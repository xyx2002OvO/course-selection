package dev.demo.selection.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.demo.selection.application.SelectionService;
import dev.demo.selection.domain.Selection;
import dev.demo.selection.domain.Selection.State;
import dev.demo.selection.infrastructure.ReservationStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class SelectionConsumerTest {
    @Test void doesNotAcknowledgeFailedTransaction() throws Exception {
        var json = JsonMapper.builder().findAndAddModules().build();
        var service = mock(SelectionService.class);
        var store = mock(ReservationStore.class);
        var ack = mock(Acknowledgment.class);
        var consumer = new SelectionConsumer(json,service,store,new SimpleMeterRegistry());
        var s = new Selection(UUID.randomUUID(),1001,202601,101,State.ACCEPTED,"",Instant.now());
        when(service.confirm(s.requestId())).thenThrow(new IllegalStateException("DB unavailable"));
        assertThatThrownBy(() -> consumer.select(json.writeValueAsString(s),ack)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ack);
    }

    @Test void acknowledgesOnlyAfterDomainCallReturns() throws Exception {
        var json = JsonMapper.builder().findAndAddModules().build();
        var service = mock(SelectionService.class);
        var store = mock(ReservationStore.class);
        var ack = mock(Acknowledgment.class);
        var consumer = new SelectionConsumer(json,service,store,new SimpleMeterRegistry());
        var s = new Selection(UUID.randomUUID(),1001,202601,101,State.SUCCESS,"",Instant.now());
        when(service.confirm(s.requestId())).thenReturn(s);
        consumer.select(json.writeValueAsString(s),ack);
        var ordered = inOrder(service,ack);
        ordered.verify(service).confirm(s.requestId());
        ordered.verify(ack).acknowledge();
    }
}
