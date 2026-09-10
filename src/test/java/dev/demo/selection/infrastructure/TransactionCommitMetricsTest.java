package dev.demo.selection.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionExecution;

class TransactionCommitMetricsTest {
    @Test void recordsSuccessfulCommitDuration() {
        var meters = new SimpleMeterRegistry();
        var metrics = new TransactionCommitMetrics(meters);
        TransactionExecution tx = new TransactionExecution() {
            public String getTransactionName() { return "test"; }
            public boolean isNewTransaction() { return true; }
            public boolean isNested() { return false; }
            public boolean hasSavepoint() { return false; }
            public boolean isReadOnly() { return false; }
            public boolean isRollbackOnly() { return false; }
            public boolean isCompleted() { return false; }
            public void setRollbackOnly() { }
        };
        metrics.beforeCommit(tx);
        metrics.afterCommit(tx, null);
        assertThat(meters.find("selection.tx.commit").timer().count()).isEqualTo(1);
        assertThat(meters.find("selection.tx.commit").timer().totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isPositive();
    }

    @Test void ignoresFailedCommit() {
        var meters = new SimpleMeterRegistry();
        var metrics = new TransactionCommitMetrics(meters);
        TransactionExecution tx = new TransactionExecution() {
            public String getTransactionName() { return "test"; }
            public boolean isNewTransaction() { return true; }
            public boolean isNested() { return false; }
            public boolean hasSavepoint() { return false; }
            public boolean isReadOnly() { return false; }
            public boolean isRollbackOnly() { return false; }
            public boolean isCompleted() { return false; }
            public void setRollbackOnly() { }
        };
        metrics.beforeCommit(tx);
        metrics.afterCommit(tx, new IllegalStateException("commit failed"));
        assertThat(meters.find("selection.tx.commit").timer().count()).isZero();
    }
}
