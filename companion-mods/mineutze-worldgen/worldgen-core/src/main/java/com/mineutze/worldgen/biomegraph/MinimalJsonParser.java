package com.mineutze.worldgen.biomegraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link MinimalJson#parse}'s recursive-descent reader over one JSON string; a fresh instance per call, holding just its position in that string. */
final class MinimalJsonParser {

    private final String json;
    private int position;

    MinimalJsonParser(String json) {
        this.json = json;
    }

    boolean atEnd() {
        return position >= json.length();
    }

    int position() {
        return position;
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

    private Map<String, Object> parseObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return result;
        }
        while (true) {
            parseObjectEntry(result);
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

    private void parseObjectEntry(Map<String, Object> result) {
        skipWhitespace();
        String key = parseString();
        skipWhitespace();
        expect(':');
        result.put(key, parseValue());
    }

    private List<Object> parseArray() {
        List<Object> result = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return result;
        }
        while (true) {
            result.add(parseArrayElement());
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

    private Object parseArrayElement() {
        return parseValue();
    }

    private String parseString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        boolean closed = false;
        while (!closed) {
            closed = parseStringChar(out);
        }
        return out.toString();
    }

    /** Reads one string character (following any escape) and appends it to {@code out}; returns true once the closing quote is consumed, appending nothing for it. */
    private boolean parseStringChar(StringBuilder out) {
        char c = json.charAt(position++);
        if (c == '"') {
            return true;
        }
        if (c == '\\') {
            out.append(parseEscape());
        } else {
            out.append(c);
        }
        return false;
    }

    private char parseEscape() {
        char escaped = json.charAt(position++);
        return switch (escaped) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> unicodeEscape();
            default -> throw new IllegalArgumentException("MinimalJson: unknown escape \\" + escaped + " at position " + (position - 1));
        };
    }

    private char unicodeEscape() {
        char value = (char) Integer.parseInt(json.substring(position, position + 4), 16);
        position += 4;
        return value;
    }

    private Boolean parseBoolean() {
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

    private Object parseNull() {
        if (json.startsWith("null", position)) {
            position += 4;
            return null;
        }
        throw new IllegalArgumentException("MinimalJson: expected null at position " + position);
    }

    private Double parseNumber() {
        int start = position;
        while (!atEnd() && "+-0123456789.eE".indexOf(json.charAt(position)) >= 0) {
            position++;
        }
        if (position == start) {
            throw new IllegalArgumentException("MinimalJson: expected a value at position " + position);
        }
        return Double.parseDouble(json.substring(start, position));
    }

    private char peek() {
        return json.charAt(position);
    }

    private void expect(char expected) {
        char actual = json.charAt(position++);
        if (actual != expected) {
            throw new IllegalArgumentException("MinimalJson: expected '" + expected + "' but found '" + actual + "' at position " + (position - 1));
        }
    }
}
