package com.uptimecrew.multistate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * Spring Boot entry point for the multistate application.
 *
 * Component-scans everything under com.uptimecrew.multistate.* so the
 * Week 1 service, its strategies, and the Day 4 repositories are all picked
 * up without explicit configuration.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
