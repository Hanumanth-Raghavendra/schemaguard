package io.github.hanumanthraghavendra.schemaguard.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.api.persistence.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Projects and schema versions over HTTP, on a real PostgreSQL database created for this test. */
@EnabledIfEnvironmentVariable(named = TestDatabase.SWITCH, matches = "1")
@SpringBootTest
@AutoConfigureMockMvc
class ProjectsTest {

    private static final String V1 = """
            CREATE TABLE customers (id integer PRIMARY KEY, email text NOT NULL, name text);
            CREATE TABLE orders (id integer PRIMARY KEY, customer_id integer NOT NULL, total numeric(10, 2));
            """;
    private static final String V2 = """
            CREATE TABLE customers (id integer PRIMARY KEY, email text NOT NULL, name text);
            CREATE TABLE orders (id bigint PRIMARY KEY, customer_id integer NOT NULL REFERENCES customers (id), total numeric(12, 2));
            """;

    private static TestDatabase database;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        database = TestDatabase.create("sg_api_projects");
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

    private record Reply(int status, JsonNode body, MvcTestResult raw) {
        String text(String field) {
            return body.get(field).asString();
        }
    }

    private Reply send(MockMvcRequestBuilder request) {
        MvcTestResult result = request.exchange();
        String text = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        return new Reply(result.getResponse().getStatus(), text.isEmpty() ? null : json.readTree(text), result);
    }

    /** A new account's access token. */
    private String signUp() {
        Map<String, String> credentials = Map.of("email", "user-" + UUID.randomUUID() + "@example.com", "password", "correct horse battery staple");
        Reply session = send(mvc.post().uri("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(credentials)));
        assertThat(session.status()).isEqualTo(201);
        return session.text("accessToken");
    }

    private Reply call(String token, MockMvcRequestBuilder request) {
        return send(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private Reply create(String token, String name) {
        return call(token, mvc.post().uri("/api/v1/projects").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name))));
    }

    private Reply addVersion(String token, String project, String sql, String label) {
        Map<String, Object> body = new HashMap<>();
        body.put("sql", sql);
        body.put("label", label);
        return call(token, mvc.post().uri("/api/v1/projects/{id}/versions", project).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    @Test
    void aProjectIsCreatedListedRenamedAndDeleted() {
        String token = signUp();
        Reply created = create(token, "  shop  ");
        assertThat(created.status()).isEqualTo(201);
        String id = created.text("id");
        assertThat(created.text("name")).isEqualTo("shop");
        assertThat(created.body().get("versions").isEmpty()).isTrue();
        assertThat(created.raw().getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/v1/projects/" + id);

        Reply list = call(token, mvc.get().uri("/api/v1/projects"));
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().size()).isEqualTo(1);
        assertThat(list.body().get(0).get("versionCount").asInt()).isZero();
        assertThat(list.body().get(0).get("latest").isNull()).isTrue();

        Reply renamed = call(token, mvc.patch().uri("/api/v1/projects/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Shop backend\"}"));
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.text("name")).isEqualTo("Shop backend");
        // Renaming to its own name in another case is fine; another project may not take it in any case.
        assertThat(call(token, mvc.patch().uri("/api/v1/projects/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"shop BACKEND\"}")).status()).isEqualTo(200);
        Reply taken = create(token, "SHOP backend");
        assertThat(taken.status()).isEqualTo(409);
        assertThat(taken.text("detail")).isEqualTo("This account already has a project named \"SHOP backend\".");

        assertThat(call(token, mvc.delete().uri("/api/v1/projects/{id}", id)).status()).isEqualTo(204);
        assertThat(call(token, mvc.get().uri("/api/v1/projects/{id}", id)).status()).isEqualTo(404);
        assertThat(call(token, mvc.delete().uri("/api/v1/projects/{id}", id)).status()).isEqualTo(404);
    }

    @Test
    void namesMustFitOnOneLine() {
        String token = signUp();
        for (String name : List.of("", "   ", "x".repeat(81), "two\nlines")) {
            Reply refused = create(token, name);
            assertThat(refused.status()).as(name).isEqualTo(422);
            assertThat(refused.text("detail")).isEqualTo("Name the project in 1 to 80 characters, on one line.");
        }
        assertThat(create(token, "x".repeat(80)).status()).isEqualTo(201);
        assertThat(create(token, "схема 🐘").status()).isEqualTo(201);
    }

    @Test
    void eachVersionStoresTheRiskOfTheChangeFromTheOneBefore() {
        String token = signUp();
        String project = create(token, "shop").text("id");

        Reply first = addVersion(token, project, V1, "  Initial schema  ");
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.body().get("number").asInt()).isEqualTo(1);
        assertThat(first.text("label")).isEqualTo("Initial schema");
        assertThat(first.body().get("risk").isNull()).isTrue();
        assertThat(first.body().get("sizeBytes").asInt()).isEqualTo(V1.getBytes(StandardCharsets.UTF_8).length);
        assertThat(first.raw().getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/v1/projects/" + project + "/versions/1");

        Reply second = addVersion(token, project, V2, "");
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().get("number").asInt()).isEqualTo(2);
        assertThat(second.body().get("label").isNull()).isTrue();
        JsonNode risk = second.body().get("risk");
        assertThat(risk.get("score").asInt()).isPositive();
        assertThat(risk.get("level").asString()).isIn("LOW", "MEDIUM", "HIGH", "CRITICAL");
        assertThat(risk.get("changes").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(risk.get("findings").asInt()).isPositive();
        assertThat(risk.get("reasons").isEmpty()).isFalse();

        Reply detail = call(token, mvc.get().uri("/api/v1/projects/{id}", project));
        assertThat(detail.body().get("versions").get(0).get("number").asInt()).isEqualTo(2);
        assertThat(detail.body().get("versions").get(1).get("number").asInt()).isEqualTo(1);

        Reply listed = call(token, mvc.get().uri("/api/v1/projects"));
        assertThat(listed.body().get(0).get("versionCount").asInt()).isEqualTo(2);
        assertThat(listed.body().get(0).get("latest").get("number").asInt()).isEqualTo(2);

        Reply opened = call(token, mvc.get().uri("/api/v1/projects/{id}/versions/2", project));
        assertThat(opened.status()).isEqualTo(200);
        assertThat(opened.text("sql")).isEqualTo(V2);
        assertThat(opened.text("previousSql")).isEqualTo(V1);
        assertThat(opened.body().get("risk").get("score").asInt()).isEqualTo(risk.get("score").asInt());
        Reply openedFirst = call(token, mvc.get().uri("/api/v1/projects/{id}/versions/1", project));
        assertThat(openedFirst.body().get("previousSql").isNull()).isTrue();
        assertThat(call(token, mvc.get().uri("/api/v1/projects/{id}/versions/9", project)).text("detail"))
                .isEqualTo("This project has no version 9.");
    }

    @Test
    void aVersionThatChangesNothingOrCannotBeAnalysedIsRefused() {
        String token = signUp();
        String project = create(token, "shop").text("id");

        Reply broken = addVersion(token, project, "CREATE TABLE (;", null);
        assertThat(broken.status()).isEqualTo(422);
        assertThat(broken.text("detail")).startsWith("The new schema has");
        assertThat(broken.body().get("diagnostics").get(0).get("input").asString()).isEqualTo("new");

        assertThat(addVersion(token, project, V1, null).status()).isEqualTo(201);
        // Respelled, not changed: other whitespace, case and a comment are the same schema.
        Reply same = addVersion(token, project, "-- the same\n" + V1.toUpperCase().replace("  ", " "), null);
        assertThat(same.status()).isEqualTo(409);
        assertThat(same.text("detail")).isEqualTo("This schema is the same as version 1, so there is nothing new to save.");

        Reply label = addVersion(token, project, V2, "x".repeat(121));
        assertThat(label.status()).isEqualTo(422);
        assertThat(label.text("detail")).isEqualTo("Keep the label to one line of at most 120 characters.");
        assertThat(call(token, mvc.get().uri("/api/v1/projects/{id}", project)).body().get("versions").size()).isEqualTo(1);
    }

    @Test
    void onlyTheLatestVersionCanBeDeleted() {
        String token = signUp();
        String project = create(token, "shop").text("id");
        addVersion(token, project, V1, null);
        addVersion(token, project, V2, null);

        Reply earlier = call(token, mvc.delete().uri("/api/v1/projects/{id}/versions/1", project));
        assertThat(earlier.status()).isEqualTo(409);
        assertThat(earlier.text("detail")).startsWith("Only the latest version, 2, can be deleted");
        assertThat(call(token, mvc.delete().uri("/api/v1/projects/{id}/versions/3", project)).status()).isEqualTo(404);

        assertThat(call(token, mvc.delete().uri("/api/v1/projects/{id}/versions/2", project)).status()).isEqualTo(204);
        Reply again = addVersion(token, project, V2, "Second try");
        assertThat(again.body().get("number").asInt()).isEqualTo(2);
        assertThat(call(token, mvc.delete().uri("/api/v1/projects/{id}/versions/2", project)).status()).isEqualTo(204);
        assertThat(call(token, mvc.delete().uri("/api/v1/projects/{id}/versions/1", project)).status()).isEqualTo(204);
        assertThat(call(token, mvc.get().uri("/api/v1/projects/{id}", project)).body().get("versions").isEmpty()).isTrue();
    }

    @Test
    void anotherAccountsProjectLooksLikeNoProjectAtAll() {
        String owner = signUp();
        String project = create(owner, "private").text("id");
        addVersion(owner, project, V1, null);
        String stranger = signUp();

        assertThat(call(stranger, mvc.get().uri("/api/v1/projects")).body().isEmpty()).isTrue();
        List<Reply> attempts = List.of(
                call(stranger, mvc.get().uri("/api/v1/projects/{id}", project)),
                call(stranger, mvc.get().uri("/api/v1/projects/{id}/versions/1", project)),
                call(stranger, mvc.patch().uri("/api/v1/projects/{id}", project).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mine now\"}")),
                addVersion(stranger, project, V2, null),
                call(stranger, mvc.delete().uri("/api/v1/projects/{id}/versions/1", project)),
                call(stranger, mvc.delete().uri("/api/v1/projects/{id}", project)));
        for (Reply attempt : attempts) {
            assertThat(attempt.status()).isEqualTo(404);
            assertThat(attempt.text("detail")).isEqualTo("There is no such project in this account.");
        }
        Reply missing = call(stranger, mvc.get().uri("/api/v1/projects/{id}", UUID.randomUUID()));
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.text("detail")).isEqualTo(attempts.get(0).text("detail"));
        assertThat(call(owner, mvc.get().uri("/api/v1/projects/{id}", project)).text("name")).isEqualTo("private");
    }

    @Test
    void projectsNeedAnAccessToken() {
        Reply anonymous = send(mvc.get().uri("/api/v1/projects"));
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.raw().getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(send(mvc.post().uri("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .status()).isEqualTo(401);
        assertThat(call(signUp(), mvc.get().uri("/api/v1/projects/not-a-uuid")).status()).isEqualTo(400);
    }

    @Test
    void deletingTheAccountDeletesItsProjectsAndVersions() {
        String token = signUp();
        String project = create(token, "shop").text("id");
        addVersion(token, project, V1, null);
        Reply gone = call(token, mvc.delete().uri("/api/v1/account").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"correct horse battery staple\"}"));
        assertThat(gone.status()).isEqualTo(204);

        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(database.url(), database.user(), database.password()));
        UUID id = UUID.fromString(project);
        assertThat(jdbc.sql("SELECT count(*) FROM project WHERE id = :id").param("id", id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM schema_version WHERE project_id = :id").param("id", id).query(Long.class).single())
                .isZero();
        // Its still-unexpired access token cannot create anything for an account that is gone.
        assertThat(create(token, "ghost").status()).isEqualTo(401);
    }
}
