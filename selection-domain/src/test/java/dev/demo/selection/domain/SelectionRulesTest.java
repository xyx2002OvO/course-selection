package dev.demo.selection.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SelectionRulesTest {
    private final SelectionRules rules = new SelectionRules();
    private final Course enrolled = course(101, 1, 1, 3, "language", null, 3);

    @Test void acceptsAdjacentSlots() {
        assertThat(rules.rejection(course(102,1,3,5,null,null,3), List.of(enrolled),8,Set.of())).isEmpty();
    }
    @Test void rejectsOverlappingSlots() {
        assertThat(rules.rejection(course(102,1,2,4,null,null,3), List.of(enrolled),8,Set.of())).isEqualTo("TIME_CONFLICT");
    }
    @Test void rejectsExclusiveCoursesOnDifferentDays() {
        assertThat(rules.rejection(course(102,2,1,3,"language",null,3), List.of(enrolled),8,Set.of())).isEqualTo("MUTUALLY_EXCLUSIVE");
    }
    @Test void rejectsDuplicateEnrollment() {
        assertThat(rules.rejection(enrolled,List.of(enrolled),8,Set.of())).isEqualTo("ALREADY_ENROLLED");
    }
    @Test void checksPrerequisite() {
        Course target = course(103,3,1,3,null,9001L,4);
        assertThat(rules.rejection(target,List.of(),8,Set.of())).isEqualTo("PREREQUISITE_NOT_MET");
        assertThat(rules.rejection(target,List.of(),8,Set.of(9001L))).isEmpty();
    }
    @Test void checksCreditLimitIncludingTarget() {
        assertThat(rules.rejection(course(105,5,1,3,null,null,6),List.of(enrolled),8,Set.of())).isEqualTo("CREDIT_LIMIT");
        assertThat(rules.rejection(course(105,5,1,3,null,null,5),List.of(enrolled),8,Set.of())).isEmpty();
    }

    private Course course(long id, int day, int start, int end, String group, Long prerequisite, int credits) {
        return new Course(id,202601,"Test",2,credits,day,start,end,group,prerequisite);
    }
}
