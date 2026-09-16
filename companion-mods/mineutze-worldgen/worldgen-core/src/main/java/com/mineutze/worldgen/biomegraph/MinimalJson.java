package com.mineutze.worldgen.biomegraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled JSON read/write - {@code worldgen-core} has zero external dependencies by
 * design (see build.gradle's own doc), and {@link BiomeGraphJson}'s whole schema (flat
 * records of strings/ints) doesn't warrant pulling one in just for this. Not a general-
 * purpose JSON library - only what {@link BiomeGraphJson} and the graph editor's own
 * request/response bodies (see {@code com.mineutze.worldgen.tooling.graphedit.GraphEditorServer})
 * need: objects, arrays, strings (with the handful of escapes JSON requires), numbers,
 * booleans, null.
 *
 * <p>Public (moved here from {@code tooling.graphedit} 2026-09-15, real user request to
 * wire the graph editor's own data into production): {@link ModBiomeGraph#OVERWORLD} now
 * loads through this same class, so it can no longer be tooling-package-private - see
 * {@link BiomeGraphJson}'s own doc for why the whole serialization layer moved here
 * instead of {@code ModBiomeGraph} reaching INTO the tooling package (wrong dependency
 * direction - production code shouldn't depend on a dev tool).
 */
public final class MinimalJson {

    private MinimalJson() {
    }

    // ---- writing ----

    public static String writeObject(Map<String, Object> fields) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> field : fields.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(quote(field.getKey())).append(':');
            writeValue(field.getValue(), out);
        }
        return out.append('}').toString();
    }

    public static String writeArray(List<?> items) {
        StringBuilder out = new StringBuilder();
        writeArray(items, out);
        return out.toString();
    }

    private static void writeArray(List<?> items, StringBuilder out) {
        out.append('[');
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            writeValue(items.get(i), out);
        }
        out.append(']');
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String stringValue) {
            out.append(quote(stringValue));
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> mapValue) {
            out.append(writeObject((Map<String, Object>) mapValue));
        } else if (value instanceof List<?> listValue) {
            writeArray(listValue, out);
        } else {
            throw new IllegalArgumentException("MinimalJson: don't know how to write a " + value.getClass());
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    // ---- reading ----

    /** @return a {@code Map<String,Object>}, {@code List<Object>}, {@code String}, {@code Double}, {@code Boolean}, or {@code null} - caller knows the expected shape from its own schema. */
    public static Object parse(String json) {
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("MinimalJson: trailing content after the top-level value at position " + parser.position);
        }
        return value;
    }

    private static final class Parser {
        private final String json;
        private int position;

        private Parser(String json) {
            this.json = json;
        }

        boolean atEnd() {
            return position >= json.length();
        }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(json.charAt(position))) {
                position++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            char c = json.charAt(position);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            Map<String, Object> result = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                result.put(key, parseValue());
                skipWhitespace();
                char next = json.charAt(position++);
                if (next == '}') {
                    return result;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("MinimalJson: expected ',' or '}' at position " + (position - 1));
                }
            }
        }

        List<Object> parseArray() {
            List<Object> result = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                char next = json.charAt(position++);
                if (next == ']') {
                    return result;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("MinimalJson: expected ',' or ']' at position " + (position - 1));
                }
            }
        }

        String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = json.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    char escaped = json.charAt(position++);
                    out.append(switch (escaped) {
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'u' -> unicodeEscape();
                        default -> throw new IllegalArgumentException("MinimalJson: unknown escape \\" + escaped + " at position " + (position - 1));
                    });
                } else {
                    out.append(c);
                }
            }
        }

        char unicodeEscape() {
            char value = (char) Integer.parseInt(json.substring(position, position + 4), 16);
            position += 4;
            return value;
        }

        Boolean parseBoolean() {
            if (json.startsWith("true", position)) {
                position += 4;
                return Boolean.TRUE;
            }
            if (json.startsWith("false", position)) {
                position += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("MinimalJson: expected true/false at position " + position);
        }

        Object parseNull() {
            if (json.startsWith("null", position)) {
                position += 4;
                return null;
            }
            throw new IllegalArgumentException("MinimalJson: expected null at position " + position);
        }

        Double parseNumber() {
            int start = position;
            while (!atEnd() && "+-0123456789.eE".indexOf(json.charAt(position)) >= 0) {
                position++;
            }
            if (position == start) {
                throw new IllegalArgumentException("MinimalJson: expected a value at position " + position);
            }
            return Double.parseDouble(json.substring(start, position));
        }

        char peek() {
            return json.charAt(position);
        }

        void expect(char expected) {
            char actual = json.charAt(position++);
            if (actual != expected) {
                throw new IllegalArgumentException("MinimalJson: expected '" + expected + "' but found '" + actual + "' at position " + (position - 1));
            }
        }
    }
}
