package io.github.hanumanthraghavendra.schemaguard.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.api.config.OpenApiConfig;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The OpenAPI document is the contract with the web app: {@code web/openapi.json} is a copy of it, and the
 * app's TypeScript types are generated from that copy. This test fails when the API changes and the copy
 * does not, so the UI cannot drift from the API. After an intended change, run the build with
 * {@code -Dgolden.update=true}, then {@code npm run gen:api} in {@code web/}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractTest {

    private static final Path COPY = Path.of("..", "web", "openapi.json");

    @Autowired
    private MockMvcTester mvc;

    private final JsonMapper json = JsonMapper.builder().build();

    private ObjectNode document;

    @BeforeEach
    void fetch() throws IOException {
        String body = mvc.get().uri("/v3/api-docs").exchange().getResponse().getContentAsString(StandardCharsets.UTF_8);
        document = (ObjectNode) json.readTree(body);
    }

    @Test
    void webCopyMatchesTheDocument() throws IOException {
        // The server address depends on who asks; the web app uses its own.
        document.remove("servers");
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer =
                new DefaultPrettyPrinter(
                                Separators.createDefaultInstance()
                                        .withObjectNameValueSpacing(Separators.Spacing.AFTER))
                        .withObjectIndenter(indenter)
                        .withArrayIndenter(indenter);
        String expected = json.writer().with(printer).writeValueAsString(document) + "\n";
        if (Boolean.getBoolean("golden.update")) {
            Files.writeString(COPY, expected, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(COPY)).as("%s exists (run with -Dgolden.update=true)", COPY).isTrue();
        String actual = Files.readString(COPY, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(actual)
                .as("web/openapi.json is out of date: run the build with -Dgolden.update=true, then npm run gen:api in web/")
                .isEqualTo(expected);
    }

    @Test
    void everyResponseFieldIsRequiredAndOnlyMarkedFieldsAreNullable() {
        List<String> checked = new ArrayList<>();
        for (Class<?> type : OpenApiConfig.responseRecords()) {
            io.swagger.v3.oas.annotations.media.Schema annotation =
                    type.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
            String name = annotation == null || annotation.name().isEmpty() ? type.getSimpleName() : annotation.name();
            JsonNode schema = document.path("components").path("schemas").path(name);
            assertThat(schema.isMissingNode()).as("schema %s", name).isFalse();
            List<String> required = new ArrayList<>();
            schema.path("required").forEach(node -> required.add(node.asString()));
            for (RecordComponent component : type.getRecordComponents()) {
                String field = component.getName();
                assertThat(required).as("%s.%s is required", name, field).contains(field);
                boolean marked = component.getAnnotatedType().isAnnotationPresent(Nullable.class);
                assertThat(allowsNull(schema.path("properties").path(field)))
                        .as("%s.%s allows null", name, field)
                        .isEqualTo(marked);
            }
            checked.add(name);
        }
        assertThat(checked).contains("DiffResponse", "Finding", "Operation", "PlanResponse", "LintResponse", "SavedAnalysis");
    }

    private static boolean allowsNull(JsonNode property) {
        JsonNode type = property.path("type");
        if (type.isArray()) {
            for (JsonNode each : type) {
                if (each.asString().equals("null")) {
                    return true;
                }
            }
        }
        for (JsonNode option : property.path("anyOf")) {
            if (option.path("type").asString("").equals("null")) {
                return true;
            }
        }
        return false;
    }
}
