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
 * The Hikari {@code DataSource} is auto-configured from the {@code spring.datasource.*}
 * block in application.yml (via the runtime-only {@code spring-boot-jdbc} + PostgreSQL
 * driver on the classpath). That DataSource is what lets Spring Data JPA stand up the
 * {@code EntityManagerFactory} and the JPA repositories the service depends on.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
