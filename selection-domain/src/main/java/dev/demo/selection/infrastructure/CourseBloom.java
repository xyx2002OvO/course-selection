package dev.demo.selection.infrastructure;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import dev.demo.selection.domain.Course;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CourseBloom {
    private volatile BloomFilter<Long> filter = BloomFilter.create(Funnels.longFunnel(), 64, 0.01);

    public synchronized void reload(List<Course> courses) {
        BloomFilter<Long> next = BloomFilter.create(Funnels.longFunnel(), Math.max(64, courses.size() * 4), 0.01);
        for (Course course : courses) {
            next.put(course.id());
        }
        filter = next;
    }

    public boolean mightContain(long courseId) {
        return filter.mightContain(courseId);
    }
}
