package io.github.hanumanthraghavendra.schemaguard.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class JsonTest {

    record Inner(String text, Double maybe) {}

    record Outer(int count, boolean flag, List<Inner> inners, List<String> empty, Map<String, Object> map) {}

    @Test
    void recordsAreWrittenFieldByFieldInOrderAndIndentedLikeJsonStringify() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("long", 5_000_000_000L);
        map.put("decimal", new BigDecimal("1.50"));
        Outer value = new Outer(2, true, List.of(new Inner("a", 0.25), new Inner("b", null)), List.of(), map);
        assertThat(Json.write(value)).isEqualTo("""
                {
                  "count": 2,
                  "flag": true,
                  "inners": [
                    {
                      "text": "a",
                      "maybe": 0.25
                    },
                    {
                      "text": "b",
                      "maybe": null
                    }
                  ],
                  "empty": [],
                  "map": {
                    "long": 5000000000,
                    "decimal": 1.50
                  }
                }""");
        assertThat(Json.write(Map.of())).isEqualTo("{}");
    }

    @Test
    void stringsAreEscapedAndReadBack() {
        String nasty = "quote \" backslash \\ newline \n return \r tab \t bell \u0007 back \b feed \f é ✓";
        String written = Json.write(nasty);
        assertThat(written).isEqualTo("\"quote \\\" backslash \\\\ newline \\n return \\r tab \\t bell \\u0007 back \\b feed \\f é ✓\"");
        assertThat(Json.read(written)).isEqualTo(nasty);
        assertThat(Json.read("\"\\u00e9\\/\"")).isEqualTo("é/");
    }

    @Test
    void readingGivesMapsListsDecimalsBooleansAndNulls() {
        Object value = Json.read(" {\"a\": [1, -2.5e3, true, false, null, {}, []], \"b\": \"x\"} ");
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("a", new ArrayList<>(Arrays.asList(new BigDecimal("1"), new BigDecimal("-2.5e3"), true, false, null, Map.of(), List.of())));
        expected.put("b", "x");
        assertThat(value).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[1,", "{\"a\" 1}", "{a: 1}", "01", "1.", "\"open", "\"bad \\x\"", "\"\\u12\"",
        "\"\\uZZZZ\"", "\"tab\tinside\"", "tru", "[1] 2", "@", "{\"a\":1,}", "\"\\"})
    void textThatIsNotOneJsonValueIsRefused(String text) {
        assertThatThrownBy(() -> Json.read(text)).isInstanceOf(Json.ParseException.class).hasMessageContaining("at character");
    }

    @Test
    void valuesJsonCannotHoldAreRefused() {
        assertThatThrownBy(() -> Json.write(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.write(new Object())).isInstanceOf(IllegalArgumentException.class);
    }
}
