package io.github.hanumanthraghavendra.schemaguard.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void emailsAreTrimmedAndLowercasedSoCaseMakesNoSecondAccount() {
        assertThat(policy.normalizeEmail("  You@Example.COM ")).isEqualTo("you@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "you", "you@", "@example.com", "you@example", "you @example.com", "a@b@example.com"})
    void anythingThatIsNotAnEmailAddressIsRefused(String email) {
        assertThatThrownBy(() -> policy.normalizeEmail(email))
                .isInstanceOf(InvalidAccountDetailsException.class)
                .hasMessage("Enter an email address such as you@example.com.");
    }

    @Test
    void anEmailLongerThanTheStandardAllowsIsRefused() {
        String longest = "a".repeat(242) + "@example.com";
        assertThat(policy.normalizeEmail(longest)).hasSize(254);
        assertThatThrownBy(() -> policy.normalizeEmail("a" + longest)).isInstanceOf(InvalidAccountDetailsException.class);
    }

    @Test
    void passwordsNeedEightCharactersCountedAsPeopleCountThem() {
        assertThatThrownBy(() -> policy.check("seven77", "you@example.com"))
                .isInstanceOf(InvalidAccountDetailsException.class)
                .hasMessageStartingWith("Use at least 8 characters.");
        // Eight characters, but sixteen UTF-16 units: emoji are one character each.
        assertThatCode(() -> policy.check("🐘🐘🐘🐘🔒🔒🔒🔒", "you@example.com")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.check("🐘🐘🐘🐘🔒🔒🔒", "you@example.com")).isInstanceOf(InvalidAccountDetailsException.class);
    }

    @Test
    void passwordsMayBeLongButNotEndless() {
        assertThatCode(() -> policy.check("x".repeat(256), "you@example.com")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.check("x".repeat(257), "you@example.com"))
                .isInstanceOf(InvalidAccountDetailsException.class)
                .hasMessage("Use at most 256 characters.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "Password1", "12345678", "iloveyou", "QWERTYUIOP", "1q2w3e4r"})
    void commonPasswordsAreRefusedWhateverTheirCase(String password) {
        assertThatThrownBy(() -> policy.check(password, "you@example.com"))
                .isInstanceOf(InvalidAccountDetailsException.class)
                .hasMessageContaining("10,000 most common");
    }

    @Test
    void theListHoldsTenThousandPasswordsOfAnAllowedLength() {
        assertThat(policy.commonPasswords()).isEqualTo(10_000);
    }

    @Test
    void theEmailAddressIsNotAPassword() {
        assertThatThrownBy(() -> policy.check("Lakshmi.Rao@example.com", "lakshmi.rao@example.com"))
                .hasMessage("Do not use your email address as your password.");
        assertThatThrownBy(() -> policy.check("lakshmi.rao", "lakshmi.rao@example.com"))
                .hasMessage("Do not use your email address as your password.");
    }

    @Test
    void passphrasesWithSpacesAndAnyScriptAreWelcomeAsTyped() {
        assertThatCode(() -> policy.check("correct horse battery staple", "you@example.com")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("  leading and trailing  ", "you@example.com")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("ఏనుగు తాళం చెవి", "you@example.com")).doesNotThrowAnyException();
    }
}
