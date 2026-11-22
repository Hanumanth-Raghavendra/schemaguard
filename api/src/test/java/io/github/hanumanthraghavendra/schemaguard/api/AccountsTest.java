package io.github.hanumanthraghavendra.schemaguard.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.api.persistence.TestDatabase;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Accounts over HTTP as a browser uses them, on a real PostgreSQL database created for this test. */
@EnabledIfEnvironmentVariable(named = TestDatabase.SWITCH, matches = "1")
@SpringBootTest(properties = "schemaguard.cors.allowed-origins=https://schemaguard.example")
@AutoConfigureMockMvc
class AccountsTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String COOKIE = "__Secure-sg-refresh";

    private static TestDatabase database;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        database = TestDatabase.create("sg_api_accounts");
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

    /** An answer: status, body (null when empty), the refresh cookie it sets (null if none) and headers. */
    private record Reply(int status, JsonNode body, String setCookie, MvcTestResult raw) {
        String cookieValue() {
            return setCookie.substring(COOKIE.length() + 1, setCookie.indexOf(';'));
        }

        String text(String field) {
            return body.get(field).asString();
        }
    }

    private Reply send(MockMvcRequestBuilder request) {
        MvcTestResult result = request.exchange();
        String text = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        String cookie = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(COOKIE + "="))
                .findFirst()
                .orElse(null);
        return new Reply(result.getResponse().getStatus(), text.isEmpty() ? null : json.readTree(text), cookie, result);
    }

    private Reply post(String path, Object body) {
        return send(mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private Reply signUp(String email) {
        return post("/api/v1/auth/signup", Map.of("email", email, "password", PASSWORD));
    }

    private Reply refresh(String refreshToken) {
        return send(mvc.post().uri("/api/v1/auth/refresh").cookie(new Cookie(COOKIE, refreshToken)));
    }

    private Reply account(String accessToken) {
        return send(mvc.get().uri("/api/v1/account").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private static String email() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void signingUpLogsInWithAnAccessTokenAndARefreshCookie() {
        String email = email();
        Reply reply = signUp(email.toUpperCase());

        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.text("tokenType")).isEqualTo("Bearer");
        assertThat(reply.body().get("expiresIn").asLong()).isEqualTo(900);
        assertThat(reply.text("accessToken").split("\\.")).hasSize(3);
        assertThat(reply.body().get("account").get("email").asString()).isEqualTo(email);
        assertThat(reply.body().get("account").propertyNames()).containsExactlyInAnyOrder("id", "email", "createdAt");
        assertThat(reply.body().toString()).doesNotContain("argon2").doesNotContain(reply.cookieValue());

        assertThat(reply.setCookie()).matches(COOKIE + "=[A-Za-z0-9_-]{43}; .*");
        assertThat(reply.setCookie().split("; ")).contains(
                "Path=/api/v1/auth", "Max-Age=2592000", "Secure", "HttpOnly", "SameSite=Strict");
        assertThat(reply.setCookie()).doesNotContain("Domain");
        assertThat(reply.raw().getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void theAccountEndpointNeedsAValidAccessToken() {
        String email = email();
        Reply session = signUp(email);

        Reply mine = account(session.text("accessToken"));
        assertThat(mine.status()).isEqualTo(200);
        assertThat(mine.text("email")).isEqualTo(email);
        assertThat(mine.text("id")).isEqualTo(session.body().get("account").get("id").asString());

        Reply anonymous = send(mvc.get().uri("/api/v1/account"));
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.raw().getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(anonymous.raw().getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(anonymous.text("code")).isEqualTo("SG-API-401");
        assertThat(anonymous.text("detail")).startsWith("Log in to use this endpoint");

        Reply forged = account(session.text("accessToken") + "x");
        assertThat(forged.status()).isEqualTo(401);
        assertThat(forged.text("detail")).startsWith("The access token has expired or is not valid");
        assertThat(forged.raw().getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("invalid_token");
    }

    @Test
    void theAnalysisEndpointsStayOpenToEveryone() {
        Reply report = post("/api/v1/report", Map.of("before", "CREATE TABLE t (a int);", "after", "CREATE TABLE t (a bigint);"));
        assertThat(report.status()).isEqualTo(200);
        assertThat(send(mvc.get().uri("/api/v1/rules")).status()).isEqualTo(200);
        assertThat(send(mvc.get().uri("/v3/api-docs")).status()).isEqualTo(200);
        assertThat(send(mvc.get().uri("/actuator/health")).status()).isEqualTo(200);
    }

    @Test
    void signingUpIsRefusedWithAReasonAPersonCanActOn() {
        String email = email();
        assertThat(signUp(email).status()).isEqualTo(201);

        Reply taken = signUp(email);
        assertThat(taken.status()).isEqualTo(409);
        assertThat(taken.text("code")).isEqualTo("SG-API-409");
        assertThat(taken.text("detail")).isEqualTo("An account with this email already exists. Log in instead.");
        assertThat(taken.setCookie()).isNull();

        Reply common = post("/api/v1/auth/signup", Map.of("email", email(), "password", "iloveyou"));
        assertThat(common.status()).isEqualTo(422);
        assertThat(common.text("code")).isEqualTo("SG-API-422");
        assertThat(common.text("detail")).contains("10,000 most common");

        Reply notAnEmail = post("/api/v1/auth/signup", Map.of("email", "nobody", "password", PASSWORD));
        assertThat(notAnEmail.status()).isEqualTo(422);
        assertThat(notAnEmail.text("detail")).isEqualTo("Enter an email address such as you@example.com.");

        assertThat(post("/api/v1/auth/signup", Map.of("email", email())).status()).isEqualTo(400);
    }

    @Test
    void loggingInSaysOnlyThatTheEmailOrPasswordIsWrong() {
        String email = email();
        signUp(email);

        Reply wrong = post("/api/v1/auth/login", Map.of("email", email, "password", "not the password"));
        Reply unknown = post("/api/v1/auth/login", Map.of("email", email(), "password", PASSWORD));
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(wrong.text("detail")).isEqualTo(unknown.text("detail")).isEqualTo("The email or password is wrong.");
        assertThat(wrong.setCookie()).isNull();

        Reply right = post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD));
        assertThat(right.status()).isEqualTo(200);
        assertThat(right.setCookie()).isNotNull();
    }

    @Test
    void tooManyFailuresLockTheAccountAndSayForHowLong() {
        String email = email();
        signUp(email);
        List<Integer> statuses = new ArrayList<>();
        Reply last = null;
        for (int i = 0; i < 5; i++) {
            last = post("/api/v1/auth/login", Map.of("email", email, "password", "guess " + i));
            statuses.add(last.status());
        }
        assertThat(statuses).containsExactly(401, 401, 401, 401, 429);
        assertThat(last.raw().getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(last.text("code")).isEqualTo("SG-API-429");
        assertThat(last.text("detail")).isEqualTo("Too many failed logins for this account. Try again in 1 minute.");

        Reply right = post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD));
        assertThat(right.status()).isEqualTo(429);
    }

    @Test
    void refreshingReplacesTheCookieAndLoggingOutEndsTheSession() {
        Reply session = signUp(email());

        Reply renewed = refresh(session.cookieValue());
        assertThat(renewed.status()).isEqualTo(200);
        assertThat(renewed.cookieValue()).isNotEqualTo(session.cookieValue());
        assertThat(account(renewed.text("accessToken")).status()).isEqualTo(200);

        Reply out = send(mvc.post().uri("/api/v1/auth/logout").cookie(new Cookie(COOKIE, renewed.cookieValue())));
        assertThat(out.status()).isEqualTo(204);
        assertThat(out.setCookie()).startsWith(COOKIE + "=;").contains("Max-Age=0").contains("Path=/api/v1/auth");

        Reply after = refresh(renewed.cookieValue());
        assertThat(after.status()).isEqualTo(401);
        assertThat(after.text("detail")).isEqualTo("Your session has ended; log in again.");
        assertThat(after.setCookie()).contains("Max-Age=0");

        assertThat(send(mvc.post().uri("/api/v1/auth/refresh")).status()).isEqualTo(401);
        assertThat(send(mvc.post().uri("/api/v1/auth/logout")).status()).isEqualTo(204);
    }

    @Test
    void anotherSiteCannotUseTheRefreshCookie() {
        Reply session = signUp(email());
        MvcTestResult foreign = mvc.post().uri("/api/v1/auth/refresh")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .cookie(new Cookie(COOKIE, session.cookieValue()))
                .exchange();
        assertThat(foreign.getResponse().getStatus()).isEqualTo(403);
        assertThat(foreign.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();

        // The web app's own origin (through the Vercel rewrite) is fine, and the session survived.
        MvcTestResult own = mvc.post().uri("/api/v1/auth/refresh")
                .header(HttpHeaders.ORIGIN, "https://schemaguard.example")
                .cookie(new Cookie(COOKIE, session.cookieValue()))
                .exchange();
        assertThat(own.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void deletingTheAccountNeedsItsPasswordAndEndsEverything() {
        String email = email();
        Reply session = signUp(email);
        String access = session.text("accessToken");

        Reply wrong = send(mvc.delete().uri("/api/v1/account")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"not it\"}"));
        assertThat(wrong.status()).isEqualTo(403);
        assertThat(wrong.text("detail")).isEqualTo("The password is wrong.");

        Reply gone = send(mvc.delete().uri("/api/v1/account")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("password", PASSWORD))));
        assertThat(gone.status()).isEqualTo(204);
        assertThat(gone.setCookie()).contains("Max-Age=0");

        // The access token is still signed and unexpired, but its account is gone.
        Reply orphan = account(access);
        assertThat(orphan.status()).isEqualTo(401);
        assertThat(orphan.text("detail")).isEqualTo("This account no longer exists.");
        assertThat(refresh(session.cookieValue()).status()).isEqualTo(401);
        assertThat(post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD)).status()).isEqualTo(401);
        assertThat(send(mvc.delete().uri("/api/v1/account").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"x\"}"))
                .status()).isEqualTo(401);
    }
}
