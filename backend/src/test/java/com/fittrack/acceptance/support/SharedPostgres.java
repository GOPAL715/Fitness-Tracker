package com.fittrack.acceptance.support;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A single PostgreSQL container for the entire test JVM.
 *
 * <p>Held in a holder class so it is created exactly once and never stopped by the
 * Testcontainers JUnit extension, which would otherwise tear it down when the first test class
 * completed while later Spring contexts were still using it.
 */
final class SharedPostgres {

    static final PostgreSQLContainer<?> INSTANCE = start();

    private SharedPostgres() {}

    private static PostgreSQLContainer<?> start() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        return container;
    }
}
