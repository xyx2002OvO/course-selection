package dev.demo.selection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import dev.demo.selection.domain.Course;
import dev.demo.selection.domain.Selection;
import dev.demo.selection.domain.Selection.State;
import dev.demo.selection.domain.SelectionRules;
import dev.demo.selection.infrastructure.CatalogMode;
import dev.demo.selection.infrastructure.CourseBloom;
import dev.demo.selection.infrastructure.Database;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SelectionServiceTest {
    private final Database db = mock(Database.class);
    private final CourseBloom courses = new CourseBloom();
    private final CatalogMode mode = new CatalogMode();
    private final SelectionService service = new SelectionService(db, new SelectionRules(), courses, mode);

    @Test void duplicateTerminalMessageDoesNotTouchInventory() {
        Selection s = selection(State.SUCCESS, Instant.now().plusSeconds(60));
        when(db.lockRequest(s.requestId())).thenReturn(s);
        assertThat(service.confirm(s.requestId())).isEqualTo(s);
        verify(db, never()).decrement(anyLong(), anyLong());
        verify(db, never()).lockStudent(anyLong(), anyLong());
    }

    @Test void staleAdmissionCannotResurrectCancellation() {
        Selection cancelled = selection(State.CANCELLED, Instant.now().minusSeconds(1));
        Selection incoming = new Selection(cancelled.requestId(),1001,202601,101,State.ACCEPTED,"",cancelled.deadline());
        when(db.lockRequest(cancelled.requestId())).thenReturn(cancelled);
        assertThat(service.accept(incoming).state()).isEqualTo(State.CANCELLED);
        verify(db, never()).event(any(), eq("COMMAND"));
    }

    @Test void rejectsReusingIdForAnotherCourse() {
        Selection old = selection(State.SUCCESS, Instant.now());
        when(db.lockRequest(old.requestId())).thenReturn(old);
        Selection changed = new Selection(old.requestId(),1001,202601,102,State.ACCEPTED,"",old.deadline());
        assertThatThrownBy(() -> service.accept(changed)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unfrozenConfirmReadsSqlCatalog() {
        Selection s = selection(State.ACCEPTED, Instant.now().plusSeconds(60));
        when(db.lockRequest(s.requestId())).thenReturn(s);
        when(db.lockStudent(1001,202601)).thenReturn(Optional.of(8));
        when(db.course(101,202601)).thenReturn(Optional.of(new Course(101,202601,"Target",2,3,1,1,3,null,null)));
        when(db.enrolled(1001,202601)).thenReturn(List.of());
        when(db.passed(1001)).thenReturn(java.util.Set.of());
        when(db.decrement(101,202601)).thenReturn(true);
        when(db.finish(s, State.SUCCESS, "")).thenReturn(s);
        service.confirm(s.requestId());
        verify(db).course(101,202601);
        verify(db, never()).decrement(eq(102L), anyLong());
    }

    @Test void frozenConfirmReadsLocalCatalog() {
        mode.setFrozen(true);
        Selection s = selection(State.ACCEPTED,Instant.now().plusSeconds(60));
        when(db.lockRequest(s.requestId())).thenReturn(s);
        when(db.lockStudent(1001,202601)).thenReturn(Optional.of(8));
        courses.reload(List.of(
                new Course(101,202601,"Target",2,3,1,1,3,null,null),
                new Course(102,202601,"Taken",2,3,1,2,4,null,null)));
        when(db.enrolled(1001,202601)).thenReturn(List.of(
                new Course(102,202601,"Taken",2,3,1,2,4,null,null)));
        when(db.passed(1001)).thenReturn(java.util.Set.of());
        service.confirm(s.requestId());
        verify(db, never()).course(anyLong(), anyLong());
        verify(db).finish(s,State.REJECTED,"TIME_CONFLICT");
        verify(db,never()).decrement(anyLong(),anyLong());
    }

    @Test void missingCatalogCourseDoesNotTouchInventory() {
        mode.setFrozen(true);
        Selection s = selection(State.ACCEPTED,Instant.now().plusSeconds(60));
        when(db.lockRequest(s.requestId())).thenReturn(s);
        when(db.lockStudent(1001,202601)).thenReturn(Optional.of(8));
        service.confirm(s.requestId());
        verify(db).finish(s,State.REJECTED,"COURSE_NOT_FOUND");
        verify(db,never()).enrolled(anyLong(),anyLong());
        verify(db,never()).decrement(anyLong(),anyLong());
    }

    @Test void cancellationPreservesCommittedSuccess() {
        Selection old = selection(State.SUCCESS,Instant.now().minusSeconds(1));
        when(db.lockRequest(old.requestId())).thenReturn(old);
        assertThat(service.cancelExpired(old)).isEqualTo(old);
        verify(db,never()).finish(any(),any(),anyString());
        verify(db).event(old,"RESULT");
    }

    private Selection selection(State state,Instant deadline) {
        return new Selection(UUID.randomUUID(),1001,202601,101,state,"",deadline);
    }
}
