package io.github.hanumanthraghavendra.schemaguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hanumanthraghavendra.schemaguard.api.config.ApiProperties;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.AnalysisStore;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.JdbcAnalysisStore;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.PersistenceConfig;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.StorageFullException;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.TestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Saved analyses end to end, on a real PostgreSQL database created for this test. */
@EnabledIfEnvironmentVariable(named = TestDatabase.SWITCH, matches = "1")
@SpringBootTest(properties = "schemaguard.persistence.max-analysis-bytes=60000")
@AutoConfigureMockMvc
class PermalinksTest {

    private static final String BEFORE = "CREATE TABLE users (id int PRIMARY KEY, email text);";
    private static final String AFTER = "CREATE TABLE users (id int PRIMARY KEY, email text NOT NULL); CREATE INDEX users_email_idx ON users (email);";

    private static TestDatabase database;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        database = TestDatabase.create("sg_api_test");
        registry.add("schemaguard.persistence.url", database::url);
        registry.add("schemaguard.persistence.username", database::user);
        registry.add("schemaguard.persistence.password", database::password);
    }

    @AfterAll
    static void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private HikariDataSource dataSource;

    @Autowired
    private AnalysisStore store;

    @Autowired
    private PersistenceConfig.RetentionJob retention;

    private record Reply(int status, String location, JsonNode body) {}

    private Reply post(String path, Object body) {
        return reply(mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)).exchange());
    }

    private Reply get(String path) {
        return reply(mvc.get().uri(path).exchange());
    }

    private Reply delete(String path, String token) {
        var request = mvc.delete().uri(path);
        if (token != null) {
            request = request.header("X-Delete-Token", token);
        }
        return reply(request.exchange());
    }

    private Reply reply(MvcTestResult result) {
        String text = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        return new Reply(result.getResponse().getStatus(), result.getResponse().getHeader("Location"), text.isEmpty() ? null : json.readTree(text));
    }

    private long rows() {
        return JdbcClient.create(dataSource).sql("SELECT count(*) FROM analysis").query(Long.class).single();
    }

    @Test
    void aSavedReportOpensExactlyAsItWasComputed() {
        Map<String, String> request = Map.of("before", BEFORE, "after", AFTER);
        JsonNode direct = post("/api/v1/report", request).body();

        Reply saved = post("/api/v1/analyses/report", request);
        assertThat(saved.status()).isEqualTo(201);
        String id = saved.body().get("id").asString();
        assertThat(id).matches("[0-9A-Za-z]{22}");
        assertThat(saved.location()).isEqualTo("/api/v1/analyses/" + id);
        assertThat(saved.body().get("path").asString()).isEqualTo(saved.location());
        assertThat(saved.body().get("kind").asString()).isEqualTo("report");
        assertThat(saved.body().get("deleteToken").asString()).hasSize(43);
        assertThat(saved.body().get("result")).isEqualTo(direct);
        Instant created = Instant.parse(saved.body().get("createdAt").asString());
        Instant expires = Instant.parse(saved.body().get("expiresAt").asString());
        assertThat(Duration.between(created, expires)).isEqualTo(Duration.ofDays(30));

        Reply opened = get(saved.location());
        assertThat(opened.status()).isEqualTo(200);
        assertThat(opened.body().get("id").asString()).isEqualTo(id);
        assertThat(opened.body().get("kind").asString()).isEqualTo("report");
        assertThat(opened.body().get("engineVersion").asString()).isNotBlank();
        assertThat(opened.body().get("request")).isEqualTo(json.valueToTree(request));
        assertThat(opened.body().get("result")).isEqualTo(direct);
        assertThat(opened.body().has("deleteToken")).isFalse();
        assertThat(Instant.parse(opened.body().get("createdAt").asString())).isEqualTo(created);
    }

    @Test
    void everyKindOfAnalysisCanBeSaved() {
        assertThat(post("/api/v1/analyses/diff", Map.of("before", BEFORE, "after", AFTER)).body().get("result").get("significant").asInt())
                .isPositive();
        Reply plan = post("/api/v1/analyses/plan", Map.of("before", BEFORE, "after", AFTER, "route", "SAFE"));
        assertThat(plan.status()).isEqualTo(201);
        assertThat(plan.body().get("result").get("sql").asString()).contains("CREATE INDEX CONCURRENTLY");
        Reply lint = post("/api/v1/analyses/lint", Map.of("schema", BEFORE, "migration", "CREATE INDEX i ON users (email);"));
        assertThat(lint.status()).isEqualTo(201);
        assertThat(get(lint.location()).body().get("kind").asString()).isEqualTo("lint");
    }

    @Test
    void onlyTheHolderOfTheTokenCanDelete() {
        Reply saved = post("/api/v1/analyses/diff", Map.of("before", BEFORE, "after", AFTER));
        String path = saved.location();
        assertThat(delete(path, "not-the-token").status()).isEqualTo(403);
        assertThat(delete(path, null).status()).isEqualTo(403);
        assertThat(get(path).status()).isEqualTo(200);

        assertThat(delete(path, saved.body().get("deleteToken").asString()).status()).isEqualTo(204);
        Reply gone = get(path);
        assertThat(gone.status()).isEqualTo(404);
        assertThat(gone.body().get("code").asString()).isEqualTo("SG-API-404");
        assertThat(delete(path, saved.body().get("deleteToken").asString()).status()).isEqualTo(404);
    }

    @Test
    void aRequestThatFailsIsNeverStored() {
        long before = rows();
        assertThat(post("/api/v1/analyses/report", Map.of("before", "CREATE TABLE (;", "after", "")).status()).isEqualTo(422);
        assertThat(post("/api/v1/analyses/report", Map.of("before", BEFORE)).status()).isEqualTo(400);
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void anAnalysisTooLargeToKeepIsRefused() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            big.append("CREATE TABLE t").append(i).append(" (id int PRIMARY KEY, name text NOT NULL);\n");
        }
        Reply reply = post("/api/v1/analyses/diff", Map.of("before", "", "after", big.toString()));
        assertThat(reply.status()).isEqualTo(413);
        assertThat(reply.body().get("detail").asString()).contains("at most 60000 can be saved");
        assertThat(post("/api/v1/diff", Map.of("before", "", "after", big.toString())).status()).isEqualTo(200);
    }

    @Test
    void expiredAnalysesAreInvisibleAndTheRetentionJobRemovesThem() {
        Instant past = Instant.now().minus(Duration.ofDays(40));
        String id = "Expired000000000000001";
        store.save(new AnalysisStore.Saved(id, "diff", "{}", "{}", "test", past, past.plus(Duration.ofDays(30))), "0".repeat(64));
        assertThat(store.find(id)).isEmpty();
        assertThat(get("/api/v1/analyses/" + id).status()).isEqualTo(404);
        assertThat(store.delete(id, "0".repeat(64))).isEqualTo(AnalysisStore.Deletion.NOT_FOUND);

        long before = rows();
        retention.deleteExpired();
        assertThat(rows()).isEqualTo(before - 1);
        assertThat(store.deleteExpired()).isZero();
    }

    @Test
    void savingStopsWhenTheRoomIsUsedUp() {
        ApiProperties.Persistence tiny =
                new ApiProperties.Persistence("x", null, null, Duration.ofDays(30), 1024, 2048, 1, Duration.ofSeconds(5));
        JdbcAnalysisStore full = new JdbcAnalysisStore(dataSource, tiny, Clock.fixed(Instant.now(), ZoneOffset.UTC));
        Instant now = Instant.now();
        AnalysisStore.Saved large =
                new AnalysisStore.Saved("FullFullFullFullFull01", "diff", "{\"a\":\"" + "x".repeat(3000) + "\"}", "{}", "t", now, now.plusSeconds(60));
        assertThatThrownBy(() -> full.save(large, "0".repeat(64))).isInstanceOf(StorageFullException.class).hasMessageContaining("no room");
        assertThat(full.find("FullFullFullFullFull01")).isEmpty();
    }

    @Test
    void theSchemaIsCreatedByTheMigrationAndNothingElse() {
        List<String> columns =
                JdbcClient.create(dataSource)
                        .sql("SELECT column_name FROM information_schema.columns WHERE table_name = 'analysis' ORDER BY ordinal_position")
                        .query(String.class)
                        .list();
        assertThat(columns)
                .containsExactly(
                        "id", "kind", "request", "result", "engine_version", "size_bytes", "delete_token_hash", "created_at", "expires_at");
        assertThat(JdbcClient.create(dataSource).sql("SELECT max(version) FROM flyway_schema_history").query(String.class).single())
                .isEqualTo("1");
    }
}
