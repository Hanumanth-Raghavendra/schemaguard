package io.github.hanumanthraghavendra.schemaguard.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MigrationsTest {

    @Test
    void flywayVersionsCompareAsNumbersPartByPart() {
        List<String> versions = new ArrayList<>(List.of("1.10", "2", "1.9", "1_2", "10", "1", "20261125093000", "3.0.1"));
        versions.sort(Migrations::versions);
        assertThat(versions).containsExactly("1", "1_2", "1.9", "1.10", "2", "3.0.1", "10", "20261125093000");
        assertThat(Migrations.versions("1.0", "1")).isZero();
        assertThat(Migrations.versions("007", "7")).isZero();
    }

    @Test
    void otherNamesCompareWithDigitRunsAsNumbers() {
        List<String> names = new ArrayList<>(List.of("10_b.sql", "2_a.sql", "002_a.sql", "a.sql", "1_z.sql", "2_b.sql"));
        names.sort(Migrations::natural);
        assertThat(names).containsExactly("1_z.sql", "002_a.sql", "2_a.sql", "2_b.sql", "10_b.sql", "a.sql");
    }
}
