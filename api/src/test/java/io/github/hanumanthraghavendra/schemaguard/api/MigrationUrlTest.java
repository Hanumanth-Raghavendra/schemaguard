package io.github.hanumanthraghavendra.schemaguard.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.api.persistence.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Migrations connect where {@code migration-url} says, not where the requests do (D-118): pointed
 * at a port where nothing listens, they fail, the request gets a 503, and the requests' database
 * is left untouched.
 */
@EnabledIfEnvironmentVariable(named = TestDatabase.SWITCH, matches = "1")
@SpringBootTest
@AutoConfigureMockMvc
class MigrationUrlTest {

    private static TestDatabase database;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        database = TestDatabase.create("sg_migration_url_test");
        registry.add("schemaguard.persistence.url", database::url);
        registry.add("schemaguard.persistence.username", database::user);
        registry.add("schemaguard.persistence.password", database::password);
        registry.add("schemaguard.persistence.migration-url", () -> "jdbc:postgresql://127.0.0.1:1/nowhere?connectTimeout=2");
    }

    @AfterAll
    static void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @Test
    void migrationsGoToTheMigrationUrl() throws Exception {
        assertThat(mvc.post()
                        .uri("/api/v1/analyses/diff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"before\":\"CREATE TABLE t (a int);\",\"after\":\"CREATE TABLE t (a int, b int);\"}"))
                .hasStatus(503);
        try (Connection connection = DriverManager.getConnection(database.url(), database.user(), database.password());
                ResultSet tables = connection.getMetaData().getTables(null, "public", "%", new String[] {"TABLE"})) {
            assertThat(tables.next()).as("no table was made in the requests' database").isFalse();
        }
    }
}
