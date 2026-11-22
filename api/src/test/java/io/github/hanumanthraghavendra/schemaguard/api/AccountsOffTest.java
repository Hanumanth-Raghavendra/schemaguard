package io.github.hanumanthraghavendra.schemaguard.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.api.auth.AccessTokens;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Without a database, accounts say they are switched off, and nothing else changes. */
@SpringBootTest
@AutoConfigureMockMvc
class AccountsOffTest {

    private static final String CREDENTIALS = "{\"email\":\"you@example.com\",\"password\":\"correct horse battery staple\"}";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AccessTokens tokens;

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void signingUpAndLoggingInAnswer503() {
        for (String path : new String[] {"/api/v1/auth/signup", "/api/v1/auth/login"}) {
            MvcTestResult result = mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS).exchange();
            assertThat(result.getResponse().getStatus()).isEqualTo(503);
            assertThat(body(result)).contains("\"code\":\"SG-API-503\"", "Accounts are switched off on this server.");
        }
    }

    @Test
    void withoutASessionThereIsNothingToRefreshOrEnd() {
        assertThat(mvc.post().uri("/api/v1/auth/refresh").exchange().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.post().uri("/api/v1/auth/refresh").cookie(new Cookie("__Secure-sg-refresh", "a".repeat(43))).exchange()
                .getResponse().getStatus()).isEqualTo(503);
        assertThat(mvc.post().uri("/api/v1/auth/logout").cookie(new Cookie("__Secure-sg-refresh", "a".repeat(43))).exchange()
                .getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void aValidAccessTokenStillNeedsTheDatabaseToNameAnAccount() {
        String token = tokens.issue(UUID.randomUUID()).value();
        MvcTestResult result = mvc.get().uri("/api/v1/account").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(mvc.get().uri("/api/v1/account").exchange().getResponse().getStatus()).isEqualTo(401);
    }
}
