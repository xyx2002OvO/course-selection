package dev.demo.selection.domain;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SelectionRules {
    public String rejection(Course target, List<Course> enrolled, int maxCredits, Set<Long> passed) {
        if (enrolled.stream().anyMatch(c -> c.id() == target.id())) return "ALREADY_ENROLLED";
        if (target.prerequisiteId() != null && !passed.contains(target.prerequisiteId())) {
            return "PREREQUISITE_NOT_MET";
        }
        for (Course course : enrolled) {
            if (course.weekday() == target.weekday()
                    && course.startSlot() < target.endSlot() && target.startSlot() < course.endSlot()) {
                return "TIME_CONFLICT";
            }
            if (target.exclusionGroup() != null && target.exclusionGroup().equals(course.exclusionGroup())) {
                return "MUTUALLY_EXCLUSIVE";
            }
        }
        if (enrolled.stream().mapToInt(Course::credits).sum() + target.credits() > maxCredits) {
            return "CREDIT_LIMIT";
        }
        return "";
    }
}
