package com.uptimecrew.multistate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the multistate application.
 *
 * Component-scans everything under com.uptimecrew.multistate.* so the
 * Week 1 service, its strategies, and the Day 4 repositories are all picked
 * up without explicit configuration.
 *
 * DataSourceAutoConfiguration is excluded via the {@code spring.autoconfigure.exclude}
 * property in application.yml. As of Spring Boot 4 that auto-configuration lives in the
 * runtime-only {@code spring-boot-jdbc} module, so it can't be referenced from a
 * compile-time annotation here.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
