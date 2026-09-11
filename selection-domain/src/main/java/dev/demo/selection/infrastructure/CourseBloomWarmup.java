package dev.demo.selection.infrastructure;

import dev.demo.selection.Settings;
import dev.demo.selection.domain.Course;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.bootstrap", havingValue = "false", matchIfMissing = true)
public class CourseBloomWarmup implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CourseBloomWarmup.class);
    private final Database db;
    private final CourseBloom bloom;
    private final ReservationStore store;
    private final Settings settings;
    private final CatalogMode mode;
    private final Environment environment;

    public CourseBloomWarmup(Database db, CourseBloom bloom, ReservationStore store, Settings settings,
                             CatalogMode mode, Environment environment) {
        this.db = db;
        this.bloom = bloom;
        this.store = store;
        this.settings = settings;
        this.mode = mode;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        sync();
    }

    @Scheduled(fixedDelay = 5_000, initialDelayString = "${app.initial-delay-ms:0}")
    public void refreshIfExpired() {
        sync();
    }

    @EventListener
    public void onEnvironmentChange(EnvironmentChangeEvent event) {
        if (event.getKeys().stream().anyMatch(key -> key.endsWith("catalog-frozen"))) {
            sync();
        }
    }

    void sync() {
        boolean desired = desiredFrozen();
        if (desired) {
            if (!mode.frozen() || !bloom.loaded() || bloom.expired()) enterFrozen();
        } else if (mode.frozen()) {
            leaveFrozen();
        }
    }

    private boolean desiredFrozen() {
        Boolean fromEnv = environment.getProperty("app.catalog-frozen", Boolean.class);
        if (fromEnv != null) return fromEnv;
        return store.catalogMode(settings.term()).or(() -> db.catalogFrozen(settings.term())).orElse(false);
    }

    private void enterFrozen() {
        long term = settings.term();
        boolean switching = !mode.frozen();
        try {
            if (switching) {
                store.deleteCatalog(term);
                List<Course> courses = db.courses(term);
                bloom.reload(courses);
                store.saveCatalog(term, courses);
            } else {
                reloadShared();
            }
            if (!bloom.loaded()) return;
            store.saveCatalogMode(term, true);
            db.saveCatalogFrozen(term, true);
            mode.setFrozen(true);
        } catch (RuntimeException e) {
            log.warn("Could not enter frozen catalog mode; staying on SQL", e);
        }
    }

    private void leaveFrozen() {
        long term = settings.term();
        mode.setFrozen(false);
        try {
            store.saveCatalogMode(term, false);
            db.saveCatalogFrozen(term, false);
        } catch (RuntimeException e) {
            log.warn("Could not publish catalog unfreeze", e);
        }
    }

    private void reloadShared() {
        long term = settings.term();
        try {
            List<Course> shared = store.catalog(term).orElse(null);
            if (shared != null) {
                bloom.reload(shared);
                return;
            }
        } catch (RuntimeException e) {
            if (bloom.loaded()) {
                log.warn("Shared course catalog unavailable; keeping snapshot", e);
                return;
            }
            log.warn("Shared course catalog unavailable; loading MySQL", e);
        }
        try {
            List<Course> courses = db.courses(term);
            bloom.reload(courses);
            try {
                store.saveCatalog(term, courses);
            } catch (RuntimeException e) {
                log.warn("Could not publish shared course catalog", e);
            }
        } catch (RuntimeException e) {
            log.warn("Course catalog reload failed; keeping snapshot", e);
        }
    }
}
