package dev.demo.selection.web;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableDubbo
@EnableConfigurationProperties(WebSettings.class)
public class SelectionWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(SelectionWebApplication.class, args);
    }
}
