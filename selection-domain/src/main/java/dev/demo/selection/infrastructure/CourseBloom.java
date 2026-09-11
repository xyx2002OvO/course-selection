package dev.demo.selection.infrastructure;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import dev.demo.selection.domain.Course;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * L1 in-process snapshot. confirm() never reads Redis or MySQL for catalog.
 * 穿透: miss never loads MySQL; unknown ids stay empty until the next full reload.
 * 击穿: no per-key TTL; reload is synchronized and swaps one snapshot; readers keep the old one.
 * 雪崩: one TTL for the whole map plus jitter; confirm() never waits on reload.
 */
@Component
public class CourseBloom {
    static final long TTL_MS = 60_000;
    static final long JITTER_MS = 15_000;

    private final Clock clock;
    private final long ttlMs;
    private final long jitterMs;
    private volatile Snapshot snap = Snapshot.empty();

    public CourseBloom() {
        this(Clock.systemUTC(), TTL_MS, JITTER_MS);
    }

    CourseBloom(Clock clock, long ttlMs, long jitterMs) {
        this.clock = clock;
        this.ttlMs = ttlMs;
        this.jitterMs = jitterMs;
    }

    public synchronized void reload(List<Course> courses) {
        if (courses.isEmpty() && !snap.byId.isEmpty()) return;
        BloomFilter<Long> next = BloomFilter.create(Funnels.longFunnel(), Math.max(64, courses.size() * 4), 0.01);
        Map<Long, Course> catalog = HashMap.newHashMap(courses.size());
        for (Course course : courses) {
            next.put(course.id());
            catalog.put(course.id(), course);
        }
        long jitter = jitterMs <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterMs + 1);
        snap = new Snapshot(next, Map.copyOf(catalog), clock.millis() + ttlMs + jitter);
    }

    public boolean expired() {
        return clock.millis() >= snap.expireAtMs;
    }

    public boolean loaded() {
        return !snap.byId.isEmpty();
    }

    public boolean mightContain(long courseId) {
        return snap.filter.mightContain(courseId);
    }

    /** remaining is a warmup snapshot; stock is decrement(). */
    public Optional<Course> course(long id, long term) {
        Course course = snap.byId.get(id);
        return course != null && course.termId() == term ? Optional.of(course) : Optional.empty();
    }

    private record Snapshot(BloomFilter<Long> filter, Map<Long, Course> byId, long expireAtMs) {
        static Snapshot empty() {
            return new Snapshot(BloomFilter.create(Funnels.longFunnel(), 64, 0.01), Map.of(), 0);
        }
    }
}
