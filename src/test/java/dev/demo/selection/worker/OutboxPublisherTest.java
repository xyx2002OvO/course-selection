package dev.demo.selection.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import dev.demo.selection.Settings;
import dev.demo.selection.infrastructure.OutboxRepository;
import dev.demo.selection.infrastructure.OutboxRepository.Event;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxPublisherTest {
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final Settings settings = new Settings(true, true, false, "demo", 202601, 120, 30, 86400, 50,
            1001, 1005, 5, 200, 10, 1000);
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(outbox, kafka, settings, new SimpleMeterRegistry());
    }

    @Test
    void emptyClaimDoesNotTouchKafka() {
        when(outbox.claim(50)).thenReturn(List.of());
        publisher.publish();
        verify(kafka, never()).send(anyString(), anyString(), anyString());
        verify(outbox, never()).sent(any(), any());
    }

    @Test
    void sendsBatchInIdOrderThenMarksSuccesses() {
        Event command = new Event(10, "COMMAND", "1001:202601", "c", 0, "tok");
        Event result = new Event(11, "RESULT", "1001:202601", "r", 0, "tok");
        when(outbox.claim(50)).thenReturn(List.of(command, result));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(completed());
        publisher.publish();
        var order = inOrder(kafka, outbox);
        order.verify(kafka).send(KafkaConfiguration.REQUESTS, "1001:202601", "c");
        order.verify(kafka).send(KafkaConfiguration.RESULTS, "1001:202601", "r");
        order.verify(outbox).sent("tok", List.of(10L, 11L));
        verify(outbox, never()).retry(any(), any());
    }

    @Test
    void partialBrokerFailureRetriesOnlyFailedRows() {
        Event ok = new Event(1, "COMMAND", "1001:202601", "a", 0, "tok");
        Event bad = new Event(2, "COMMAND", "1002:202601", "b", 0, "tok");
        when(outbox.claim(50)).thenReturn(List.of(ok, bad));
        when(kafka.send(eq(KafkaConfiguration.REQUESTS), eq("1001:202601"), eq("a"))).thenReturn(completed());
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker down"));
        when(kafka.send(eq(KafkaConfiguration.REQUESTS), eq("1002:202601"), eq("b"))).thenReturn(failed);
        publisher.publish();
        verify(outbox).sent("tok", List.of(1L));
        verify(outbox).retry(eq(bad), any());
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<SendResult<String, String>> completed() {
        return CompletableFuture.completedFuture(mock(SendResult.class));
    }
}
