package dev.demo.selection.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.demo.selection.application.SelectionService;
import dev.demo.selection.domain.Selection;
import dev.demo.selection.infrastructure.ReservationStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.worker-enabled", havingValue = "true")
public class SelectionConsumer {
    private final ObjectMapper json;
    private final SelectionService service;
    private final ReservationStore store;
    private final MeterRegistry meters;
    private final Timer confirm;

    public SelectionConsumer(ObjectMapper json, SelectionService service, ReservationStore store, MeterRegistry meters) {
        this.json = json;
        this.service = service;
        this.store = store;
        this.meters = meters;
        this.confirm = Timer.builder("selection.confirm.duration")
                .description("confirm() including connection acquire, SQL, and commit")
                .register(meters);
    }

    @KafkaListener(topics = KafkaConfiguration.REQUESTS, groupId = "selection-domain-v1",
            concurrency = "${app.confirm-concurrency:6}")
    public void select(String message, Acknowledgment ack) throws JsonProcessingException {
        Selection event = json.readValue(message, Selection.class);
        Selection result = confirm.record(() -> service.confirm(event.requestId()));
        meters.counter("selection.consumed", "state", result.state().name()).increment();
        // The proxied domain call has committed before the offset is acknowledged.
        ack.acknowledge();
    }

    @KafkaListener(topics = KafkaConfiguration.RESULTS, groupId = "selection-projection-v1",
            concurrency = "${app.project-concurrency:6}")
    public void project(String message, Acknowledgment ack) throws JsonProcessingException {
        Selection event = json.readValue(message, Selection.class);
        if (!event.state().terminal()) throw new IllegalArgumentException("Expected terminal result");
        store.project(event);
        ack.acknowledge();
    }
}
