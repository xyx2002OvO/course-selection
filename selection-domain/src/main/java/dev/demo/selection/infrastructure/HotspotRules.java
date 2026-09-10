package dev.demo.selection.infrastructure;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import dev.demo.selection.Settings;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.bootstrap", havingValue = "false", matchIfMissing = true)
public class HotspotRules implements ApplicationRunner {
    public static final String SELECT_COURSE = "select-course";
    private final Settings settings;

    public HotspotRules(Settings settings) {
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        ParamFlowRule rule = new ParamFlowRule(SELECT_COURSE)
                .setParamIdx(0)
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(settings.hotspotPerCourse());
        ParamFlowRuleManager.loadRules(List.of(rule));
    }
}
