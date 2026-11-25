package io.github.hanumanthraghavendra.schemaguard.cli;

import static io.github.hanumanthraghavendra.schemaguard.cli.Cli.run;
import static io.github.hanumanthraghavendra.schemaguard.cli.Cli.write;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.hanumanthraghavendra.schemaguard.cli.Cli.Run;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** {@code save} against a real HTTP server on this machine, answering as the API does. */
class SaveCommandTest {

    private static final String KEY = "sg_TestKeyTestKeyTestKeyTestKeyTestKeyTestKey1";
    private static final String PROJECT = "3f9c2a1e-8b7d-4c6e-9f10-2a3b4c5d6e7f";
    private static final String SCHEMA = "CREATE TABLE t (id bigint PRIMARY KEY);\n";

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<Request> requests = new ArrayList<>();
    private volatile int status = 201;
    private volatile String answer = "";
    private volatile long delayMillis;

    record Request(String method, String path, Map<String, String> headers, String body) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
            synchronized (requests) {
                requests.add(new Request(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        headers,
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            }
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String site() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Map<String, String> env() {
        return Map.of(SaveCommand.KEY, KEY, SaveCommand.API, site());
    }

    private String schema() {
        return write(dir, "schema.sql", SCHEMA).toString();
    }

    /** Runs save and checks the key is never printed, whatever happened. */
    private Run save(Map<String, String> env, String... args) {
        String[] all = new String[args.length + 1];
        all[0] = "save";
        System.arraycopy(args, 0, all, 1, args.length);
        Run run = run(env, SCHEMA, all);
        assertThat(run.out() + run.err()).doesNotContain(KEY).doesNotContain(KEY.substring(3));
        return run;
    }

    private Request onlyRequest() {
        synchronized (requests) {
            assertThat(requests).hasSize(1);
            return requests.get(0);
        }
    }

    @Test
    void savesTheSchemaAndSaysWhatTheChangeScored() {
        answer = "{\"number\": 3, \"label\": \"Release 2.4\", \"risk\": {\"score\": 40, \"level\": \"MEDIUM\", \"reasons\": [],"
                + " \"changes\": 2, \"findings\": 1}, \"sql\": \"...\", \"previousSql\": \"...\"}";
        Run run = save(env(), "--project", PROJECT, "--label", "Release 2.4", schema());
        assertThat(run).isEqualTo(new Run(0, "Saved version 3. The change from version 2 scores 40/100 (Medium): 2 changes, 1 risk.\n", ""));

        Request request = onlyRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/api/v1/projects/" + PROJECT + "/versions");
        assertThat(request.headers()).containsEntry("authorization", "Bearer " + KEY)
                .containsEntry("content-type", "application/json")
                .containsEntry("user-agent", "schemaguard-cli");
        assertThat(Json.read(request.body())).isEqualTo(Map.of("sql", SCHEMA, "label", "Release 2.4"));
    }

    @Test
    void aProjectsFirstVersionHasNothingToCompareWith() {
        answer = "{\"number\": 1, \"label\": null, \"risk\": null, \"sql\": \"...\", \"previousSql\": null}";
        Run run = save(env(), "--project", PROJECT, "-");
        assertThat(run).isEqualTo(new Run(0, "Saved version 1, the project's first.\n", ""));
        assertThat(Json.read(onlyRequest().body())).as("no label key without --label; the schema came from standard input")
                .isEqualTo(Map.of("sql", SCHEMA));
    }

    @Test
    void anUnchangedSchemaIsNotSavedAndThatIsFine() {
        status = 409;
        answer = "{\"title\": \"Conflict\", \"status\": 409, \"detail\": \"The schema is the same as version 2.\"}";
        assertThat(save(env(), "--project", PROJECT, schema()))
                .isEqualTo(new Run(0, "Nothing saved: the schema is the same as the project's latest version.\n", ""));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "401 | {\"detail\": \"The API key is unknown or was deleted.\"} | The SchemaGuard API answered 401: The API key is unknown or was deleted.",
                "404 | {\"detail\": \"This account has no such project.\"}   | The SchemaGuard API answered 404: This account has no such project.",
                "502 | <html>Bad gateway</html>                                | The SchemaGuard API answered 502: no explanation in the answer.",
                "500 | {\"title\": \"Internal Server Error\"}                   | The SchemaGuard API answered 500: no explanation in the answer.",
                "201 | <html>not JSON</html>                                   | The SchemaGuard API answered with something that is not a saved version.",
                "201 | []                                                      | The SchemaGuard API answered with something that is not a saved version."
            })
    void aRefusalIsExplainedWithExitCodeThree(int code, String body, String message) {
        status = code;
        answer = body;
        Run run = save(env(), "--project", PROJECT, schema());
        assertThat(run).isEqualTo(new Run(3, "", "schemaguard: " + message + "\n"));
    }

    @Test
    void theKeyMustBeSetAndLookLikeAKey() {
        Run missing = save(Map.of(SaveCommand.API, site()), "--project", PROJECT, schema());
        assertThat(missing).isEqualTo(new Run(2, "", "schemaguard: Set SCHEMAGUARD_KEY to an API key from your account page on the SchemaGuard site.\n"));
        Run blank = save(Map.of(SaveCommand.KEY, " ", SaveCommand.API, site()), "--project", PROJECT, schema());
        assertThat(blank.code()).isEqualTo(2);
        Run wrong = save(Map.of(SaveCommand.KEY, "ghp_notours", SaveCommand.API, site()), "--project", PROJECT, schema());
        assertThat(wrong).isEqualTo(new Run(2, "", "schemaguard: SCHEMAGUARD_KEY does not hold a SchemaGuard API key; those start with sg_.\n"));
        assertThat(requests).as("nothing was sent").isEmpty();
    }

    @Test
    void theProjectMustBeAnId() {
        Run run = save(env(), "--project", "billing", schema());
        assertThat(run.code()).isEqualTo(2);
        assertThat(run.err()).startsWith("--project takes a project id such as ");
        assertThat(save(env(), schema()).err()).contains("Missing required option: '--project=<id>'");
    }

    @Test
    void theSiteComesFromTheOptionThenTheEnvironmentAndMustBeSecure() {
        answer = "{\"number\": 1, \"risk\": null}";
        Map<String, String> envPointingElsewhere = Map.of(SaveCommand.KEY, KEY, SaveCommand.API, "https://elsewhere.invalid");
        assertThat(save(envPointingElsewhere, "--project", PROJECT, "--api", site() + "/", schema()).code())
                .as("--api wins over the environment; a trailing slash is fine")
                .isZero();
        assertThat(onlyRequest().path()).isEqualTo("/api/v1/projects/" + PROJECT + "/versions");

        for (String address : List.of("http://example.com", "ftp://example.com", "not an address", "https://")) {
            Run run = save(env(), "--project", PROJECT, "--api", address, schema());
            assertThat(run.code()).as(address).isEqualTo(2);
            assertThat(run.err()).as(address).startsWith("schemaguard: ");
        }
        assertThat(save(env(), "--project", PROJECT, "--api", "http://example.com", schema()).err())
                .isEqualTo("schemaguard: --api must be an https:// address (plain http only for localhost), not http://example.com.\n");
    }

    @Test
    void aSlowAnswerTimesOutWithExitCodeThree() {
        delayMillis = 3_000;
        Map<String, String> env = new HashMap<>(env());
        env.put(SaveCommand.TIMEOUT, "1");
        Run run = save(env, "--project", PROJECT, schema());
        assertThat(run.code()).isEqualTo(3);
        assertThat(run.err()).isEqualTo("schemaguard: No answer from " + site() + " within 1 second. "
                + "The free host may still be waking up; try again in a minute.\n");
    }

    @Test
    void theTimeoutMustBeANumberOfSeconds() {
        for (String value : List.of("0", "soon", "1000", "-5")) {
            Map<String, String> env = new HashMap<>(env());
            env.put(SaveCommand.TIMEOUT, value);
            Run run = save(env, "--project", PROJECT, schema());
            assertThat(run).as(value).isEqualTo(new Run(2, "",
                    "schemaguard: SCHEMAGUARD_TIMEOUT must be a number of seconds from 1 to 999, not " + value + ".\n"));
        }
    }

    @Test
    void aSiteThatIsNotThereIsExplainedWithExitCodeThree() {
        String gone = site();
        server.stop(0);
        Run run = save(Map.of(SaveCommand.KEY, KEY, SaveCommand.API, gone), "--project", PROJECT, schema());
        assertThat(run.code()).isEqualTo(3);
        assertThat(run.err()).startsWith("schemaguard: Cannot connect to " + gone);
        server = null;
    }
}
