package dev.demo.selection.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.demo.selection.Settings;
import dev.demo.selection.domain.Course;
import dev.demo.selection.domain.Selection;
import dev.demo.selection.domain.Selection.State;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationStore {
    private static final TypeReference<List<Course>> COURSES = new TypeReference<>() {};
    private final StringRedisTemplate redis;
    private final Settings settings;
    private final ObjectMapper json;
    private final DefaultRedisScript<String> reserve = script("reserve", String.class);
    private final DefaultRedisScript<String> settle = script("settle", String.class);
    private final DefaultRedisScript<Long> rate = script("rate_limit", Long.class);
    private final DefaultRedisScript<Long> repair = script("enqueue_repair", Long.class);
    private final DefaultRedisScript<Long> cache = script("cache_result", Long.class);

    public ReservationStore(StringRedisTemplate redis, Settings settings, ObjectMapper json) {
        this.redis = redis;
        this.settings = settings;
        this.json = json;
    }

    public String key(long term, String suffix) { return "selection:{" + term + "}:" + suffix; }

    public String reserve(UUID id, long student, long term, long course) {
        return redis.execute(reserve, List.of(key(term, "stock:" + course), key(term, "student:" + student),
                key(term, "reservation:" + id), key(term, "status:" + id), key(term, "due"),
                key(term, "dedup:" + id), key(term, "ready")), id.toString(), Long.toString(student),
                Long.toString(course), Long.toString(term), Integer.toString(settings.reservationSeconds() * 1000),
                Integer.toString(settings.dedupSeconds()), Integer.toString(settings.statusSeconds()),
                Integer.toString(settings.statusTtlJitterSeconds()));
    }

    public Optional<Selection> reservation(long term, UUID id) {
        Map<Object, Object> value = redis.opsForHash().entries(key(term, "reservation:" + id));
        if (value.isEmpty()) return Optional.empty();
        String state = value.get("state").toString();
        return Optional.of(new Selection(id, Long.parseLong(value.get("studentId").toString()), term,
                Long.parseLong(value.get("courseId").toString()),
                state.equals("PROCESSING") ? State.ACCEPTED : State.valueOf(state),
                value.get("reason").toString(), Instant.ofEpochMilli(Long.parseLong(value.get("deadline").toString()))));
    }

    public void project(Selection s) {
        if (!s.state().terminal()) return;
        String result = redis.execute(settle, List.of(key(s.termId(), "stock:" + s.courseId()),
                key(s.termId(), "student:" + s.studentId()), key(s.termId(), "reservation:" + s.requestId()),
                key(s.termId(), "status:" + s.requestId()), key(s.termId(), "due"), key(s.termId(), "ready")),
                s.requestId().toString(), Long.toString(s.studentId()), Long.toString(s.courseId()),
                s.state().name(), s.reason(), Integer.toString(settings.statusSeconds()),
                Integer.toString(settings.statusTtlJitterSeconds()));
        if (!"OK".equals(result)) {
            if ("MISSING_LEDGER".equals(result) || "STATE_CONFLICT".equals(result)) {
                redis.opsForValue().set(key(s.termId(), "ready"), "blocked");
            }
            throw new IllegalStateException("Redis projection failed: " + result + ", request=" + s.requestId());
        }
    }

    public record Status(String state, String reason) { }

    public Status status(long term, UUID id, long student) {
        Map<Object, Object> value = redis.opsForHash().entries(key(term, "status:" + id));
        if (!value.isEmpty()) {
            if ("NOT_FOUND".equals(value.get("state"))) return new Status("NOT_FOUND", "");
            if (!Long.toString(student).equals(value.get("studentId"))) return new Status("NOT_FOUND", "");
            return new Status(value.get("state").toString(), value.get("reason").toString());
        }
        Optional<Selection> ledger = reservation(term, id);
        if (ledger.isPresent()) {
            Selection s = ledger.get();
            if (s.studentId() != student) return new Status("NOT_FOUND", "");
            return new Status(s.state() == State.ACCEPTED ? "PROCESSING" : s.state().name(), s.reason());
        }
        redis.execute(repair, List.of(key(term, "repair")), id.toString(),
                Long.toString(System.currentTimeMillis()), "1000");
        return new Status("CONFIRMING", "");
    }

    public boolean allow(long term, long student, boolean query) {
        String operation = query ? "query" : "submit";
        Long allowed = redis.execute(rate, List.of(key(term, "rate:" + operation + ":" + student),
                key(term, "rate:" + operation + ":global")),
                Integer.toString(query ? settings.queryPerStudent() : settings.submitPerStudent()),
                Integer.toString(query ? settings.queryGlobal() : settings.submitGlobal()), "1000");
        return Long.valueOf(1).equals(allowed);
    }

    public Set<String> due(long term, int limit) {
        return redis.opsForZSet().rangeByScore(key(term, "due"), 0, System.currentTimeMillis(), 0, limit);
    }

    public Set<String> repairs(long term, int limit) {
        return redis.opsForZSet().range(key(term, "repair"), 0, limit - 1);
    }

    public void repairStatus(long term, UUID id, Optional<Selection> result) {
        Selection s = result.filter(item -> item.termId() == term).orElse(null);
        String state = s == null ? "NOT_FOUND" : s.state() == State.ACCEPTED ? "PROCESSING" : s.state().name();
        redis.execute(cache, List.of(key(term, "status:" + id)), s == null ? "0" : Long.toString(s.studentId()),
                state, s == null ? "" : s.reason(),
                s == null ? "5" : Integer.toString(settings.statusSeconds() + jitter()));
        redis.opsForZSet().remove(key(term, "repair"), id.toString());
    }

    public boolean ready(long term) { return "ready".equals(redis.opsForValue().get(key(term, "ready"))); }

    public void initialize(long term, List<Course> courses) {
        for (Course course : courses) {
            redis.opsForValue().setIfAbsent(key(term, "stock:" + course.id()), Integer.toString(course.remaining()));
        }
        saveCatalog(term, courses);
        redis.opsForValue().set(key(term, "ready"), "ready");
    }

    public Optional<List<Course>> catalog(long term) {
        String payload = redis.opsForValue().get(key(term, "catalog"));
        if (payload == null || payload.isBlank()) return Optional.empty();
        try {
            return Optional.of(json.readValue(payload, COURSES));
        } catch (JsonProcessingException e) {
            redis.delete(key(term, "catalog"));
            return Optional.empty();
        }
    }

    public void saveCatalog(long term, List<Course> courses) {
        if (courses.isEmpty()) return;
        try {
            redis.opsForValue().setIfAbsent(key(term, "catalog"), json.writeValueAsString(courses));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize course catalog", e);
        }
    }

    public void deleteCatalog(long term) {
        redis.delete(key(term, "catalog"));
    }

    public Optional<Boolean> catalogMode(long term) {
        String mode = redis.opsForValue().get(key(term, "mode"));
        if (mode == null || mode.isBlank()) return Optional.empty();
        return Optional.of("frozen".equals(mode));
    }

    public void saveCatalogMode(long term, boolean frozen) {
        redis.opsForValue().set(key(term, "mode"), frozen ? "frozen" : "open");
    }

    private int jitter() {
        int span = Math.max(0, settings.statusTtlJitterSeconds());
        return span == 0 ? 0 : ThreadLocalRandom.current().nextInt(span + 1);
    }

    private static <T> DefaultRedisScript<T> script(String name, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/" + name + ".lua"));
        script.setResultType(type);
        return script;
    }
}
