package io.github.hanumanthraghavendra.schemaguard.cli;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON writer and reader, so the CLI needs nothing beyond the engine and picocli. The
 * writer turns records into objects with their fields in declaration order, indented by two
 * spaces as {@code JSON.stringify(value, null, 2)} does. The reader turns text into maps, lists,
 * strings, {@link BigDecimal} numbers, booleans and nulls.
 */
final class Json {

    private Json() {}

    /** The value as indented JSON, without a final newline. */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, int depth) {
        switch (value) {
            case null -> out.append("null");
            case String s -> string(s, out);
            case Boolean b -> out.append(b);
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case Double d -> {
                if (d.isNaN() || d.isInfinite()) {
                    throw new IllegalArgumentException("JSON has no " + d);
                }
                out.append(d);
            }
            case BigDecimal d -> out.append(d.toPlainString());
            case List<?> list -> {
                List<Object> items = new ArrayList<>(list);
                container(out, depth, '[', ']', items.size(), (i, o) -> write(items.get(i), o, depth + 1));
            }
            case Map<?, ?> map -> {
                List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
                container(out, depth, '{', '}', entries.size(), (i, o) -> {
                    string(String.valueOf(entries.get(i).getKey()), o);
                    o.append(": ");
                    write(entries.get(i).getValue(), o, depth + 1);
                });
            }
            case Record record -> {
                RecordComponent[] components = record.getClass().getRecordComponents();
                container(out, depth, '{', '}', components.length, (i, o) -> {
                    string(components[i].getName(), o);
                    o.append(": ");
                    write(field(record, components[i]), o, depth + 1);
                });
            }
            default -> throw new IllegalArgumentException("Cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private interface Item {
        void write(int index, StringBuilder out);
    }

    private static void container(StringBuilder out, int depth, char open, char close, int size, Item item) {
        out.append(open);
        if (size == 0) {
            out.append(close);
            return;
        }
        for (int i = 0; i < size; i++) {
            out.append(i == 0 ? "\n" : ",\n").append("  ".repeat(depth + 1));
            item.write(i, out);
        }
        out.append('\n').append("  ".repeat(depth)).append(close);
    }

    private static Object field(Record record, RecordComponent component) {
        try {
            return component.getAccessor().invoke(record);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot read " + component.getName(), e);
        }
    }

    private static void string(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ---------------------------------------------------------------- reading

    /** Thrown for text that is not one JSON value. */
    static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParseException(String message, int position) {
            super(message + " at character " + (position + 1));
        }
    }

    /** Reads one JSON value; text after it, other than white space, is an error. */
    static Object read(String text) {
        Reader reader = new Reader(text);
        Object value = reader.value();
        reader.space();
        if (reader.at < text.length()) {
            throw new ParseException("Unexpected text after the value", reader.at);
        }
        return value;
    }

    private static final class Reader {
        private final String text;
        private int at;

        Reader(String text) {
            this.text = text;
        }

        Object value() {
            space();
            if (at >= text.length()) {
                throw new ParseException("Unexpected end of the text", at);
            }
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> word("true", Boolean.TRUE);
                case 'f' -> word("false", Boolean.FALSE);
                case 'n' -> word("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw new ParseException("Unexpected character '" + c + "'", at);
                }
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            at++;
            space();
            if (peek('}')) {
                at++;
                return map;
            }
            while (true) {
                space();
                if (!peek('"')) {
                    throw new ParseException("Expected a name in quotes", at);
                }
                String key = string();
                space();
                expect(':');
                map.put(key, value());
                space();
                if (peek(',')) {
                    at++;
                } else {
                    expect('}');
                    return map;
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            at++;
            space();
            if (peek(']')) {
                at++;
                return list;
            }
            while (true) {
                list.add(value());
                space();
                if (peek(',')) {
                    at++;
                } else {
                    expect(']');
                    return list;
                }
            }
        }

        private String string() {
            StringBuilder out = new StringBuilder();
            at++;
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw new ParseException("Control character in a string", at - 1);
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (at >= text.length()) {
                    break;
                }
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) {
                            throw new ParseException("Short \\u escape", at);
                        }
                        try {
                            out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        } catch (NumberFormatException e) {
                            throw new ParseException("Bad \\u escape", at);
                        }
                        at += 4;
                    }
                    default -> throw new ParseException("Bad escape \\" + escaped, at - 1);
                }
            }
            throw new ParseException("Unterminated string", at);
        }

        private BigDecimal number() {
            int start = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            String literal = text.substring(start, at);
            if (!literal.matches("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?")) {
                throw new ParseException("Bad number " + literal, start);
            }
            return new BigDecimal(literal);
        }

        private Object word(String word, Object value) {
            if (!text.startsWith(word, at)) {
                throw new ParseException("Unexpected text", at);
            }
            at += word.length();
            return value;
        }

        private boolean peek(char c) {
            return at < text.length() && text.charAt(at) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw new ParseException("Expected '" + c + "'", at);
            }
            at++;
        }

        void space() {
            while (at < text.length() && " \t\n\r".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
        }
    }
}
