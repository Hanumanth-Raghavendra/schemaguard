package io.github.hanumanthraghavendra.schemaguard.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The limits and the CORS rules on a real server, with small limits so they are easy to
 * reach. A real server is needed to send a body without a length (chunked), which is the
 * case the size filter has to read itself.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "schemaguard.limits.max-body-bytes=2048",
            "schemaguard.limits.max-input-chars=300",
            "schemaguard.limits.max-statements=3",
            "schemaguard.cors.allowed-origins=https://schemaguard.example"
        })
class LimitsAndCorsTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, String body, boolean chunked) throws IOException, InterruptedException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher publisher =
                chunked
                        ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
                        : HttpRequest.BodyPublishers.ofByteArray(bytes);
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(publisher)
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String compare(String before, String after) {
        return "{\"before\":\"" + before + "\",\"after\":\"" + after + "\"}";
    }

    @Test
    void aBodyAboveTheLimitIsRefusedBeforeItIsParsed() throws Exception {
        String big = compare("x".repeat(3000), "");
        HttpResponse<String> declared = post("/api/v1/diff", big, false);
        assertThat(declared.statusCode()).isEqualTo(413);
        assertThat(declared.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(declared.body()).contains("\"code\":\"SG-API-413\"", "larger than 2048 bytes");

        HttpResponse<String> chunked = post("/api/v1/diff", big, true);
        assertThat(chunked.statusCode()).isEqualTo(413);
    }

    @Test
    void aSmallChunkedBodyIsReadNormally() throws Exception {
        HttpResponse<String> reply = post("/api/v1/diff", compare("CREATE TABLE t (a int);", "CREATE TABLE t (a int, b int);"), true);
        assertThat(reply.statusCode()).isEqualTo(200);
        assertThat(reply.body()).contains("+ column public.t.b integer");
    }

    @Test
    void anInputAboveTheCharacterLimitIsRefused() throws Exception {
        HttpResponse<String> reply = post("/api/v1/diff", compare("-- " + "x".repeat(400), ""), false);
        assertThat(reply.statusCode()).isEqualTo(413);
        assertThat(reply.body()).contains("The before input has 403 characters; at most 300 are accepted.");
    }

    @Test
    void aScriptWithTooManyStatementsIsRefused() throws Exception {
        String body = "{\"schema\":\"CREATE TABLE t (a int);\",\"migration\":\"SELECT 1; SELECT 2; SELECT 3; SELECT 4;\"}";
        HttpResponse<String> reply = post("/api/v1/lint", body, false);
        assertThat(reply.statusCode()).isEqualTo(413);
        assertThat(reply.body()).contains("The migration has 4 statements; at most 3 are accepted.");
        String fine = "{\"schema\":\"CREATE TABLE t (a int);\",\"migration\":\"SELECT 1; SELECT 2;\"}";
        assertThat(post("/api/v1/lint", fine, false).statusCode()).isEqualTo(200);
    }

    @Test
    void onlyTheConfiguredOriginMayCallFromABrowser() throws Exception {
        HttpResponse<String> allowed = preflight("https://schemaguard.example");
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("https://schemaguard.example");

        HttpResponse<String> other = preflight("https://evil.example");
        assertThat(other.statusCode()).isEqualTo(403);
        assertThat(other.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    private HttpResponse<String> preflight(String origin) throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/diff"))
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
