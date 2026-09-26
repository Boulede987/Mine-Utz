package com.mineutze.worldgen.biomegraph;

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
        int index = 0;
        for (Map.Entry<String, Object> field : fields.entrySet()) {
            appendObjectField(out, field, index++);
        }
        return out.append('}').toString();
    }

    private static void appendObjectField(StringBuilder out, Map.Entry<String, Object> field, int index) {
        if (index > 0) {
            out.append(',');
        }
        out.append(quote(field.getKey())).append(':');
        writeValue(field.getValue(), out);
    }

    public static String writeArray(List<?> items) {
        StringBuilder out = new StringBuilder();
        writeArray(items, out);
        return out.toString();
    }

    private static void writeArray(List<?> items, StringBuilder out) {
        out.append('[');
        for (int i = 0; i < items.size(); i++) {
            appendArrayElement(items, out, i);
        }
        out.append(']');
    }

    private static void appendArrayElement(List<?> items, StringBuilder out, int i) {
        if (i > 0) {
            out.append(',');
        }
        writeValue(items.get(i), out);
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
            appendEscapedChar(out, value.charAt(i));
        }
        return out.append('"').toString();
    }

    private static void appendEscapedChar(StringBuilder out, char c) {
        switch (c) {
            case '"' -> out.append("\\\"");
            case '\\' -> out.append("\\\\");
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            default -> out.append(c);
        }
    }

    // ---- reading ----

    /** @return a {@code Map<String,Object>}, {@code List<Object>}, {@code String}, {@code Double}, {@code Boolean}, or {@code null} - caller knows the expected shape from its own schema. */
    public static Object parse(String json) {
        MinimalJsonParser parser = new MinimalJsonParser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("MinimalJson: trailing content after the top-level value at position " + parser.position());
        }
        return value;
    }
}
