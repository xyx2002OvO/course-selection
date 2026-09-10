package dev.demo.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import dev.demo.selection.application.SelectionService;
import dev.demo.selection.domain.Selection;
import dev.demo.selection.domain.Selection.State;
import dev.demo.selection.infrastructure.Database;
import dev.demo.selection.infrastructure.ReservationStore;
import dev.demo.selection.worker.OutboxPublisher;
import dev.demo.selection.worker.Reconciler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(properties = {"app.initial-delay-ms=3600000", "app.worker-enabled=true"})
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SelectionFlowIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.2");

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> {
            String url = MYSQL.getJdbcUrl();
            String extra = "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
            return url + (url.contains("?") ? "&" : "?") + extra;
        });
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
        p.add("spring.data.redis.host", REDIS::getHost);
        p.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        p.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired SelectionService service;
    @Autowired ReservationStore store;
    @Autowired Database db;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired Reconciler reconciler;
    @Autowired OutboxPublisher publisher;

    @BeforeEach
    void resetIsolatedContainers() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM enrollment");
        jdbc.update("DELETE FROM selection_request");
        jdbc.update("UPDATE course SET remaining=capacity");
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        store.initialize(202601,db.courses(202601));
    }

    @Test @Order(1)
    void duplicateSubmissionDeductsOnlyOnceAndBindsPayload() {
        UUID id = UUID.randomUUID();
        assertThat(store.reserve(id,1001,202601,101)).isEqualTo("RESERVED");
        assertThat(store.reserve(id,1001,202601,101)).isEqualTo("EXISTING");
        assertThat(store.reserve(id,1001,202601,102)).isEqualTo("KEY_REUSED");
        assertThat(redis.opsForValue().get(store.key(202601,"stock:101"))).isEqualTo("1");
        assertThat(store.reserve(UUID.randomUUID(),1001,202601,102)).isEqualTo("STUDENT_BUSY");
    }

    @Test @Order(2)
    void concurrentDifferentRequestsForSameStudentSeeCommittedConflict() throws Exception {
        Selection a = acceptDirect(1001,101);
        Selection b = acceptDirect(1001,102);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Selection> first = pool.submit(() -> service.confirm(a.requestId()));
            Future<Selection> second = pool.submit(() -> service.confirm(b.requestId()));
            List<Selection> results = List.of(first.get(),second.get());
            assertThat(results).filteredOn(s -> s.state() == State.SUCCESS).hasSize(1);
            assertThat(results).filteredOn(s -> s.reason().equals("TIME_CONFLICT")).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM enrollment",Integer.class)).isEqualTo(1);
    }

    @Test @Order(3)
    void duplicateConsumptionCreatesOnlyOneEnrollmentAndResultEvent() throws Exception {
        Selection a = acceptDirect(1001,101);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.confirm(a.requestId()));
            var second = pool.submit(() -> service.confirm(a.requestId()));
            assertThat(first.get().state()).isEqualTo(State.SUCCESS);
            assertThat(second.get().state()).isEqualTo(State.SUCCESS);
        }
        assertThat(jdbc.queryForObject("SELECT remaining FROM course WHERE id=101",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE kind='RESULT'",Integer.class)).isEqualTo(1);
    }

    @Test @Order(4)
    void orphanReservationIsCancelledAndRefundedOnce() {
        Selection hold = reserve(1001,101);
        expireRedis(hold);
        reconciler.reconcile();
        Selection cancelled = db.find(hold.requestId()).orElseThrow();
        assertThat(cancelled.state()).isEqualTo(State.CANCELLED);
        store.project(cancelled);
        assertThat(redis.opsForValue().get(store.key(202601,"stock:101"))).isEqualTo("2");
        assertThat(service.accept(hold).state()).isEqualTo(State.CANCELLED);
        assertThat(service.confirm(hold.requestId()).state()).isEqualTo(State.CANCELLED);
    }

    @Test @Order(5)
    void staleResultDoesNotReleaseNewStudentLease() {
        Selection old = reserve(1001,101);
        service.accept(old);
        Selection success = service.confirm(old.requestId());
        String lease = store.key(202601,"student:1001");
        redis.delete(lease);
        Selection next = reserve(1001,102);
        store.project(success);
        assertThat(redis.opsForValue().get(lease)).isEqualTo(next.requestId().toString());
        assertThat(redis.opsForValue().get(store.key(202601,"stock:101"))).isEqualTo("1");
    }

    @Test @Order(6)
    void lostResultDeliveryIsRepairedWithoutRefundingSuccess() {
        Selection hold = reserve(1001,101);
        service.accept(hold);
        service.confirm(hold.requestId());
        expireRedis(hold);
        reconciler.reconcile();
        assertThat(store.status(202601,hold.requestId(),1001).state()).isEqualTo("SUCCESS");
        assertThat(redis.opsForValue().get(store.key(202601,"stock:101"))).isEqualTo("1");
    }

    @Test @Order(7)
    void databaseStockDoesNotOversellUnderConcurrentConsumers() throws Exception {
        List<Selection> requests = new ArrayList<>();
        for (int i=0;i<20;i++) {
            long student=2000+i;
            jdbc.update("INSERT INTO student_term_state VALUES (?,202601,8) ON DUPLICATE KEY UPDATE max_credits=8",student);
            requests.add(acceptDirect(student,101));
        }
        try (var pool = Executors.newFixedThreadPool(10)) {
            List<Future<Selection>> tasks = new ArrayList<>();
            for (Selection s:requests) tasks.add(pool.submit(() -> service.confirm(s.requestId())));
            int success=0;
            for (Future<Selection> task:tasks) if (task.get().state()==State.SUCCESS) success++;
            assertThat(success).isEqualTo(2);
        }
        assertThat(jdbc.queryForObject("SELECT remaining FROM course WHERE id=101",Integer.class)).isZero();
    }

    @Test @Order(8)
    void resultCacheCannotBeRegressedByDelayedRepairAndIsOwnerScoped() {
        Selection hold=reserve(1001,101);
        service.accept(hold);
        Selection finalResult=service.confirm(hold.requestId());
        store.project(finalResult);
        store.repairStatus(202601,hold.requestId(),java.util.Optional.of(hold));
        assertThat(store.status(202601,hold.requestId(),1001).state()).isEqualTo("SUCCESS");
        assertThat(store.status(202601,hold.requestId(),1002).state()).isEqualTo("NOT_FOUND");
    }

    @Test @Order(9)
    void missingCacheUsesBoundedAsyncRepairInsteadOfSynchronousDatabaseRead() {
        Selection existing=acceptDirect(1001,101);
        assertThat(store.status(202601,existing.requestId(),1001).state()).isEqualTo("CONFIRMING");
        reconciler.repairStatus();
        assertThat(store.status(202601,existing.requestId(),1001).state()).isEqualTo("PROCESSING");
    }

    @Test @Order(10)
    void rejectedSelectionRefundsOnce() {
        Selection hold=reserve(1003,103);
        service.accept(hold);
        Selection rejection=service.confirm(hold.requestId());
        assertThat(rejection.reason()).isEqualTo("PREREQUISITE_NOT_MET");
        store.project(rejection);
        store.project(rejection);
        assertThat(redis.opsForValue().get(store.key(202601,"stock:103"))).isEqualTo("1");
        assertThat(redis.opsForValue().get(store.key(202601,"student:1003"))).isNull();
    }

    @Test @Order(99)
    void outboxKafkaConsumerAndRedisProjectionWorkEndToEndWithReplay() {
        Selection hold=reserve(1001,101);
        service.accept(hold);
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            publisher.publish();
            assertThat(store.status(202601,hold.requestId(),1001).state()).isEqualTo("SUCCESS");
        });
        jdbc.update("UPDATE outbox_event SET state='NEW',next_attempt_at=CURRENT_TIMESTAMP(6) WHERE request_id=?",hold.requestId().toString());
        publisher.publish();
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM enrollment",Integer.class)).isEqualTo(1);
            assertThat(redis.opsForValue().get(store.key(202601,"stock:101"))).isEqualTo("1");
        });
    }

    private Selection reserve(long student,long course) {
        UUID id=UUID.randomUUID();
        assertThat(store.reserve(id,student,202601,course)).isEqualTo("RESERVED");
        return store.reservation(202601,id).orElseThrow();
    }

    private Selection acceptDirect(long student,long course) {
        return service.accept(new Selection(UUID.randomUUID(),student,202601,course,State.ACCEPTED,"",Instant.now().plusSeconds(120)));
    }

    private void expireRedis(Selection s) {
        long past=System.currentTimeMillis()-1000;
        redis.opsForHash().put(store.key(202601,"reservation:"+s.requestId()),"deadline",Long.toString(past));
        redis.opsForZSet().add(store.key(202601,"due"),s.requestId().toString(),past);
    }
}
