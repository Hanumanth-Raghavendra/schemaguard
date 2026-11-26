package io.github.hanumanthraghavendra.schemaguard.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.api.ratelimit.Category;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The rate limits on a real server, with small rates. All tests share one server and so one set
 * of counts; each uses its own made-up address, sent the way Render's proxies send it, as the
 * last {@code X-Forwarded-For} entry. Without a database, saving, sign-up and login answer 503,
 * which is enough: the limit is counted before the request reaches them.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "schemaguard.rate-limits.enabled=true",
            "schemaguard.rate-limits.analyses=3/1m",
            "schemaguard.rate-limits.shares=2/1h",
            "schemaguard.rate-limits.signups=2/1h",
            "schemaguard.rate-limits.logins=2/10m",
            "schemaguard.rate-limits.connecting-factor=2"
        })
class RateLimitTest {

    private static final String COMPARE = "{\"before\":\"CREATE TABLE t (a int);\",\"after\":\"CREATE TABLE t (a int, b int);\"}";
    private static final String CREDENTIALS = "{\"email\":\"someone@example.com\",\"password\":\"a long enough password\"}";

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, String body, String forwardedFor, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("X-Forwarded-For", forwardedFor)
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    @Test
    void analysesAreLimitedPerAddressAndTheRefusalSaysWhenToComeBack() throws Exception {
        for (int remaining = 2; remaining >= 0; remaining--) {
            HttpResponse<String> reply = post("/api/v1/diff", COMPARE, "10.0.0.1, 203.0.113.1");
            assertThat(reply.statusCode()).isEqualTo(200);
            assertThat(header(reply, "X-RateLimit-Limit")).isEqualTo("3");
            assertThat(header(reply, "X-RateLimit-Remaining")).isEqualTo(Integer.toString(remaining));
        }
        HttpResponse<String> refused = post("/api/v1/report", COMPARE, "203.0.113.1");
        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(header(refused, "Content-Type")).startsWith("application/problem+json");
        assertThat(header(refused, "X-RateLimit-Remaining")).isEqualTo("0");
        // 3 a minute come back one every 20 seconds.
        assertThat(Long.parseLong(header(refused, "Retry-After"))).isBetween(1L, 20L);
        assertThat(refused.body())
                .startsWith("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,")
                .contains("\"detail\":\"Too many analyses from your address: at most 3 per minute. Try again in ")
                .endsWith(" seconds.\",\"code\":\"SG-API-429\"}");

        // The made-up first entry is not the address; another real address has its own count.
        assertThat(post("/api/v1/diff", COMPARE, "203.0.113.1, 203.0.113.2").statusCode()).isEqualTo(200);
    }

    @Test
    void anEncodedPathCountsAsTheEndpointItNames() throws Exception {
        assertThat(post("/api/v1/%64iff", COMPARE, "203.0.113.3").headers().firstValue("X-RateLimit-Remaining")).hasValue("2");
        post("/api/v1/plan", COMPARE, "203.0.113.3");
        post("/api/v1/projects/abc/versions", "{}", "203.0.113.3");
        assertThat(post("/api/v1/lint", "{}", "203.0.113.3").statusCode()).isEqualTo(429);
    }

    @Test
    void madeUpVercelVisitorsAreHeldToTheConnectingCap() throws Exception {
        int allowed = 0;
        HttpResponse<String> last = null;
        for (int i = 0; i < 10; i++) {
            last = post("/api/v1/diff", COMPARE, "203.0.113.4", "X-Vercel-Forwarded-For", "192.0.2." + i);
            if (last.statusCode() == 200) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(6);
        assertThat(last.statusCode()).isEqualTo(429);
        assertThat(last.body()).contains("at most 6 per minute");
        // A visitor the cap refused was not charged: through another address they have all three.
        HttpResponse<String> elsewhere = post("/api/v1/diff", COMPARE, "203.0.113.5", "X-Vercel-Forwarded-For", "192.0.2.9");
        assertThat(elsewhere.statusCode()).isEqualTo(200);
        assertThat(header(elsewhere, "X-RateLimit-Remaining")).isEqualTo("2");
    }

    @Test
    void savingSigningUpAndLoggingInHaveTheirOwnCounts() throws Exception {
        String[][] cases = {
            {"/api/v1/analyses/diff", COMPARE, "203.0.113.6", "saved analyses from your address: at most 2 per hour. Try again in 30 minutes."},
            {"/api/v1/auth/signup", CREDENTIALS, "203.0.113.7", "sign-ups from your address: at most 2 per hour. Try again in 30 minutes."},
            {"/api/v1/auth/login", CREDENTIALS, "203.0.113.8", "login attempts from your address: at most 2 per 10 minutes. Try again in 5 minutes."}
        };
        for (String[] c : cases) {
            for (int i = 0; i < 2; i++) {
                HttpResponse<String> reply = post(c[0], c[1], c[2]);
                assertThat(reply.statusCode()).as(c[0]).isEqualTo(503);
                assertThat(header(reply, "X-RateLimit-Limit")).as(c[0]).isEqualTo("2");
            }
            HttpResponse<String> refused = post(c[0], c[1], c[2]);
            assertThat(refused.statusCode()).as(c[0]).isEqualTo(429);
            assertThat(refused.body()).as(c[0]).contains("Too many " + c[3]);
            // An analysis from the same address is a different count.
            assertThat(post("/api/v1/diff", COMPARE, c[2]).statusCode()).as(c[0]).isEqualTo(200);
        }
    }

    @Test
    void otherRequestsAreNotCounted() throws Exception {
        for (int i = 0; i < 10; i++) {
            HttpResponse<String> reply =
                    client.send(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/rules"))
                                    .header("X-Forwarded-For", "203.0.113.9")
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(reply.statusCode()).isEqualTo(200);
            assertThat(reply.headers().firstValue("X-RateLimit-Limit")).isEmpty();
        }
        assertThat(post("/api/v1/auth/refresh", "", "203.0.113.9").headers().firstValue("X-RateLimit-Limit")).isEmpty();
    }

    /** A new POST endpoint has to be given a limit here, or be named as one that needs none. */
    @Test
    void everyPostEndpointIsLimitedOrKnownToNeedNoLimit() {
        Set<String> unlimited = new TreeSet<>();
        Set<String> limited = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            if (!info.getMethodsCondition().getMethods().contains(RequestMethod.POST)) {
                continue;
            }
            for (String pattern : info.getPatternValues()) {
                String path = pattern.replaceAll("\\{[^}]+}", "some-id");
                (Category.of("POST", path) == null ? unlimited : limited).add(pattern);
            }
        }
        // Each needs a session and is bounded per account (20 projects, 10 keys), or is cheap.
        assertThat(unlimited)
                .containsExactly("/api/v1/account/keys", "/api/v1/auth/logout", "/api/v1/auth/refresh", "/api/v1/projects");
        assertThat(limited).hasSize(12);
    }
}
