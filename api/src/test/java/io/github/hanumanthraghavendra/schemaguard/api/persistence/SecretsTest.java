package io.github.hanumanthraghavendra.schemaguard.api.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecretsTest {

    @Test
    void idsAre22LettersAndDigitsAndDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String id = Secrets.newId();
            assertThat(id).hasSize(22).matches("[0-9A-Za-z]+");
            assertThat(Secrets.isId(id)).isTrue();
            assertThat(seen.add(id)).isTrue();
        }
    }

    @Test
    void onlyTheShapeOfAnIdIsAccepted() {
        assertThat(Secrets.isId(null)).isFalse();
        assertThat(Secrets.isId("short")).isFalse();
        assertThat(Secrets.isId("0123456789012345678901'")).isFalse();
        assertThat(Secrets.isId("012345678901234567890-")).isFalse();
        assertThat(Secrets.isId("0123456789abcdefABCDEF")).isTrue();
    }

    @Test
    void tokensAreLongAndOnlyTheirHashIsKept() {
        String token = Secrets.newToken();
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(Secrets.newToken()).isNotEqualTo(token);
        assertThat(Secrets.hash(token)).hasSize(64).matches("[0-9a-f]+").isEqualTo(Secrets.hash(token)).doesNotContain(token);
        assertThat(Secrets.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
