package io.github.hanumanthraghavendra.schemaguard.api.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A rename. The diff offers these as candidates; a client sends back the ones a person has
 * confirmed, and the plan then writes {@code RENAME} instead of a drop and an add.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RenameDto.Table.class, name = "table"),
    @JsonSubTypes.Type(value = RenameDto.Column.class, name = "column"),
    @JsonSubTypes.Type(value = RenameDto.EnumValue.class, name = "enumValue")
})
@Schema(
        oneOf = {RenameDto.Table.class, RenameDto.Column.class, RenameDto.EnumValue.class},
        discriminatorProperty = "kind",
        discriminatorMapping = {
            @DiscriminatorMapping(value = "table", schema = RenameDto.Table.class),
            @DiscriminatorMapping(value = "column", schema = RenameDto.Column.class),
            @DiscriminatorMapping(value = "enumValue", schema = RenameDto.EnumValue.class)
        })
public sealed interface RenameDto {

    @Schema(name = "TableRename")
    record Table(@NotNull @Valid NameDto from, @NotNull @Valid NameDto to) implements RenameDto {}

    @Schema(name = "ColumnRename")
    record Column(@NotNull @Valid NameDto table, @NotBlank String from, @NotBlank String to) implements RenameDto {}

    @Schema(name = "EnumValueRename")
    record EnumValue(@NotNull @Valid NameDto type, @NotBlank String from, @NotBlank String to) implements RenameDto {}
}
