package dev.demo.selection.infrastructure;

import dev.demo.selection.Settings;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.bootstrap", havingValue = "true")
public class Bootstrap implements ApplicationRunner {
    private final Database db;
    private final ReservationStore store;
    private final Settings settings;
    private final ConfigurableApplicationContext context;

    public Bootstrap(Database db, ReservationStore store, Settings settings, ConfigurableApplicationContext context) {
        this.db = db;
        this.store = store;
        this.settings = settings;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        long term = settings.term();
        if (!store.ready(term)) {
            if (db.requestCount(term) != 0) {
                throw new IllegalStateException("Redis is uninitialized but requests exist. Pause admission and reconcile; do not reset stock.");
            }
            store.initialize(term, db.courses(term));
        }
        SpringApplication.exit(context);
    }
}
