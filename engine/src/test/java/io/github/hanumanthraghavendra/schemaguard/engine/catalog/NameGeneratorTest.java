package io.github.hanumanthraghavendra.schemaguard.engine.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The expected names were read from a PostgreSQL 16 server. */
class NameGeneratorTest {

    private static final String LONG_TABLE = "a_table_with_a_really_long_name_that_goes_on_and_on_and_on_1234";
    private static final String LONG_COLUMN = "another_quite_long_column_name_to_push_things_over_the_limit_xx";

    @Test
    void shortNamesAreJoinedWithUnderscores() {
        assertThat(NameGenerator.objectName("orders", null, "pkey")).isEqualTo("orders_pkey");
        assertThat(NameGenerator.objectName("orders", "customer_id", "fkey")).isEqualTo("orders_customer_id_fkey");
    }

    @Test
    void longNamesAreShortenedTheLongerOneFirst() {
        assertThat(LONG_TABLE).hasSize(63);
        assertThat(NameGenerator.objectName(LONG_TABLE, null, "pkey"))
                .isEqualTo("a_table_with_a_really_long_name_that_goes_on_and_on_and_on_pkey")
                .hasSize(63);
        assertThat(NameGenerator.objectName(LONG_TABLE, "short", "key"))
                .isEqualTo("a_table_with_a_really_long_name_that_goes_on_and_on_a_short_key");
        assertThat(NameGenerator.objectName(LONG_TABLE, "short", "check"))
                .isEqualTo("a_table_with_a_really_long_name_that_goes_on_and_on_short_check");
        assertThat(NameGenerator.objectName(LONG_TABLE, LONG_COLUMN, "fkey"))
                .isEqualTo("a_table_with_a_really_long_na_another_quite_long_column_na_fkey");
    }

    @Test
    void aTakenNameGetsANumberAfterTheLabel() {
        Set<String> taken = Set.of("t_a_idx", "t_a_idx1");
        assertThat(NameGenerator.unique("t", "a", "idx", taken::contains)).isEqualTo("t_a_idx2");
        assertThat(NameGenerator.unique("t", "b", "idx", taken::contains)).isEqualTo("t_b_idx");
    }

    @Test
    void columnNamesAreJoinedUntilTheLimitIsReached() {
        assertThat(NameGenerator.joinColumnNames(List.of("a", "b"))).isEqualTo("a_b");
        assertThat(NameGenerator.joinColumnNames(List.of())).isEmpty();
        String forty = "c".repeat(40);
        assertThat(NameGenerator.joinColumnNames(List.of(forty, forty, forty))).hasSize(81);
        assertThat(NameGenerator.joinColumnNames(List.of("x".repeat(70), "y"))).startsWith("x".repeat(63) + "_y");
    }

    @Test
    void repeatedNamesAreNumbered() {
        assertThat(NameGenerator.distinct(List.of("expr", "expr", "abs", "expr", "abs")))
                .containsExactly("expr", "expr1", "abs", "expr2", "abs1");
        String long63 = "n".repeat(63);
        assertThat(NameGenerator.distinct(List.of(long63, long63))).containsExactly(long63, "n".repeat(62) + "1");
    }
}
