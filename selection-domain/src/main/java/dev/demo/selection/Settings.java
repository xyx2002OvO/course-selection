package dev.demo.selection;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record Settings(boolean apiEnabled, boolean workerEnabled, boolean bootstrap,
                       String demoPassword, long term, int reservationSeconds,
                       int dedupSeconds, int statusSeconds, int batchSize,
                       long studentFrom, long studentTo,
                       int submitPerStudent, int submitGlobal,
                       int queryPerStudent, int queryGlobal,
                       int hotspotPerCourse, int statusTtlJitterSeconds,
                       boolean catalogFrozen, boolean syncBaseline) {
}
