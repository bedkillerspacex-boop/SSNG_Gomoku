package com.ssng.gomoku.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonCodec {
    private JsonCodec() {
    }

    public static String stringify(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value);
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String string) {
            builder.append('"').append(escape(string)).append('"');
        } else if (value instanceof Number number) {
            writeNumber(builder, number);
        } else if (value instanceof Boolean) {
            builder.append(value);
        } else if (value instanceof Map<?, ?> map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append('"').append(escape(String.valueOf(entry.getKey()))).append('"').append(':');
                writeValue(builder, entry.getValue());
            }
            builder.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            builder.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeValue(builder, item);
            }
            builder.append(']');
        } else {
            builder.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    public static Object parse(String json) {
        return new Parser(json).parse();
    }

    private static String escape(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static void writeNumber(StringBuilder builder, Number number) {
        if (number instanceof Double d && !Double.isFinite(d)) {
            throw new IllegalArgumentException("non-finite number");
        }
        if (number instanceof Float f && !Float.isFinite(f)) {
            throw new IllegalArgumentException("non-finite number");
        }
        builder.append(number);
    }

    private static final class Parser {
        private final String json;
        private int index;

        Parser(String json) {
            this.json = json == null ? "" : json;
        }

        Object parse() {
            Object value = parseValue();
            skipWhitespace();
            if (index != json.length()) {
                throw error("trailing characters");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= json.length()) {
                throw error("expected value");
            }
            char c = json.charAt(index);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                index++;
                return map;
            }
            while (true) {
                String key = parseString();
                skipWhitespace();
                expect(':');
                map.put(key, parseValue());
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                index++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return list;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < json.length()) {
                char c = json.charAt(index++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c < 0x20) {
                    throw error("unescaped control character");
                }
                if (c != '\\') {
                    builder.append(c);
                    continue;
                }
                if (index >= json.length()) {
                    throw error("unterminated escape");
                }
                char escaped = json.charAt(index++);
                switch (escaped) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (index + 4 > json.length()) {
                            throw error("bad unicode escape");
                        }
                        builder.append((char) Integer.parseInt(json.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> throw error("bad escape");
                }
            }
            throw error("unterminated string");
        }

        private Object parseNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            if (index >= json.length()) {
                throw error("expected digit");
            }
            if (peek('0')) {
                index++;
                if (index < json.length() && Character.isDigit(json.charAt(index))) {
                    throw error("leading zero");
                }
            } else if (isDigitOneToNine(json.charAt(index))) {
                while (index < json.length() && Character.isDigit(json.charAt(index))) {
                    index++;
                }
            } else {
                throw error("expected digit");
            }
            boolean floating = false;
            if (peek('.')) {
                floating = true;
                index++;
                if (index >= json.length() || !Character.isDigit(json.charAt(index))) {
                    throw error("expected fraction digit");
                }
                while (index < json.length() && Character.isDigit(json.charAt(index))) {
                    index++;
                }
            }
            if (peek('e') || peek('E')) {
                floating = true;
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                if (index >= json.length() || !Character.isDigit(json.charAt(index))) {
                    throw error("expected exponent digit");
                }
                while (index < json.length() && Character.isDigit(json.charAt(index))) {
                    index++;
                }
            }
            String raw = json.substring(start, index);
            return floating ? Double.parseDouble(raw) : Long.parseLong(raw);
        }

        private boolean isDigitOneToNine(char c) {
            return c >= '1' && c <= '9';
        }

        private Object parseLiteral(String literal, Object value) {
            if (!json.startsWith(literal, index)) {
                throw error("expected " + literal);
            }
            index += literal.length();
            return value;
        }

        private void skipWhitespace() {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
                index++;
            }
        }

        private boolean peek(char c) {
            return index < json.length() && json.charAt(index) == c;
        }

        private void expect(char c) {
            skipWhitespace();
            if (!peek(c)) {
                throw error("expected " + c);
            }
            index++;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at " + index);
        }
    }
}
