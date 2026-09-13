package dev.demo.selection.application;

import dev.demo.selection.domain.Selection;
import dev.demo.selection.domain.Selection.State;
import dev.demo.selection.domain.SelectionRules;
import dev.demo.selection.infrastructure.CatalogMode;
import dev.demo.selection.infrastructure.CourseBloom;
import dev.demo.selection.infrastructure.Database;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SelectionService {
    private final Database db;
    private final SelectionRules rules;
    private final CourseBloom courses;
    private final CatalogMode mode;

    public SelectionService(Database db, SelectionRules rules, CourseBloom courses, CatalogMode mode) {
        this.db = db;
        this.rules = rules;
        this.courses = courses;
        this.mode = mode;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public Selection accept(Selection reservation) {
        db.insertRequest(reservation);
        Selection current = db.lockRequest(reservation.requestId());
        current.requireIdentity(reservation.studentId(), reservation.termId(), reservation.courseId());
        if (current.state().terminal()) return current;
        if (!current.deadline().isAfter(Instant.now())) {
            return db.finish(current, State.CANCELLED, "PROCESSING_TIMEOUT");
        }
        db.event(current, "COMMAND");
        return current;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public Selection enrollSync(Selection reservation) {
        db.insertRequest(reservation);
        Selection current = db.lockRequest(reservation.requestId());
        current.requireIdentity(reservation.studentId(), reservation.termId(), reservation.courseId());
        if (current.state().terminal()) return current;
        if (!current.deadline().isAfter(Instant.now())) {
            return complete(current, State.CANCELLED, "PROCESSING_TIMEOUT", false);
        }
        return confirmLocked(current, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public Selection confirm(UUID id) {
        Selection s = db.lockRequest(id);
        if (s.state().terminal()) return s;
        if (!s.deadline().isAfter(Instant.now())) return db.finish(s, State.CANCELLED, "PROCESSING_TIMEOUT");
        return confirmLocked(s, true);
    }

    private Selection confirmLocked(Selection s, boolean writeOutbox) {
        var credits = db.lockStudent(s.studentId(), s.termId());
        if (credits.isEmpty()) return complete(s, State.REJECTED, "STUDENT_NOT_ELIGIBLE", writeOutbox);
        // READ_COMMITTED plus the student row lock makes all rule reads follow the preceding commit.
        var target = (mode.frozen()
                ? courses.course(s.courseId(), s.termId())
                : db.course(s.courseId(), s.termId())).orElse(null);
        if (target == null) return complete(s, State.REJECTED, "COURSE_NOT_FOUND", writeOutbox);
        String rejection = rules.rejection(target, db.enrolled(s.studentId(), s.termId()),
                credits.get(), db.passed(s.studentId()));
        if (!rejection.isEmpty()) return complete(s, State.REJECTED, rejection, writeOutbox);
        if (!db.decrement(s.courseId(), s.termId())) return complete(s, State.REJECTED, "SOLD_OUT", writeOutbox);
        db.enroll(s);
        return complete(s, State.SUCCESS, "", writeOutbox);
    }

    private Selection complete(Selection s, State state, String reason, boolean writeOutbox) {
        return writeOutbox ? db.finish(s, state, reason) : db.finish(s, state, reason, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public Selection cancelExpired(Selection reservation) {
        if (reservation.deadline().isAfter(Instant.now())) {
            throw new IllegalArgumentException("Reservation has not expired");
        }
        Selection tombstone = new Selection(reservation.requestId(), reservation.studentId(),
                reservation.termId(), reservation.courseId(), State.CANCELLED, "PROCESSING_TIMEOUT",
                reservation.deadline());
        // The primary-key upsert arbitrates a missing-row cancellation against a late accept.
        db.insertRequest(tombstone);
        Selection current = db.lockRequest(reservation.requestId());
        current.requireIdentity(reservation.studentId(), reservation.termId(), reservation.courseId());
        if (!current.state().terminal() && current.deadline().isAfter(Instant.now())) return current;
        if (!current.state().terminal()) return db.finish(current, State.CANCELLED, "PROCESSING_TIMEOUT");
        db.event(current, "RESULT");
        return current;
    }
}
