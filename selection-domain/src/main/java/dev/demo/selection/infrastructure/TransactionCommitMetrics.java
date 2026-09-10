package dev.demo.selection.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

@Component
public class TransactionCommitMetrics implements TransactionExecutionListener {
    private final Timer commit;
    private final ThreadLocal<Long> started = new ThreadLocal<>();

    public TransactionCommitMetrics(MeterRegistry meters) {
        this.commit = Timer.builder("selection.tx.commit")
                .description("JDBC commit wall time, from beforeCommit to afterCommit")
                .register(meters);
    }

    @Override
    public void beforeCommit(TransactionExecution transaction) {
        started.set(System.nanoTime());
    }

    @Override
    public void afterCommit(TransactionExecution transaction, Throwable commitFailure) {
        Long start = started.get();
        started.remove();
        if (start != null && commitFailure == null) {
            commit.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void afterRollback(TransactionExecution transaction, Throwable rollbackFailure) {
        started.remove();
    }
}
