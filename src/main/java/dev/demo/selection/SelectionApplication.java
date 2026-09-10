package dev.demo.selection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(Settings.class)
public class SelectionApplication {
    public static void main(String[] args) {
        SpringApplication.run(SelectionApplication.class, args);
    }
}
