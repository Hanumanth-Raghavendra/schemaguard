package io.github.hanumanthraghavendra.schemaguard.engine.diff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SimilarityTest {

    @Test
    void editDistance() {
        assertThat(Similarity.distance("kitten", "sitting")).isEqualTo(3);
        assertThat(Similarity.distance("", "abc")).isEqualTo(3);
        assertThat(Similarity.distance("abc", "")).isEqualTo(3);
        assertThat(Similarity.distance("same", "same")).isZero();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            textBlock =
                    """
                    email      | email      | 1.0   | same name
                    Email      | email      | 1.0   | names differ only in letter case
                    usr_id     | user_id    | 0.857 | names 86% similar
                    name       | full_name  | 0.8   | one name contains the other
                    user_id    | old_user_id| 0.8   | one name contains the other
                    qty        | quantity   | 0.6   | one name abbreviates the other
                    id         | paid       | 0.5   | names 50% similar
                    qt         | quantity   | 0.25  | names 25% similar
                    qty        | tqy        | 0.333 | names 33% similar
                    """)
    void scoresAndExplains(String a, String b, double value, String explanation) {
        Similarity.Score score = Similarity.of(a, b);
        assertThat(score.value()).isCloseTo(value, org.assertj.core.data.Offset.offset(0.001));
        assertThat(score.explanation()).isEqualTo(explanation);
        assertThat(Similarity.of(b, a).value()).as("symmetric").isEqualTo(score.value());
    }
}
