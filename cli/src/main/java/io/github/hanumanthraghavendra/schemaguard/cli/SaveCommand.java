package io.github.hanumanthraghavendra.schemaguard.cli;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
        name = "save",
        description = {
            "Save a schema as the next version of a project on the SchemaGuard site, as CI does after a release.",
            "Needs an API key in the SCHEMAGUARD_KEY environment variable (make one on your account page and keep it",
            "in a CI secret). A schema identical to the project's latest version is not saved again, and that is not an error.",
            "Waits up to 120 seconds for an answer (the free host takes about a minute to wake up); set "
                    + SaveCommand.TIMEOUT + " to another number of seconds."
        })
final class SaveCommand implements Callable<Integer> {

    static final String DEFAULT_API = "https://schemaguard-v1.vercel.app";
    static final String KEY = "SCHEMAGUARD_KEY";
    static final String API = "SCHEMAGUARD_API";
    static final String TIMEOUT = "SCHEMAGUARD_TIMEOUT";

    private static final Pattern PROJECT_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    @ParentCommand
    SchemaGuardCli root;

    @Spec
    CommandSpec spec;

    @Option(names = "--project", required = true, paramLabel = "<id>", description = "The project's id, as in its address on the site")
    String project;

    @Option(names = "--label", paramLabel = "<text>", description = "A note of at most 120 characters, such as a release or a commit")
    String label;

    @Option(
            names = "--api",
            paramLabel = "<url>",
            description = "The SchemaGuard site (default: $" + API + " if set, else " + DEFAULT_API + ")")
    String api;

    @Parameters(
            index = "0",
            paramLabel = "<schema.sql>",
            description = "The whole schema, as pg_dump --schema-only prints it (- for standard input)")
    String schema;

    @Override
    public Integer call() {
        Io io = root.io();
        String key = io.env().get(KEY);
        if (key == null || key.isBlank()) {
            throw new InputException("Set " + KEY + " to an API key from your account page on the SchemaGuard site.");
        }
        if (!key.startsWith("sg_")) {
            throw new InputException(KEY + " does not hold a SchemaGuard API key; those start with sg_.");
        }
        if (!PROJECT_ID.matcher(project).matches()) {
            throw new ParameterException(spec.commandLine(), "--project takes a project id such as 3f9c2a1e-8b7d-4c6e-9f10-2a3b4c5d6e7f.");
        }
        URI site = site(api != null ? api : io.env().getOrDefault(API, DEFAULT_API));
        Duration timeout = timeout(io.env().get(TIMEOUT));
        Source source = Source.read(schema, io);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", source.sql());
        if (label != null) {
            body.put("label", label);
        }
        HttpRequest request = HttpRequest.newBuilder(site.resolve("/api/v1/projects/" + project + "/versions"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "schemaguard-cli")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request, site, timeout);

        switch (response.statusCode()) {
            case 201 -> io.out().println(saved(response.body()));
            case 409 -> io.out().println("Nothing saved: the schema is the same as the project's latest version.");
            default -> throw new ApiException("The SchemaGuard API answered " + response.statusCode() + ": " + detail(response.body()));
        }
        return ExitCodes.OK;
    }

    /** The site's address. Plain http is refused except on this machine: the key would travel in clear text. */
    private URI site(String address) {
        String trimmed = address.strip().replaceAll("/+$", "");
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new InputException(address + " is not an address.");
        }
        boolean secure = "https".equals(uri.getScheme());
        boolean local = "http".equals(uri.getScheme()) && uri.getHost() != null && LOCAL_HOSTS.contains(uri.getHost());
        if (uri.getHost() == null || !(secure || local)) {
            throw new InputException("--api must be an https:// address (plain http only for localhost), not " + address + ".");
        }
        return uri;
    }

    private static Duration timeout(String seconds) {
        if (seconds == null || seconds.isBlank()) {
            return Duration.ofSeconds(120);
        }
        if (!seconds.strip().matches("\\d{1,3}") || Integer.parseInt(seconds.strip()) == 0) {
            throw new InputException(TIMEOUT + " must be a number of seconds from 1 to 999, not " + seconds + ".");
        }
        return Duration.ofSeconds(Integer.parseInt(seconds.strip()));
    }

    private static HttpResponse<String> send(HttpRequest request, URI site, Duration timeout) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout.compareTo(Duration.ofSeconds(30)) < 0 ? timeout : Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            long seconds = timeout.toSeconds();
            throw new ApiException("No answer from " + site + " within " + seconds + (seconds == 1 ? " second" : " seconds")
                    + ". The free host may still be waking up; try again in a minute.");
        } catch (ConnectException e) {
            throw new ApiException("Cannot connect to " + site + ".");
        } catch (IOException e) {
            throw new ApiException("Cannot reach " + site + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Interrupted while waiting for " + site + ".");
        } finally {
            client.close();
        }
    }

    /** "Saved version 3. The change from version 2 scores 40/100 (Medium): 2 changes, 1 risk." */
    private static String saved(String body) {
        Map<?, ?> version = object(body);
        int number = ((BigDecimal) version.get("number")).intValue();
        if (!(version.get("risk") instanceof Map<?, ?> risk)) {
            return "Saved version " + number + ", the project's first.";
        }
        int changes = ((BigDecimal) risk.get("changes")).intValue();
        int findings = ((BigDecimal) risk.get("findings")).intValue();
        return "Saved version " + number + ". The change from version " + (number - 1) + " scores "
                + risk.get("score") + "/100 (" + Markdown.sentenceCase(String.valueOf(risk.get("level"))) + "): "
                + changes + (changes == 1 ? " change, " : " changes, ") + findings + (findings == 1 ? " risk." : " risks.");
    }

    /** The problem document's detail, or a plain note when the body is not one (a proxy's page, say). */
    private static String detail(String body) {
        try {
            if (Json.read(body) instanceof Map<?, ?> problem && problem.get("detail") instanceof String detail) {
                return detail;
            }
        } catch (Json.ParseException e) {
            // Not JSON: fall through.
        }
        return "no explanation in the answer.";
    }

    private static Map<?, ?> object(String body) {
        try {
            if (Json.read(body) instanceof Map<?, ?> map) {
                return map;
            }
        } catch (Json.ParseException e) {
            // Reported below.
        }
        throw new ApiException("The SchemaGuard API answered with something that is not a saved version.");
    }
}
