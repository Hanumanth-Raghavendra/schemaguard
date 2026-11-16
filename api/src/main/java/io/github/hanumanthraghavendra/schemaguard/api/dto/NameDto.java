package io.github.hanumanthraghavendra.schemaguard.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** A schema-qualified name, such as {@code public.users}. */
@Schema(description = "A schema-qualified name of a table or type")
public record NameDto(@NotBlank @Schema(example = "public") String schema, @NotBlank @Schema(example = "users") String name) {}
