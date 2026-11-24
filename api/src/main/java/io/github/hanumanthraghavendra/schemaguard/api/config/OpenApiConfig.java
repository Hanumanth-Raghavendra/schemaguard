package io.github.hanumanthraghavendra.schemaguard.api.config;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.hanumanthraghavendra.schemaguard.api.auth.RefreshCookie;
import io.github.hanumanthraghavendra.schemaguard.api.auth.SecurityConfig;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Accounts;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Permalinks;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Projects;
import io.github.hanumanthraghavendra.schemaguard.api.dto.RenameDto;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Responses;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The title and description shown at {@code /swagger-ui.html} and in {@code /v3/api-docs}, and an exact
 * contract for the responses.
 *
 * <p>The web app generates its TypeScript types from {@code /v3/api-docs}, so the document must say what
 * the server really sends. Jackson writes every field of a response record, nulls included, so every field
 * is required; a field that can be null is marked {@link Nullable} in the record and becomes
 * {@code type: [..., "null"]} here (D-074).
 */
@Configuration
public class OpenApiConfig {

    /** Every record the API returns. Requests are left alone: their optional fields are really optional. */
    public static List<Class<?>> responseRecords() {
        return Stream.of(Responses.class, Permalinks.class, Accounts.class, Projects.class)
                .flatMap(holder -> Arrays.stream(holder.getDeclaredClasses()))
                .filter(Class::isRecord)
                .toList();
    }

    @Bean
    OpenAPI schemaGuardOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("SchemaGuard API")
                                .version("v1")
                                .description(
                                        "Checks PostgreSQL schema changes before they reach production: what changed, what"
                                                + " each change locks or breaks, and how to apply it safely. All inputs are"
                                                + " PostgreSQL DDL, such as the output of pg_dump --schema-only. The analysis"
                                                + " endpoints store nothing; the /api/v1/analyses endpoints save the request and"
                                                + " its result until they expire, so they can be shared by link. Accounts"
                                                + " (/api/v1/auth, /api/v1/account) and projects (/api/v1/projects) need the"
                                                + " server's database.")
                                .license(
                                        new License()
                                                .name("MIT")
                                                .url("https://github.com/Hanumanth-Raghavendra/schemaguard/blob/main/LICENSE")))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        SecurityConfig.BEARER,
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description("The accessToken of a session (sign-up, login or refresh)"))
                                .addSecuritySchemes(
                                        SecurityConfig.REFRESH_COOKIE,
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.COOKIE)
                                                .name(RefreshCookie.NAME)
                                                .description("The httpOnly refresh cookie set by sign-up, login and refresh")));
    }

    @Bean
    OpenApiCustomizer responsesAreExact() {
        return openApi -> {
            var schemas = openApi.getComponents().getSchemas();
            for (Class<?> type : responseRecords()) {
                makeExact(schemas, type);
            }
            flattenSubtypes(schemas, RenameDto.class);
        };
    }

    /**
     * springdoc writes each subtype of a sealed type as {@code allOf: [parent, own fields]} while the parent
     * lists the subtypes in {@code oneOf}: a cycle that generated clients turn into {@code any}. Each subtype
     * becomes a plain object with its discriminator value as a constant, and the parent a pure
     * {@code oneOf}, so TypeScript sees a discriminated union.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void flattenSubtypes(Map<String, Schema> schemas, Class<?> sealedType) {
        JsonTypeInfo typeInfo = sealedType.getAnnotation(JsonTypeInfo.class);
        JsonSubTypes subTypes = sealedType.getAnnotation(JsonSubTypes.class);
        Schema parent = schemas.get(schemaName(sealedType));
        if (typeInfo == null || subTypes == null || parent == null) {
            throw new IllegalStateException("Not a documented sealed type: " + sealedType.getName());
        }
        String discriminator = typeInfo.property();
        for (JsonSubTypes.Type subType : subTypes.value()) {
            String name = schemaName(subType.value());
            Schema composed = schemas.get(name);
            List<Schema> parts = composed == null ? null : composed.getAllOf();
            if (parts == null || parts.size() != 2 || parts.get(1).getProperties() == null) {
                throw new IllegalStateException("Unexpected shape for " + name + ": " + composed);
            }
            Map<String, Schema> properties = new LinkedHashMap<>();
            Schema constant = new JsonSchema().types(Set.of("string"));
            constant.addEnumItemObject(subType.name());
            properties.put(discriminator, constant);
            properties.putAll(parts.get(1).getProperties());
            List<String> required = new ArrayList<>(List.of(discriminator));
            if (composed.getRequired() != null) {
                required.addAll(composed.getRequired());
            }
            Schema flat = new JsonSchema().types(Set.of("object")).properties(properties);
            flat.setRequired(required);
            if (composed.getDescription() != null) {
                flat.setDescription(composed.getDescription());
            }
            schemas.put(name, flat);
        }
        parent.setProperties(null);
        parent.setRequired(null);
    }

    // swagger-models exposes Schema as a raw type, so its maps and setters cannot be used without these.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void makeExact(Map<String, Schema> schemas, Class<?> type) {
        String name = schemaName(type);
        Schema schema = schemas.get(name);
        if (schema == null || schema.getProperties() == null) {
            throw new IllegalStateException("No schema named " + name + " for " + type.getName());
        }
        Map<String, Schema> properties = schema.getProperties();
        List<String> required = schema.getRequired() == null ? new ArrayList<>() : new ArrayList<>(schema.getRequired());
        for (RecordComponent component : type.getRecordComponents()) {
            String field = component.getName();
            Schema property = properties.get(field);
            if (property == null) {
                throw new IllegalStateException(name + " has no property " + field);
            }
            if (!required.contains(field)) {
                required.add(field);
            }
            if (component.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                properties.put(field, nullable(property));
            }
        }
        schema.setRequired(required);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema nullable(Schema property) {
        if (property.get$ref() != null) {
            String description = property.getDescription();
            property.setDescription(null);
            Schema wrapper = new JsonSchema().anyOf(List.of(property, new JsonSchema().types(Set.of("null"))));
            return description == null ? wrapper : wrapper.description(description);
        }
        Set<String> types = new LinkedHashSet<>();
        if (property.getTypes() != null) {
            types.addAll(property.getTypes());
        } else if (property.getType() != null) {
            types.add(property.getType());
        }
        if (types.isEmpty()) {
            throw new IllegalStateException("Cannot make an untyped property nullable: " + property);
        }
        types.add("null");
        return property.types(types);
    }

    private static String schemaName(Class<?> type) {
        io.swagger.v3.oas.annotations.media.Schema annotation =
                type.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
        return annotation == null || annotation.name().isEmpty() ? type.getSimpleName() : annotation.name();
    }
}
