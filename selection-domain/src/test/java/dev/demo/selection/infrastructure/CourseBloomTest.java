package dev.demo.selection.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import dev.demo.selection.domain.Course;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CourseBloomTest {
    @Test void unknownCourseDoesNotAppearUntilReload() {
        CourseBloom bloom = new CourseBloom();
        assertThat(bloom.course(101, 202601)).isEmpty();
        bloom.reload(List.of(course(101)));
        assertThat(bloom.course(101, 202601)).isPresent();
        assertThat(bloom.course(999, 202601)).isEmpty();
        assertThat(bloom.course(101, 202602)).isEmpty();
    }

    @Test void expiredSnapshotStillServesUntilReload() {
        MutableClock clock = new MutableClock(1_000);
        CourseBloom bloom = new CourseBloom(clock, 60_000, 0);
        bloom.reload(List.of(course(101)));
        assertThat(bloom.expired()).isFalse();
        clock.millis.set(70_000);
        assertThat(bloom.expired()).isTrue();
        assertThat(bloom.course(101, 202601)).map(Course::title).contains("Java");
        bloom.reload(List.of(new Course(101, 202601, "Java 21", 2, 3, 1, 1, 3, null, null)));
        assertThat(bloom.course(101, 202601)).map(Course::title).contains("Java 21");
        assertThat(bloom.expired()).isFalse();
    }

    @Test void emptyReloadDoesNotWipeLiveCatalog() {
        CourseBloom bloom = new CourseBloom();
        bloom.reload(List.of(course(101)));
        bloom.reload(List.of());
        assertThat(bloom.course(101, 202601)).isPresent();
    }

    private static Course course(long id) {
        return new Course(id, 202601, "Java", 2, 3, 1, 1, 3, null, null);
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis;
        private MutableClock(long millis) { this.millis = new AtomicLong(millis); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        @Override public long millis() { return millis.get(); }
    }
}
