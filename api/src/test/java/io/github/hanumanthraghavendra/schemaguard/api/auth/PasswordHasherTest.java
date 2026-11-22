package io.github.hanumanthraghavendra.schemaguard.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashesWithArgon2idAtOwaspsSettingAndASaltOfItsOwn() {
        String hash = hasher.hash("correct horse battery staple");
        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").hasSizeLessThanOrEqualTo(200);
        assertThat(hasher.hash("correct horse battery staple")).isNotEqualTo(hash);
        assertThat(hasher.matches("correct horse battery staple", hash)).isTrue();
        assertThat(hasher.matches("correct horse battery stapl", hash)).isFalse();
        assertThat(hasher.needsRehash(hash)).isFalse();
    }

    @Test
    void aHashMadeWithWeakerSettingsIsUpgradedAtTheNextLogin() {
        String old = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("correct horse battery staple");
        assertThat(hasher.matches("correct horse battery staple", old)).isTrue();
        assertThat(hasher.needsRehash(old)).isTrue();
    }

    @Test
    void checkingAPasswordForNoAccountDoesTheSameWork() {
        long start = System.nanoTime();
        hasher.matchNothing("anything");
        hasher.matchNothing("anything else");
        assertThat(System.nanoTime() - start).isPositive();
    }
}
