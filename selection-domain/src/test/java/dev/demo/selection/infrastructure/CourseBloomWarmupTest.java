package dev.demo.selection.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import dev.demo.selection.Settings;
import dev.demo.selection.domain.Course;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CourseBloomWarmupTest {
    private final Settings settings = new Settings(true, true, false, "demo", 202601, 120, 30, 86400, 50,
            1001, 1005, 5, 200, 10, 1000, 50, 300, false, false);
    private final List<Course> java = List.of(new Course(101, 202601, "Java", 2, 3, 1, 1, 3, null, null));

    @Test void unfrozenDoesNotLoadCatalogCache() {
        Database db = mock(Database.class);
        ReservationStore store = mock(ReservationStore.class);
        CourseBloom bloom = new CourseBloom();
        CatalogMode mode = new CatalogMode();
        warmup(db, bloom, store, mode, "false").run(null);
        assertThat(mode.frozen()).isFalse();
        verify(db, never()).courses(anyLong());
        verify(store, never()).catalog(anyLong());
    }

    @Test void sharedCatalogSkipsMysqlOnceFrozen() {
        Database db = mock(Database.class);
        ReservationStore store = mock(ReservationStore.class);
        when(db.courses(202601L)).thenReturn(java);
        when(store.catalog(202601L)).thenReturn(Optional.of(java));
        CourseBloom bloom = new CourseBloom(Clock.systemUTC(), 0, 0);
        CatalogMode mode = new CatalogMode();
        CourseBloomWarmup warmup = warmup(db, bloom, store, mode, "true");
        warmup.run(null);
        assertThat(mode.frozen()).isTrue();
        warmup.refreshIfExpired();
        verify(db, times(1)).courses(202601L);
        verify(store, atLeastOnce()).catalog(202601L);
    }

    @Test void mysqlFillWritesSharedCatalog() {
        Database db = mock(Database.class);
        ReservationStore store = mock(ReservationStore.class);
        when(db.courses(202601L)).thenReturn(java);
        CourseBloom bloom = new CourseBloom();
        warmup(db, bloom, store, new CatalogMode(), "true").run(null);
        verify(store).deleteCatalog(202601L);
        verify(store).saveCatalog(202601L, java);
        verify(store).saveCatalogMode(202601L, true);
        verify(db).saveCatalogFrozen(202601L, true);
    }

    @Test void redisDownKeepsLocalAndSkipsMysql() {
        Database db = mock(Database.class);
        ReservationStore store = mock(ReservationStore.class);
        when(db.courses(202601L)).thenReturn(java);
        CourseBloom bloom = new CourseBloom(Clock.systemUTC(), 0, 0);
        CatalogMode mode = new CatalogMode();
        CourseBloomWarmup warmup = warmup(db, bloom, store, mode, "true");
        warmup.run(null);
        when(store.catalog(202601L)).thenThrow(new RuntimeException("redis down"));
        warmup.refreshIfExpired();
        assertThat(bloom.course(101, 202601)).isPresent();
        verify(db, times(1)).courses(202601L);
    }

    @Test void leaveFrozenFlipsHotPathBeforeSql() {
        Database db = mock(Database.class);
        ReservationStore store = mock(ReservationStore.class);
        when(db.courses(202601L)).thenReturn(java);
        CatalogMode mode = new CatalogMode();
        CourseBloom bloom = new CourseBloom();
        warmup(db, bloom, store, mode, "true").run(null);
        assertThat(mode.frozen()).isTrue();
        warmup(db, bloom, store, mode, "false").sync();
        assertThat(mode.frozen()).isFalse();
        verify(store).saveCatalogMode(202601L, false);
        verify(db).saveCatalogFrozen(202601L, false);
    }

    private CourseBloomWarmup warmup(Database db, CourseBloom bloom, ReservationStore store, CatalogMode mode,
                                     String frozen) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.catalog-frozen", frozen);
        return new CourseBloomWarmup(db, bloom, store, settings, mode, env);
    }
}
