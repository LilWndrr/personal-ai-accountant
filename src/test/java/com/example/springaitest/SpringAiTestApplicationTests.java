package com.example.springaitest;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts the whole app against a real, empty Postgres and Redis: proves Flyway's
 * migrations apply cleanly and Hibernate's validate agrees with the entities.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class SpringAiTestApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Container
    @ServiceConnection
    static RedisContainer redis = new RedisContainer("redis:7");

    @Test
    void contextLoads() {
    }
}