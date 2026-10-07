package com.fitbook;

import org.junit.jupiter.api.Test;

@FitbookIntegrationTest
class FitbookApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the layered app (Controller -> Service -> Repository -> JDBC/SQLite)
        // wires up and schema.sql/seed.sql load cleanly.
    }
}
