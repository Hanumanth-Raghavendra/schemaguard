package io.github.hanumanthraghavendra.schemaguard.cli;

import static io.github.hanumanthraghavendra.schemaguard.cli.Cli.golden;
import static io.github.hanumanthraghavendra.schemaguard.cli.Cli.run;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.hanumanthraghavendra.schemaguard.cli.Cli.Run;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The CLI's JSON is the API's: checked against {@code web/openapi.json}, the contract the API
 * writes and the web app's types are generated from (D-107). A field added to the API and not
 * here, or the other way round, fails this test.
 */
class ContractTest {

    private static final String REF = "#/components/schemas/";
    private static final List<Class<? extends Record>> RESPONSES = List.of(
            Documents.DiffResponse.class,
            Documents.ReportResponse.class,
            Documents.PlanResponse.class,
            Documents.LintResponse.class);

    private static Map<?, ?> schemas;

    @BeforeAll
    static void readContract() throws IOException {
        Map<?, ?> contract = (Map<?, ?>) Json.read(
                Files.readString(Path.of(System.getProperty("schemaguard.contract")), StandardCharsets.UTF_8));
        schemas = (Map<?, ?>) ((Map<?, ?>) contract.get("components")).get("schemas");
    }

    // ---------------------------------------------------------------- the records

    @Test
    void everyRecordHasTheFieldsTypesAndNullsOfItsSchema() {
        Set<Class<?>> seen = new LinkedHashSet<>();
        RESPONSES.forEach(type -> compare(type, seen));
        assertThat(seen).as("every record the four responses reach was compared").hasSizeGreaterThanOrEqualTo(15);
    }

    private void compare(Class<?> type, Set<Class<?>> seen) {
        if (!seen.add(type)) {
            return;
        }
        Map<?, ?> schema = schema(type.getSimpleName());
        if (type.isInterface()) {
            List<String> permitted = Arrays.stream(type.getPermittedSubclasses()).map(Class::getSimpleName).toList();
            List<String> oneOf = ((List<?>) schema.get("oneOf")).stream().map(s -> name((Map<?, ?>) s)).toList();
            assertThat(permitted).as(type.getSimpleName() + " has the contract's subtypes").containsExactlyInAnyOrderElementsOf(oneOf);
            Arrays.stream(type.getPermittedSubclasses()).forEach(sub -> compare(sub, seen));
            return;
        }
        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
        List<String> fields = Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
        assertThat(fields).as(type.getSimpleName() + " fields").containsExactlyInAnyOrderElementsOf(names(properties.keySet()));
        assertThat(names((List<?>) schema.get("required"))).as(type.getSimpleName() + ": the API sends every field")
                .containsExactlyInAnyOrderElementsOf(fields);
        for (RecordComponent component : type.getRecordComponents()) {
            String where = type.getSimpleName() + "." + component.getName();
            Map<?, ?> property = (Map<?, ?>) properties.get(component.getName());
            assertThat(component.isAnnotationPresent(Documents.Nullable.class))
                    .as(where + " may be null exactly when the contract says so")
                    .isEqualTo(nullable(property));
            matches(component.getGenericType(), property, where, seen);
        }
    }

    private void matches(Type type, Map<?, ?> property, String where, Set<Class<?>> seen) {
        if (type instanceof ParameterizedType list) {
            assertThat(list.getRawType()).as(where).isEqualTo(List.class);
            assertThat(types(property)).as(where).contains("array");
            matches(list.getActualTypeArguments()[0], (Map<?, ?>) property.get("items"), where + "[]", seen);
            return;
        }
        Class<?> java = (Class<?>) type;
        String expected =
                java == int.class ? "integer"
                        : java == double.class || java == Double.class ? "number"
                        : java == boolean.class ? "boolean"
                        : java == String.class ? "string"
                        : null;
        if (expected != null) {
            assertThat(types(property)).as(where).contains(expected);
            return;
        }
        assertThat(ref(property)).as(where + " refers to its record's schema").isEqualTo(java.getSimpleName());
        compare(java, seen);
    }

    // ---------------------------------------------------------------- real output

    @Test
    void realOutputIsValidAgainstTheContract() {
        String before = golden("before_pg_dump_16.sql");
        String after = golden("after_pg_dump_16.sql");
        validate(json("diff", before, after), "DiffResponse");
        validate(json("report", before, after), "ReportResponse");
        validate(json("plan", before, after), "PlanResponse");
        validate(json("plan", "--route", "direct", before, after), "PlanResponse");
        validate(json("lint", golden("before.sql"), golden("migration.sql")), "LintResponse");
    }

    @Test
    void theDiffOffersEachKindOfRenameTheContractKnows() {
        Map<?, ?> diff = (Map<?, ?>) json("diff", golden("before_pg_dump_16.sql"), golden("after_pg_dump_16.sql"));
        Set<Object> kinds = new LinkedHashSet<>();
        for (Object change : (List<?>) diff.get("changes")) {
            if (((Map<?, ?>) change).get("rename") instanceof Map<?, ?> rename) {
                kinds.add(rename.get("kind"));
            }
        }
        assertThat(kinds).as("the evolution fixture has table, column and enum value rename candidates")
                .containsExactlyInAnyOrder("table", "column", "enumValue");
    }

    private static Object json(String... args) {
        String[] withFormat = Arrays.copyOf(args, args.length + 2);
        withFormat[args.length] = "--format";
        withFormat[args.length + 1] = "json";
        Run run = run(withFormat);
        assertThat(run.code()).as(String.join(" ", args)).isZero();
        return Json.read(run.out());
    }

    /** Enough of JSON Schema for this contract: types, null, $ref, anyOf, oneOf with a kind enum, items, objects. */
    private void validate(Object value, String schemaName) {
        validate(value, schema(schemaName), schemaName);
    }

    private void validate(Object value, Map<?, ?> schema, String where) {
        if (schema.get("$ref") instanceof String ref) {
            validate(value, schema(ref.substring(REF.length())), where);
            return;
        }
        if (schema.get("anyOf") instanceof List<?> anyOf) {
            List<AssertionError> errors = new ArrayList<>();
            for (Object option : anyOf) {
                try {
                    validate(value, (Map<?, ?>) option, where);
                    return;
                } catch (AssertionError e) {
                    errors.add(e);
                }
            }
            throw new AssertionError(where + " matches none of anyOf: " + errors);
        }
        if (schema.get("oneOf") instanceof List<?> oneOf) {
            long matching = oneOf.stream().filter(option -> {
                try {
                    validate(value, (Map<?, ?>) option, where);
                    return true;
                } catch (AssertionError e) {
                    return false;
                }
            }).count();
            assertThat(matching).as(where + " matches exactly one of oneOf").isEqualTo(1);
            return;
        }
        List<String> types = types(schema);
        if (value == null) {
            assertThat(types).as(where + " may be null").contains("null");
            return;
        }
        if (schema.get("enum") instanceof List<?> allowed) {
            assertThat(new ArrayList<Object>(allowed)).as(where).contains(value);
        }
        switch (value) {
            case String s -> assertThat(types).as(where).contains("string");
            case Boolean b -> assertThat(types).as(where).contains("boolean");
            case BigDecimal n -> {
                if (types.contains("integer") && !types.contains("number")) {
                    assertThat(n.stripTrailingZeros().scale()).as(where + " is a whole number").isLessThanOrEqualTo(0);
                } else {
                    assertThat(types).as(where).contains("number");
                }
            }
            case List<?> list -> {
                assertThat(types).as(where).contains("array");
                for (int i = 0; i < list.size(); i++) {
                    validate(list.get(i), (Map<?, ?>) schema.get("items"), where + "[" + i + "]");
                }
            }
            case Map<?, ?> object -> {
                Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
                assertThat(names(object.keySet())).as(where + " fields").containsExactlyInAnyOrderElementsOf(names(properties.keySet()));
                for (Map.Entry<?, ?> field : object.entrySet()) {
                    validate(field.getValue(), (Map<?, ?>) properties.get(field.getKey()), where + "." + field.getKey());
                }
            }
            default -> throw new AssertionError(where + ": unexpected " + value.getClass());
        }
    }

    // ---------------------------------------------------------------- reading the contract

    private static Map<?, ?> schema(String name) {
        Object schema = schemas.get(name);
        assertThat(schema).as("the contract has a schema named " + name).isInstanceOf(Map.class);
        return (Map<?, ?>) schema;
    }

    private static boolean nullable(Map<?, ?> property) {
        return types(property).contains("null")
                || (property.get("anyOf") instanceof List<?> anyOf
                        && anyOf.stream().anyMatch(option -> "null".equals(((Map<?, ?>) option).get("type"))));
    }

    /** The schema a property refers to, directly or as the non-null half of an anyOf. */
    private static String ref(Map<?, ?> property) {
        if (property.get("$ref") != null) {
            return name(property);
        }
        if (property.get("anyOf") instanceof List<?> anyOf) {
            for (Object option : anyOf) {
                if (((Map<?, ?>) option).get("$ref") != null) {
                    return name((Map<?, ?>) option);
                }
            }
        }
        return null;
    }

    private static String name(Map<?, ?> reference) {
        return ((String) reference.get("$ref")).substring(REF.length());
    }

    private static List<String> types(Map<?, ?> schema) {
        Object type = schema.get("type");
        if (type instanceof String single) {
            return List.of(single);
        }
        return type instanceof List<?> many ? names(many) : List.of();
    }

    private static List<String> names(java.util.Collection<?> values) {
        return values.stream().map(String::valueOf).toList();
    }
}
