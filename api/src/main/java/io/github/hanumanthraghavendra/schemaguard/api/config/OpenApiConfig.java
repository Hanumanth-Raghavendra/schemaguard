package io.github.hanumanthraghavendra.schemaguard.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The title and description shown at {@code /swagger-ui.html} and in {@code /v3/api-docs}. */
@Configuration
public class OpenApiConfig {

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
                                                + " PostgreSQL DDL, such as the output of pg_dump --schema-only. Nothing sent"
                                                + " is stored.")
                                .license(new License().name("Source").url("https://github.com/Hanumanth-Raghavendra/schemaguard")));
    }
}
