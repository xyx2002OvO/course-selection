package dev.demo.selection.domain;

import java.time.Instant;
import java.util.UUID;

public record Selection(UUID requestId, long studentId, long termId, long courseId,
                        State state, String reason, Instant deadline) {
    public enum State {
        ACCEPTED, SUCCESS, REJECTED, CANCELLED;
        public boolean terminal() { return this != ACCEPTED; }
    }

    public void requireIdentity(long student, long term, long course) {
        if (studentId != student || termId != term || courseId != course) {
            throw new IllegalArgumentException("IDEMPOTENCY_KEY_REUSED");
        }
    }
}
