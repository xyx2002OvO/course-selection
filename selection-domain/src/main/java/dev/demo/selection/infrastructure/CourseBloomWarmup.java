package dev.demo.selection.infrastructure;

import dev.demo.selection.Settings;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.bootstrap", havingValue = "false", matchIfMissing = true)
public class CourseBloomWarmup implements ApplicationRunner {
    private final Database db;
    private final CourseBloom bloom;
    private final Settings settings;

    public CourseBloomWarmup(Database db, CourseBloom bloom, Settings settings) {
        this.db = db;
        this.bloom = bloom;
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        bloom.reload(db.courses(settings.term()));
    }
}
