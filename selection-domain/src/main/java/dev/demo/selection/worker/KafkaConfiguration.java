package dev.demo.selection.worker;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import java.util.concurrent.TimeUnit;

@Configuration
@ConditionalOnProperty(name = "app.worker-enabled", havingValue = "true")
public class KafkaConfiguration {
    public static final String REQUESTS = "selection.requests.v1";
    public static final String RESULTS = "selection.results.v1";

    @Bean NewTopic requestTopic() { return TopicBuilder.name(REQUESTS).partitions(12).replicas(1).build(); }
    @Bean NewTopic resultTopic() { return TopicBuilder.name(RESULTS).partitions(12).replicas(1).build(); }
    @Bean NewTopic requestDlt() { return TopicBuilder.name(REQUESTS + ".DLT").partitions(12).replicas(1).build(); }
    @Bean NewTopic resultDlt() { return TopicBuilder.name(RESULTS + ".DLT").partitions(12).replicas(1).build(); }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        DefaultErrorHandler handler = new DefaultErrorHandler((record, failure) -> {
            try {
                var dead = new ProducerRecord<String, String>(record.topic() + ".DLT", record.partition(),
                        (String) record.key(), (String) record.value());
                dead.headers().add("error", failure.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                kafka.send(dead).get(12, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("DLT delivery failed; keep original offset uncommitted", e);
            }
        }, new FixedBackOff(1000, 4));
        handler.setCommitRecovered(true);
        return handler;
    }
}
