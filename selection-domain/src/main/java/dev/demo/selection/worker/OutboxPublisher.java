package dev.demo.selection.worker;

import dev.demo.selection.Settings;
import dev.demo.selection.infrastructure.OutboxRepository;
import dev.demo.selection.infrastructure.OutboxRepository.Event;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.worker-enabled", havingValue = "true")
@ConditionalOnProperty(name = "app.outbox-publisher-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Settings settings;
    private final MeterRegistry meters;

    public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka, Settings settings, MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.settings = settings;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${app.outbox-delay-ms:1}", initialDelayString = "${app.initial-delay-ms:0}")
    public void publish() {
        List<Event> events = outbox.claim(settings.batchSize());
        if (events.isEmpty()) return;
        record Attempt(Event event, CompletableFuture<SendResult<String, String>> future) { }
        List<Attempt> attempts = new ArrayList<>(events.size());
        for (Event event : events) {
            String topic = event.kind().equals("COMMAND") ? KafkaConfiguration.REQUESTS : KafkaConfiguration.RESULTS;
            attempts.add(new Attempt(event, kafka.send(topic, event.key(), event.payload())));
        }
        List<Long> sent = new ArrayList<>();
        for (Attempt attempt : attempts) {
            try {
                attempt.future().get(12, TimeUnit.SECONDS);
                sent.add(attempt.event().id());
                meters.counter("selection.outbox.sent", "kind", attempt.event().kind()).increment();
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                Throwable cause = e.getCause() == null ? e : e.getCause();
                Exception failure = cause instanceof Exception ex ? ex : new IllegalStateException(cause);
                outbox.retry(attempt.event(), failure);
                meters.counter("selection.outbox.failure").increment();
                log.warn("Outbox delivery retry id={} cause={}", attempt.event().id(), failure.toString());
            }
        }
        outbox.sent(events.getFirst().claim(), sent);
    }
}
