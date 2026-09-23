package com.fitbook;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:./fitbook-test.db")
class FitbookApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the layered app (Controller -> Service -> Repository -> JDBC/SQLite)
        // wires up and schema.sql/seed.sql load cleanly.
    }
}
