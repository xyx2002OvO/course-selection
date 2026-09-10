package dev.demo.selection.worker;

import dev.demo.selection.Settings;
import dev.demo.selection.application.SelectionService;
import dev.demo.selection.domain.Selection;
import dev.demo.selection.infrastructure.Database;
import dev.demo.selection.infrastructure.ReservationStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.worker-enabled", havingValue = "true")
public class Reconciler {
    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);
    private final ReservationStore store;
    private final Database db;
    private final SelectionService service;
    private final Settings settings;
    private final MeterRegistry meters;

    public Reconciler(ReservationStore store, Database db, SelectionService service, Settings settings, MeterRegistry meters) {
        this.store = store;
        this.db = db;
        this.service = service;
        this.settings = settings;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${app.reconcile-delay-ms:2000}", initialDelayString = "${app.initial-delay-ms:0}")
    public void reconcile() {
        // The DB scan still runs if Redis is down; committed cancellations carry result outbox events.
        for (Selection s : db.expired(settings.batchSize())) {
            attempt(s.requestId().toString(), () -> service.cancelExpired(s));
        }
        for (String id : store.due(settings.term(), settings.batchSize())) {
            attempt(id, () -> {
                Selection reservation = store.reservation(settings.term(), UUID.fromString(id))
                        .orElseThrow(() -> new IllegalStateException("Missing reservation ledger"));
                Selection terminal = service.cancelExpired(reservation);
                store.project(terminal);
                meters.counter("selection.reconciled").increment();
            });
        }
    }

    @Scheduled(fixedDelayString = "${app.reconcile-delay-ms:2000}", initialDelayString = "${app.initial-delay-ms:0}")
    public void repairStatus() {
        for (String id : store.repairs(settings.term(), settings.batchSize())) {
            attempt(id, () -> store.repairStatus(settings.term(), UUID.fromString(id), db.find(UUID.fromString(id))));
        }
    }

    private void attempt(String id, Runnable work) {
        try {
            work.run();
        } catch (Exception e) {
            meters.counter("selection.reconcile.failure").increment();
            log.warn("Reconciliation will retry request={}", id, e);
        }
    }
}
