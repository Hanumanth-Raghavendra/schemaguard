package io.github.hanumanthraghavendra.schemaguard.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The API end to end inside Spring, with the real engine behind it. */
@SpringBootTest
@AutoConfigureMockMvc
class AnalysisApiTest {

    private static final String BEFORE =
            """
            CREATE TABLE users (id int PRIMARY KEY, display_name text, email text);
            CREATE TABLE posts (id int PRIMARY KEY, user_id int, title text);
            """;
    private static final String AFTER =
            """
            CREATE TABLE users (id int PRIMARY KEY, full_name text, email text NOT NULL);
            CREATE TABLE posts (id int PRIMARY KEY, user_id int REFERENCES users (id), title text);
            CREATE INDEX posts_title_idx ON posts (title);
            """;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JsonMapper json;

    private record Reply(int status, String contentType, JsonNode body) {}

    private Reply post(String path, Object body) {
        return send(path, json.writeValueAsString(body));
    }

    private Reply send(String path, String body) {
        MvcTestResult result = mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        return reply(result);
    }

    private Reply get(String path) {
        return reply(mvc.get().uri(path).exchange());
    }

    private Reply reply(MvcTestResult result) {
        String text = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        JsonNode body = text.isEmpty() ? null : json.readTree(text);
        return new Reply(result.getResponse().getStatus(), result.getResponse().getContentType(), body);
    }

    private static Map<String, Object> compare(String before, String after) {
        return Map.of("before", before, "after", after);
    }

    // ---------------------------------------------------------------- diff

    @Test
    void diffListsTheChangesAndOffersRenamesToConfirm() {
        Reply reply = post("/api/v1/diff", compare(BEFORE, AFTER));
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("significant").asInt()).isEqualTo(reply.body().get("changes").size());
        assertThat(reply.body().get("text").asString()).contains("+ index public.posts_title_idx");

        JsonNode rename = null;
        for (JsonNode change : reply.body().get("changes")) {
            if (change.get("kind").asString().equals("ColumnRenameCandidate")) {
                rename = change;
            }
        }
        assertThat(rename).isNotNull();
        assertThat(rename.get("confidence").asDouble()).isBetween(0.5, 1.0);
        assertThat(rename.get("table").get("name").asString()).isEqualTo("users");
        assertThat(rename.get("rename").get("kind").asString()).isEqualTo("column");
        assertThat(rename.get("rename").get("from").asString()).isEqualTo("display_name");
        assertThat(rename.get("rename").get("to").asString()).isEqualTo("full_name");
    }

    @Test
    void equalSchemasHaveNoChanges() {
        Reply reply = post("/api/v1/diff", compare(BEFORE, BEFORE));
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("changes").size()).isZero();
        assertThat(reply.body().get("text").asString()).isEmpty();
    }

    // ---------------------------------------------------------------- model

    @Test
    void modelMarksWhatChangedInEachTableColumnAndForeignKey() {
        Reply reply = post("/api/v1/model", compare(BEFORE, AFTER));
        assertThat(reply.status()).isEqualTo(200);
        JsonNode tables = reply.body().get("tables");
        assertThat(tables.size()).isEqualTo(2);
        JsonNode posts = tables.get(0);
        assertThat(posts.get("name").get("name").asString()).isEqualTo("posts");
        assertThat(posts.get("status").asString()).isEqualTo("CHANGED");
        JsonNode users = tables.get(1);
        assertThat(users.get("status").asString()).isEqualTo("CHANGED");
        StringBuilder columns = new StringBuilder();
        for (JsonNode column : users.get("columns")) {
            columns.append(column.get("name").asString()).append('=').append(column.get("status").asString()).append(' ');
        }
        assertThat(columns.toString().trim())
                .isEqualTo("id=UNCHANGED full_name=ADDED email=CHANGED display_name=REMOVED");
        assertThat(users.get("columns").get(0).get("primaryKey").asBoolean()).isTrue();
        assertThat(users.get("columns").get(0).get("previousType").isNull()).isTrue();

        JsonNode reference = reply.body().get("references").get(0);
        assertThat(reference.get("from").get("name").asString()).isEqualTo("posts");
        assertThat(reference.get("to").get("name").asString()).isEqualTo("users");
        assertThat(reference.get("columns").get(0).asString()).isEqualTo("user_id");
        assertThat(reference.get("status").asString()).isEqualTo("ADDED");
        assertThat(reply.body().get("warnings").size()).isZero();
    }

    @Test
    void modelRefusesASchemaItCannotRead() {
        Reply reply = post("/api/v1/model", compare("CREATE TABLE t (id int;", AFTER));
        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.body().get("diagnostics").get(0).get("input").asString()).isEqualTo("before");
    }

    // ---------------------------------------------------------------- report

    @Test
    void reportScoresTheChangesAndExplainsEachFinding() {
        Reply reply = post("/api/v1/report", compare(BEFORE, AFTER));
        assertThat(reply.status()).isEqualTo(200);
        JsonNode score = reply.body().get("score");
        assertThat(score.get("value").asInt()).isBetween(1, 100);
        assertThat(score.get("level").asString()).isIn("LOW", "MEDIUM", "HIGH", "CRITICAL");
        assertThat(score.get("reasons").size()).isPositive();

        JsonNode index = finding(reply.body().get("findings"), "SG-R101");
        assertThat(index.get("severity").asString()).isEqualTo("HIGH");
        assertThat(index.get("table").get("name").asString()).isEqualTo("posts");
        assertThat(index.get("operation").get("lock").asString()).isEqualTo("SHARE");
        assertThat(index.get("operation").get("work").asString()).isEqualTo("SCAN");
        assertThat(index.get("effect").asString()).startsWith("CREATE INDEX takes SHARE on public.posts");
        assertThat(index.get("change").asString()).startsWith("+ index public.posts_title_idx");
        assertThat(index.get("fix").asString()).contains("CONCURRENTLY");

        JsonNode foreignKey = finding(reply.body().get("findings"), "SG-R104");
        assertThat(foreignKey.get("effect").asString()).contains("SHARE ROW EXCLUSIVE on public.users");
        assertThat(reply.body().get("text").asString()).startsWith("Risk: " + score.get("value").asInt() + "/100");
    }

    private static JsonNode finding(JsonNode findings, String ruleId) {
        for (JsonNode finding : findings) {
            if (finding.get("ruleId").asString().equals(ruleId)) {
                return finding;
            }
        }
        throw new AssertionError("no finding of " + ruleId + " in " + findings);
    }

    // ---------------------------------------------------------------- plan

    @Test
    void theSafePlanIsTheDefaultAndAvoidsLongLocks() {
        Reply reply = post("/api/v1/plan", compare(BEFORE, AFTER));
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("route").asString()).isEqualTo("SAFE");
        String sql = reply.body().get("sql").asString();
        assertThat(sql).contains("SET lock_timeout = '5s'", "CREATE INDEX CONCURRENTLY", "NOT VALID", "VALIDATE CONSTRAINT");
        JsonNode first = reply.body().get("steps").get(0);
        assertThat(first.get("sql").asString()).isEqualTo("SET lock_timeout = '5s'");
        assertThat(first.get("transactional").asBoolean()).isTrue();
        assertThat(reply.body().get("complete").asBoolean()).isTrue();
        // Without the rename confirmed, display_name is dropped: a decision for a person.
        assertThat(reply.body().get("needsDecisions").asBoolean()).isTrue();
    }

    @Test
    void aRenameFromTheDiffCanBeSentBackToThePlan() {
        JsonNode changes = post("/api/v1/diff", compare(BEFORE, AFTER)).body().get("changes");
        Object rename = null;
        for (JsonNode change : changes) {
            if (!change.get("rename").isNull()) {
                rename = json.treeToValue(change.get("rename"), Object.class);
            }
        }
        Map<String, Object> request = new LinkedHashMap<>(compare(BEFORE, AFTER));
        request.put("route", "DIRECT");
        request.put("renames", List.of(rename));
        Reply reply = post("/api/v1/plan", request);
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("route").asString()).isEqualTo("DIRECT");
        String sql = reply.body().get("sql").asString();
        assertThat(sql).contains("ALTER TABLE public.users RENAME COLUMN display_name TO full_name;").doesNotContain("DROP COLUMN");
        assertThat(reply.body().get("needsDecisions").asBoolean()).isFalse();
    }

    @Test
    void tableAndEnumValueRenamesAreAcceptedToo() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("before", "CREATE TYPE mood AS ENUM ('sad'); CREATE TABLE logs (id int);");
        request.put("after", "CREATE TYPE mood AS ENUM ('unhappy'); CREATE TABLE audit.events (id int);");
        request.put(
                "renames",
                List.of(
                        Map.of("kind", "table", "from", Map.of("schema", "public", "name", "logs"), "to", Map.of("schema", "audit", "name", "events")),
                        Map.of("kind", "enumValue", "type", Map.of("schema", "public", "name", "mood"), "from", "sad", "to", "unhappy")));
        request.put("lockTimeout", "2s");
        Reply reply = post("/api/v1/plan", request);
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("sql").asString())
                .contains("SET lock_timeout = '2s'", "RENAME TO events", "SET SCHEMA audit", "RENAME VALUE 'sad' TO 'unhappy'");
    }

    @Test
    void aPlanWithNothingToDoForSomeChangesSaysSo() {
        Reply reply = post("/api/v1/plan", compare("CREATE TYPE mood AS ENUM ('a', 'b');", "CREATE TYPE mood AS ENUM ('b');"));
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("complete").asBoolean()).isFalse();
        assertThat(reply.body().get("unplanned").get(0).get("reason").asString()).startsWith("PostgreSQL cannot remove a value");
    }

    @Test
    void aRenameThatDoesNotFitOrABadLockTimeoutIsRejected() {
        Map<String, Object> request = new LinkedHashMap<>(compare(BEFORE, AFTER));
        request.put("renames", List.of(Map.of("kind", "column", "table", Map.of("schema", "public", "name", "users"), "from", "nope", "to", "x")));
        Reply rename = post("/api/v1/plan", request);
        assertThat(rename.status()).isEqualTo(422);
        assertThat(rename.body().get("detail").asString()).contains("column \"nope\" does not exist");

        Map<String, Object> timeout = new LinkedHashMap<>(compare(BEFORE, AFTER));
        timeout.put("lockTimeout", "5s'; DROP TABLE users; --");
        Reply bad = post("/api/v1/plan", timeout);
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body().get("code").asString()).isEqualTo("SG-API-422");

        Map<String, Object> kind = new LinkedHashMap<>(compare(BEFORE, AFTER));
        kind.put("renames", List.of(Map.of("kind", "index", "from", "a", "to", "b")));
        assertThat(post("/api/v1/plan", kind).status()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- lint

    @Test
    void lintJudgesEachStatementAndPointsAtItsLine() {
        String migration =
                """
                SET lock_timeout = '5s';
                CREATE INDEX CONCURRENTLY posts_title_idx ON posts (title);
                ALTER TABLE users ALTER COLUMN email SET NOT NULL;
                CREATE VIEW v AS SELECT 1;
                """;
        Reply reply = post("/api/v1/lint", Map.of("schema", BEFORE, "migration", migration));
        assertThat(reply.status()).isEqualTo(200);
        JsonNode statements = reply.body().get("statements");
        assertThat(statements.size()).isEqualTo(4);
        assertThat(statements.get(0).get("status").asString()).isEqualTo("NOT_SCHEMA");
        assertThat(statements.get(1).get("findings").size()).isZero();
        assertThat(statements.get(1).get("operations").get(0).get("name").asString()).isEqualTo("CREATE_INDEX_CONCURRENTLY");
        JsonNode notNull = statements.get(2);
        assertThat(notNull.get("line").asInt()).isEqualTo(3);
        assertThat(notNull.get("table").get("name").asString()).isEqualTo("users");
        assertThat(notNull.get("findings").get(0).get("ruleId").asString()).isEqualTo("SG-R106");
        assertThat(notNull.get("findings").get(0).get("effect").asString()).contains("ACCESS EXCLUSIVE on public.users");
        assertThat(statements.get(3).get("status").asString()).isEqualTo("NOT_ANALYSED");
        assertThat(reply.body().get("diagnostics").get(0).get("input").asString()).isEqualTo("migration");
        assertThat(reply.body().get("diagnostics").get(0).get("line").asInt()).isEqualTo(4);
        assertThat(reply.body().get("score").get("level").asString()).isEqualTo("HIGH");
        assertThat(reply.body().get("text").asString()).contains("line 3: ALTER TABLE users ALTER COLUMN email SET NOT NULL");
    }

    // ---------------------------------------------------------------- errors

    @Test
    void aSchemaThatDoesNotParseIsRejectedWithItsDiagnostics() {
        Reply reply = post("/api/v1/report", compare("CREATE TABLE users (id int,\n  name text NOT NULL DEFAULT);", AFTER));
        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.contentType()).isEqualTo("application/problem+json");
        assertThat(reply.body().get("title").asString()).isEqualTo("Input cannot be analysed");
        assertThat(reply.body().get("detail").asString()).isEqualTo("The before schema has 1 error and cannot be analysed.");
        JsonNode diagnostic = reply.body().get("diagnostics").get(0);
        assertThat(diagnostic.get("input").asString()).isEqualTo("before");
        assertThat(diagnostic.get("severity").asString()).isEqualTo("ERROR");
        assertThat(diagnostic.get("line").asInt()).isEqualTo(2);

        Reply refused = post("/api/v1/diff", compare("CREATE TABLE t (a int); CREATE TABLE t (b int);", "CREATE TABLE t (a int);"));
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.body().get("diagnostics").get(0).get("message").asString()).isEqualTo("relation \"t\" already exists");

        assertThat(post("/api/v1/lint", Map.of("schema", "CREATE TABLE (;", "migration", "")).status()).isEqualTo(422);
    }

    @Test
    void statementsTheEngineSkipsComeBackAsWarnings() {
        Reply reply = post("/api/v1/diff", compare(BEFORE + "CREATE VIEW v AS SELECT 1;", BEFORE));
        assertThat(reply.status()).isEqualTo(200);
        JsonNode warning = reply.body().get("warnings").get(0);
        assertThat(warning.get("severity").asString()).isEqualTo("WARNING");
        assertThat(warning.get("input").asString()).isEqualTo("before");
        assertThat(warning.get("message").asString()).isEqualTo("Unsupported statement: CREATE VIEW. It was not analysed.");
    }

    @Test
    void malformedOrIncompleteRequestsAreBadRequests() {
        Reply broken = send("/api/v1/diff", "{\"before\": ");
        assertThat(broken.status()).isEqualTo(400);
        assertThat(broken.contentType()).isEqualTo("application/problem+json");
        Reply missing = post("/api/v1/diff", Map.of("before", BEFORE));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.contentType()).isEqualTo("application/problem+json");
        assertThat(post("/api/v1/plan", Map.of("before", "", "after", "", "route", "FAST")).status()).isEqualTo(400);
        assertThat(get("/api/v1/diff").status()).isEqualTo(405);
    }

    // ---------------------------------------------------------------- rules, docs, health

    @Test
    void everyRuleIsListedWithItsScope() {
        Reply reply = get("/api/v1/rules");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().size()).isEqualTo(40);
        JsonNode first = reply.body().get(0);
        assertThat(first.get("id").asString()).isEqualTo("SG-R101");
        assertThat(first.get("scope").asString()).isEqualTo("schema");
        assertThat(reply.body().get(39).get("scope").asString()).isEqualTo("script");
        assertThat(first.get("why").asString()).isNotBlank();
    }

    @Test
    void theOpenApiDescriptionCoversEveryEndpoint() {
        Reply reply = get("/v3/api-docs");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("info").get("title").asString()).isEqualTo("SchemaGuard API");
        assertThat(reply.body().get("paths").propertyNames())
                .contains("/api/v1/diff", "/api/v1/report", "/api/v1/plan", "/api/v1/lint", "/api/v1/rules");
        assertThat(reply.body().at("/components/schemas/TableRename").isMissingNode()).isFalse();
        for (String[] answer :
                new String[][] {
                    {"diff", "DiffResponse"}, {"report", "ReportResponse"}, {"plan", "PlanResponse"}, {"lint", "LintResponse"}
                }) {
            JsonNode schema = reply.body().at("/paths/~1api~1v1~1" + answer[0] + "/post/responses/200/content/application~1json/schema");
            assertThat(schema.get("$ref").asString()).as(answer[0]).isEqualTo("#/components/schemas/" + answer[1]);
        }
        JsonNode rules = reply.body().at("/paths/~1api~1v1~1rules/get/responses/200/content/application~1json/schema");
        assertThat(rules.get("items").get("$ref").asString()).isEqualTo("#/components/schemas/Rule");
        assertThat(reply.body().at("/components/schemas/Finding/properties/effect").isMissingNode()).isFalse();
        assertThat(reply.body().get("paths").propertyNames())
                .contains("/api/v1/analyses/report", "/api/v1/analyses/lint", "/api/v1/analyses/{id}");
        JsonNode saved = reply.body().at("/paths/~1api~1v1~1analyses~1report/post/responses/201/content/application~1json/schema");
        assertThat(saved.get("$ref").asString()).isEqualTo("#/components/schemas/SavedAnalysis");
        assertThat(reply.body().at("/components/schemas/StoredAnalysis/properties/result/type").asString()).isEqualTo("object");
        assertThat(reply.body().at("/components/schemas/SavedAnalysis/properties/result/type").asString()).isEqualTo("object");
        assertThat(reply.body().at("/paths/~1api~1v1~1analyses~1{id}/delete/responses/403").isMissingNode()).isFalse();
    }

    @Test
    void healthIsUp() {
        Reply reply = get("/actuator/health");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("status").asString()).isEqualTo("UP");
    }
}
