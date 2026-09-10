package dev.demo.selection;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableDubbo
@ConditionalOnProperty(name = "dubbo.enabled", havingValue = "true", matchIfMissing = true)
public class DubboConfiguration {
}
