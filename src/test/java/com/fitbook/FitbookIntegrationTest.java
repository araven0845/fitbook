package com.fitbook;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Full application context over a real SQLite file. Every integration test uses this same
 * annotation, so they share one cached context (schema.sql + seed.sql load once) and each test
 * creates its own slots instead of relying on shared mutable data.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:./fitbook-test.db")
public @interface FitbookIntegrationTest {
}
