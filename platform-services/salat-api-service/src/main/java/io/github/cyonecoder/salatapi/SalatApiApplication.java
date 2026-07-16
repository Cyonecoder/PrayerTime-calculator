package io.github.cyonecoder.salatapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The single Spring Boot application: one process, one HTTP port, one scheduled
 * worker, one local JSON data directory, no database.
 *
 * <p>Responsibilities are separated by package ({@code secapproach}, {@code habous},
 * {@code archive}, {@code verification}, {@code api}), never by deployable module.
 * Scheduling is activated by {@code SchedulerActivation} (default on).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SalatApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SalatApiApplication.class, args);
    }
}
