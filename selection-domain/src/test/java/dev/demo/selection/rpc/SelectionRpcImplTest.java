package dev.demo.selection.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import dev.demo.selection.Settings;
import dev.demo.selection.application.SelectionService;
import dev.demo.selection.domain.Selection;
import dev.demo.selection.domain.Selection.State;
import dev.demo.selection.infrastructure.CatalogMode;
import dev.demo.selection.infrastructure.CourseBloom;
import dev.demo.selection.infrastructure.ReservationStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SelectionRpcImplTest {
    private final ReservationStore store = mock(ReservationStore.class);
    private final SelectionService service = mock(SelectionService.class);
    private final CourseBloom bloom = mock(CourseBloom.class);
    private final CatalogMode mode = new CatalogMode();
    private final Settings settings = new Settings(false, false, false, "demo", 202601, 120, 30, 86400, 50,
            1001, 1005, 5, 200, 10, 1000, 50, 300, false, false);
    private final SelectionRpcImpl rpc = new SelectionRpcImpl(store, service, bloom, mode, settings,
            new SimpleMeterRegistry());

    @Test
    void busyStudentDoesNotAccept() {
        when(store.allow(202601, 1001, false)).thenReturn(true);
        when(store.reserve(any(), eq(1001L), eq(202601L), eq(101L))).thenReturn("STUDENT_BUSY");
        SubmitResult result = rpc.submit(new SubmitCommand(UUID.randomUUID(), 1001, 202601, 101));
        assertThat(result.getCode()).isEqualTo("STUDENT_BUSY");
        verify(service, never()).accept(any());
        verify(bloom, never()).mightContain(anyLong());
    }

    @Test
    void frozenUnknownCourseIsRejectedWithoutReserve() {
        mode.setFrozen(true);
        when(bloom.mightContain(101)).thenReturn(false);
        SubmitResult result = rpc.submit(new SubmitCommand(UUID.randomUUID(), 1001, 202601, 101));
        assertThat(result.getCode()).isEqualTo("COURSE_UNKNOWN");
        verify(store, never()).reserve(any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void reservedRequestIsAccepted() {
        UUID id = UUID.randomUUID();
        Selection reservation = new Selection(id, 1001, 202601, 101, State.ACCEPTED, "", Instant.now().plusSeconds(60));
        when(store.allow(202601, 1001, false)).thenReturn(true);
        when(store.reserve(id, 1001, 202601, 101)).thenReturn("RESERVED");
        when(store.reservation(202601, id)).thenReturn(Optional.of(reservation));
        when(service.accept(reservation)).thenReturn(reservation);
        SubmitResult result = rpc.submit(new SubmitCommand(id, 1001, 202601, 101));
        assertThat(result.getCode()).isEqualTo("OK");
        assertThat(result.getState()).isEqualTo("PROCESSING");
        verify(service).accept(reservation);
        verify(service, never()).enrollSync(any());
        verify(store, never()).project(any());
    }

    @Test
    void syncBaselineConfirmsAndProjectsInRequest() {
        Settings sync = new Settings(false, false, false, "demo", 202601, 120, 30, 86400, 50,
                1001, 1005, 5, 200, 10, 1000, 50, 300, false, true);
        SelectionRpcImpl syncRpc = new SelectionRpcImpl(store, service, bloom, mode, sync, new SimpleMeterRegistry());
        UUID id = UUID.randomUUID();
        Selection reservation = new Selection(id, 1001, 202601, 101, State.ACCEPTED, "", Instant.now().plusSeconds(60));
        Selection done = new Selection(id, 1001, 202601, 101, State.SUCCESS, "", reservation.deadline());
        when(store.allow(202601, 1001, false)).thenReturn(true);
        when(store.reserve(id, 1001, 202601, 101)).thenReturn("RESERVED");
        when(store.reservation(202601, id)).thenReturn(Optional.of(reservation));
        when(service.enrollSync(reservation)).thenReturn(done);
        SubmitResult result = syncRpc.submit(new SubmitCommand(id, 1001, 202601, 101));
        assertThat(result.getCode()).isEqualTo("OK");
        assertThat(result.getState()).isEqualTo("SUCCESS");
        verify(service).enrollSync(reservation);
        verify(service, never()).accept(any());
        verify(store).project(done);
    }
}
