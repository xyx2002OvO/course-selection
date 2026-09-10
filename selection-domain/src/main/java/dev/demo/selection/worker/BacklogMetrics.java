package dev.demo.selection.worker;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.worker-enabled", havingValue = "true")
public class BacklogMetrics {
    private final JdbcTemplate jdbc;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestSeconds = new AtomicLong();
    private final AtomicLong active = new AtomicLong();

    public BacklogMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        meters.gauge("selection.outbox.pending", pending);
        meters.gauge("selection.outbox.oldest.seconds", oldestSeconds);
        meters.gauge("selection.requests.active", active);
    }

    @Scheduled(fixedDelay = 10000, initialDelayString = "${app.initial-delay-ms:0}")
    public void update() {
        jdbc.query("""
            SELECT COUNT(*),COALESCE(TIMESTAMPDIFF(SECOND,MIN(created_at),CURRENT_TIMESTAMP),0)
            FROM outbox_event WHERE state IN ('NEW','IN_FLIGHT')
            """, rs -> { pending.set(rs.getLong(1)); oldestSeconds.set(rs.getLong(2)); });
        active.set(jdbc.queryForObject("SELECT COUNT(*) FROM selection_request WHERE state='ACCEPTED'",Long.class));
    }
}
