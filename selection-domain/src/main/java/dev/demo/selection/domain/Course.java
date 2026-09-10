package dev.demo.selection.domain;

public record Course(long id, long termId, String title, int remaining, int credits,
                     int weekday, int startSlot, int endSlot, String exclusionGroup,
                     Long prerequisiteId) {
}
