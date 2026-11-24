package io.github.hanumanthraghavendra.schemaguard.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.api.persistence.TestDatabase;
import java.nio.charset.StandardCharsets;
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

/** API keys over HTTP, on a real PostgreSQL database created for this test. */
@EnabledIfEnvironmentVariable(named = TestDatabase.SWITCH, matches = "1")
@SpringBootTest
@AutoConfigureMockMvc
class ApiKeysTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String V1 = "CREATE TABLE customers (id integer PRIMARY KEY, email text NOT NULL);";
    private static final String V2 = "CREATE TABLE customers (id integer PRIMARY KEY, email text NOT NULL, name text);";
    private static final String KEY_ONLY = "An API key may only read projects and save versions; this needs a login.";
    private static final String UNKNOWN_KEY = "The API key is unknown or was deleted.";

    private static TestDatabase database;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        database = TestDatabase.create("sg_api_keys");
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
        Map<String, String> credentials = Map.of("email", "user-" + UUID.randomUUID() + "@example.com", "password", PASSWORD);
        Reply session = send(mvc.post().uri("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(credentials)));
        assertThat(session.status()).isEqualTo(201);
        return session.text("accessToken");
    }

    /** A request sent with an access token or an API key: both are bearer tokens. */
    private Reply call(String bearer, MockMvcRequestBuilder request) {
        return send(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));
    }

    private Reply makeKey(String token, String name) {
        return call(token, mvc.post().uri("/api/v1/account/keys").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name))));
    }

    private Reply keys(String token) {
        return call(token, mvc.get().uri("/api/v1/account/keys"));
    }

    private String project(String token, String name) {
        Reply created = call(token, mvc.post().uri("/api/v1/projects").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name))));
        assertThat(created.status()).isEqualTo(201);
        return created.text("id");
    }

    private Reply addVersion(String bearer, String project, String sql) {
        return call(bearer, mvc.post().uri("/api/v1/projects/{id}/versions", project).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("sql", sql))));
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(new DriverManagerDataSource(database.url(), database.user(), database.password()));
    }

    @Test
    void aKeyIsShownOnceThenListedByItsHint() {
        String token = signUp();
        Reply made = makeKey(token, "  GitHub Actions  ");
        assertThat(made.status()).isEqualTo(201);
        String key = made.text("key");
        assertThat(key).matches("sg_[A-Za-z0-9_-]{43}");
        assertThat(made.text("name")).isEqualTo("GitHub Actions");
        assertThat(made.text("hint")).isEqualTo(key.substring(0, 7));
        assertThat(made.raw().getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/v1/account/keys/" + made.text("id"));

        Reply list = keys(token);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().size()).isEqualTo(1);
        JsonNode listed = list.body().get(0);
        assertThat(listed.get("id").asString()).isEqualTo(made.text("id"));
        assertThat(listed.get("hint").asString()).isEqualTo(key.substring(0, 7));
        assertThat(listed.has("key")).isFalse();
        assertThat(listed.get("lastUsedAt").isNull()).isTrue();

        // Only a hash is stored: the database cannot give the key back.
        String stored = jdbc().sql("SELECT key_hash FROM api_key WHERE id = :id").param("id", UUID.fromString(made.text("id")))
                .query(String.class).single();
        assertThat(stored).hasSize(64).doesNotContain(key.substring(3));

        // Newest first.
        makeKey(token, "laptop");
        assertThat(keys(token).body().get(0).get("name").asString()).isEqualTo("laptop");
    }

    @Test
    void keyNamesMustFitOnOneLine() {
        String token = signUp();
        for (String name : List.of("", "   ", "x".repeat(81), "two\nlines")) {
            Reply refused = makeKey(token, name);
            assertThat(refused.status()).isEqualTo(422);
            assertThat(refused.text("detail")).isEqualTo("Name the key in 1 to 80 characters, on one line, such as \"GitHub Actions\".");
        }
        assertThat(call(token, mvc.post().uri("/api/v1/account/keys").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .status()).isEqualTo(400);
        assertThat(keys(token).body().isEmpty()).isTrue();
    }

    @Test
    void aKeyMayReadProjectsAndSaveVersions() {
        String token = signUp();
        String project = project(token, "shop");
        String key = makeKey(token, "ci").text("key");

        Reply list = call(key, mvc.get().uri("/api/v1/projects"));
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().get(0).get("id").asString()).isEqualTo(project);

        Reply first = addVersion(key, project, V1);
        assertThat(first.status()).isEqualTo(201);
        Reply second = addVersion(key, project, V2);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().get("number").asInt()).isEqualTo(2);
        assertThat(call(key, mvc.get().uri("/api/v1/projects/{id}", project)).body().get("versions").size()).isEqualTo(2);
        assertThat(call(key, mvc.get().uri("/api/v1/projects/{id}/versions/2", project)).status()).isEqualTo(200);
        // The same schema again is refused, as with a login: saving on every push keeps only real changes.
        assertThat(addVersion(key, project, V2).status()).isEqualTo(409);

        // Use is noted, at most once a minute.
        JsonNode listed = keys(token).body().get(0);
        assertThat(listed.get("lastUsedAt").isNull()).isFalse();
        String lastUsed = listed.get("lastUsedAt").asString();
        call(key, mvc.get().uri("/api/v1/projects"));
        assertThat(keys(token).body().get(0).get("lastUsedAt").asString()).isEqualTo(lastUsed);
    }

    @Test
    void aKeyMayNotManageTheAccountItsKeysOrItsProjects() {
        String token = signUp();
        String project = project(token, "shop");
        addVersion(token, project, V1);
        Reply made = makeKey(token, "ci");
        String key = made.text("key");
        String password = "{\"password\":\"" + PASSWORD + "\"}";

        List<MockMvcRequestBuilder> refused = List.of(
                mvc.get().uri("/api/v1/account"),
                mvc.delete().uri("/api/v1/account").contentType(MediaType.APPLICATION_JSON).content(password),
                mvc.get().uri("/api/v1/account/keys"),
                mvc.post().uri("/api/v1/account/keys").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"more\"}"),
                mvc.delete().uri("/api/v1/account/keys/{id}", made.text("id")),
                mvc.post().uri("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"other\"}"),
                mvc.patch().uri("/api/v1/projects/{id}", project).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"),
                mvc.delete().uri("/api/v1/projects/{id}", project),
                mvc.delete().uri("/api/v1/projects/{id}/versions/1", project));
        for (MockMvcRequestBuilder request : refused) {
            Reply reply = call(key, request);
            assertThat(reply.status()).isEqualTo(403);
            assertThat(reply.text("code")).isEqualTo("SG-API-403");
            assertThat(reply.text("detail")).isEqualTo(KEY_ONLY);
            assertThat(reply.raw().getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("insufficient_scope");
        }
        // Nothing changed.
        assertThat(keys(token).body().size()).isEqualTo(1);
        assertThat(call(token, mvc.get().uri("/api/v1/projects/{id}", project)).body().get("versions").size()).isEqualTo(1);
        assertThat(call(token, mvc.get().uri("/api/v1/account")).status()).isEqualTo(200);
    }

    @Test
    void aDeletedOrUnknownKeyIsRefused() {
        String token = signUp();
        Reply made = makeKey(token, "ci");
        String key = made.text("key");
        assertThat(call(key, mvc.get().uri("/api/v1/projects")).status()).isEqualTo(200);

        assertThat(call(token, mvc.delete().uri("/api/v1/account/keys/{id}", made.text("id"))).status()).isEqualTo(204);
        Reply gone = call(key, mvc.get().uri("/api/v1/projects"));
        assertThat(gone.status()).isEqualTo(401);
        assertThat(gone.text("detail")).isEqualTo(UNKNOWN_KEY);
        assertThat(gone.raw().getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer").contains("invalid_token");
        Reply again = call(token, mvc.delete().uri("/api/v1/account/keys/{id}", made.text("id")));
        assertThat(again.status()).isEqualTo(404);
        assertThat(again.text("detail")).isEqualTo("This account has no such API key.");

        for (String unknown : List.of("sg_" + "A".repeat(43), "sg_short", key + "x")) {
            Reply reply = call(unknown, mvc.get().uri("/api/v1/projects"));
            assertThat(reply.status()).isEqualTo(401);
            assertThat(reply.text("detail")).isEqualTo(UNKNOWN_KEY);
        }
        // An access token that is not one is still told so in its own words.
        assertThat(call("not-a-token", mvc.get().uri("/api/v1/projects")).text("detail"))
                .isEqualTo("The access token has expired or is not valid. Renew the session or log in again.");
        assertThat(send(mvc.get().uri("/api/v1/projects")).text("detail"))
                .isEqualTo("Log in to use this endpoint: send an access token or an API key as Authorization: Bearer.");
    }

    @Test
    void aKeyMayAlsoUseTheOpenEndpoints() {
        String key = makeKey(signUp(), "ci").text("key");
        Reply diff = call(key, mvc.post().uri("/api/v1/diff").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("before", V1, "after", V2))));
        assertThat(diff.status()).isEqualTo(200);
        assertThat(diff.body().get("significant").asInt()).isEqualTo(1);
    }

    @Test
    void anAccountHasAtMostTenKeys() {
        String token = signUp();
        String last = null;
        for (int i = 1; i <= 10; i++) {
            Reply made = makeKey(token, "key " + i);
            assertThat(made.status()).isEqualTo(201);
            last = made.text("id");
        }
        Reply full = makeKey(token, "eleven");
        assertThat(full.status()).isEqualTo(422);
        assertThat(full.text("detail")).isEqualTo("This account has 10 API keys, the most it can have. Delete one to make room.");

        assertThat(call(token, mvc.delete().uri("/api/v1/account/keys/{id}", last)).status()).isEqualTo(204);
        assertThat(makeKey(token, "eleven").status()).isEqualTo(201);
    }

    @Test
    void anotherAccountsKeyLooksLikeNoKeyAtAll() {
        String alice = signUp();
        String bob = signUp();
        Reply alicesKey = makeKey(alice, "ci");
        String bobsProject = project(bob, "bob's");

        assertThat(call(bob, mvc.delete().uri("/api/v1/account/keys/{id}", alicesKey.text("id"))).status()).isEqualTo(404);
        assertThat(keys(bob).body().isEmpty()).isTrue();
        String key = alicesKey.text("key");
        assertThat(call(key, mvc.get().uri("/api/v1/projects")).body().isEmpty()).isTrue();
        assertThat(call(key, mvc.get().uri("/api/v1/projects/{id}", bobsProject)).status()).isEqualTo(404);
        assertThat(addVersion(key, bobsProject, V1).status()).isEqualTo(404);
        assertThat(call(bob, mvc.get().uri("/api/v1/projects/{id}", bobsProject)).body().get("versions").isEmpty()).isTrue();
    }

    @Test
    void deletingTheAccountDeletesItsKeys() {
        String token = signUp();
        Reply made = makeKey(token, "ci");
        String key = made.text("key");
        Reply gone = call(token, mvc.delete().uri("/api/v1/account").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + PASSWORD + "\"}"));
        assertThat(gone.status()).isEqualTo(204);

        assertThat(jdbc().sql("SELECT count(*) FROM api_key WHERE id = :id").param("id", UUID.fromString(made.text("id")))
                .query(Long.class).single()).isZero();
        assertThat(call(key, mvc.get().uri("/api/v1/projects")).status()).isEqualTo(401);
        // Its still-unexpired access token cannot make a key for an account that is gone.
        assertThat(makeKey(token, "ghost").status()).isEqualTo(401);
    }
}
