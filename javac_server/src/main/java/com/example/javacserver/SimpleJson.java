package com.example.javacserver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Very small JSON parser/serializer for request bodies and responses.
 * Supports the subset of JSON required for this server: objects with string keys,
 * string/boolean/null/number values, and arrays of the same.
 */
final class SimpleJson {
    private SimpleJson() {
    }

    static Object parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.isAtEnd()) {
            throw new IllegalArgumentException("Unexpected trailing content in JSON payload");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String json) {
        Object value = parse(json);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) value;
    }

    static String stringify(Object value) {
        StringBuilder builder = new StringBuilder();
        appendJson(value, builder);
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendJson(Object value, StringBuilder builder) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String) {
            appendString((String) value, builder);
        } else if (value instanceof Number || value instanceof Boolean) {
            builder.append(value.toString());
        } else if (value instanceof Map<?, ?>) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                appendString(entry.getKey(), builder);
                builder.append(':');
                appendJson(entry.getValue(), builder);
                first = false;
            }
            builder.append('}');
        } else if (value instanceof List<?>) {
            builder.append('[');
            boolean first = true;
            for (Object element : (List<?>) value) {
                if (!first) {
                    builder.append(',');
                }
                appendJson(element, builder);
                first = false;
            }
            builder.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported type for JSON serialization: " + value.getClass());
        }
    }

    private static void appendString(String value, StringBuilder builder) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                    break;
            }
        }
        builder.append('"');
    }

    private static final class Parser {
        private final String input;
        private int index;

        Parser(String input) {
            this.input = input;
            this.index = 0;
        }

        Object parseValue() {
            skipWhitespace();
            if (isAtEnd()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char ch = input.charAt(index);
            switch (ch) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                case 'f':
                    return parseBoolean();
                case 'n':
                    return parseNull();
                default:
                    if (isDigit(ch) || ch == '-' || ch == '+') {
                        return parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected character at position " + index + ": " + ch);
            }
        }

        Map<String, Object> parseObject() {
            expect('{');
            skipWhitespace();
            Map<String, Object> result = new HashMap<>();
            if (peek() == '}') {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                char ch = peek();
                if (ch == ',') {
                    index++;
                    continue;
                }
                if (ch == '}') {
                    index++;
                    break;
                }
                throw new IllegalArgumentException("Expected ',' or '}' in object at position " + index);
            }
            return result;
        }

        List<Object> parseArray() {
            expect('[');
            skipWhitespace();
            List<Object> result = new ArrayList<>();
            if (peek() == ']') {
                index++;
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                char ch = peek();
                if (ch == ',') {
                    index++;
                    continue;
                }
                if (ch == ']') {
                    index++;
                    break;
                }
                throw new IllegalArgumentException("Expected ',' or ']' in array at position " + index);
            }
            return result;
        }

        String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (!isAtEnd()) {
                char ch = input.charAt(index++);
                if (ch == '"') {
                    return builder.toString();
                }
                if (ch == '\\') {
                    if (isAtEnd()) {
                        throw new IllegalArgumentException("Invalid escape at end of input");
                    }
                    char esc = input.charAt(index++);
                    switch (esc) {
                        case '"':
                        case '\\':
                        case '/':
                            builder.append(esc);
                            break;
                        case 'b':
                            builder.append('\b');
                            break;
                        case 'f':
                            builder.append('\f');
                            break;
                        case 'n':
                            builder.append('\n');
                            break;
                        case 'r':
                            builder.append('\r');
                            break;
                        case 't':
                            builder.append('\t');
                            break;
                        case 'u':
                            builder.append(parseUnicode());
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid escape sequence: \\" + esc);
                    }
                } else {
                    builder.append(ch);
                }
            }
            throw new IllegalArgumentException("Unterminated string literal");
        }

        private char parseUnicode() {
            if (index + 4 > input.length()) {
                throw new IllegalArgumentException("Incomplete unicode escape sequence");
            }
            int codePoint = 0;
            for (int i = 0; i < 4; i++) {
                char ch = input.charAt(index++);
                int digit = Character.digit(ch, 16);
                if (digit < 0) {
                    throw new IllegalArgumentException("Invalid hex digit in unicode escape: " + ch);
                }
                codePoint = (codePoint << 4) | digit;
            }
            return (char) codePoint;
        }

        Boolean parseBoolean() {
            if (match("true")) {
                return Boolean.TRUE;
            }
            if (match("false")) {
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid boolean literal at position " + index);
        }

        Object parseNull() {
            if (match("null")) {
                return null;
            }
            throw new IllegalArgumentException("Invalid null literal at position " + index);
        }

        Number parseNumber() {
            int start = index;
            if (peek() == '-' || peek() == '+') {
                index++;
            }
            consumeDigits();
            if (!isAtEnd() && peek() == '.') {
                index++;
                consumeDigits();
            }
            if (!isAtEnd() && (peek() == 'e' || peek() == 'E')) {
                index++;
                if (!isAtEnd() && (peek() == '-' || peek() == '+')) {
                    index++;
                }
                consumeDigits();
            }
            String numberStr = input.substring(start, index);
            if (numberStr.indexOf('.') >= 0 || numberStr.indexOf('e') >= 0 || numberStr.indexOf('E') >= 0) {
                return Double.parseDouble(numberStr);
            }
            try {
                return Integer.parseInt(numberStr);
            } catch (NumberFormatException ex) {
                return Long.parseLong(numberStr);
            }
        }

        private void consumeDigits() {
            if (isAtEnd() || !isDigit(peek())) {
                throw new IllegalArgumentException("Invalid number format at position " + index);
            }
            while (!isAtEnd() && isDigit(peek())) {
                index++;
            }
        }

        private boolean isDigit(char ch) {
            return ch >= '0' && ch <= '9';
        }

        private boolean match(String literal) {
            int end = index + literal.length();
            if (end > input.length()) {
                return false;
            }
            if (input.substring(index, end).equals(literal)) {
                index = end;
                return true;
            }
            return false;
        }

        void skipWhitespace() {
            while (!isAtEnd()) {
                char ch = input.charAt(index);
                if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
                    index++;
                } else {
                    break;
                }
            }
        }

        void expect(char ch) {
            if (isAtEnd() || input.charAt(index) != ch) {
                throw new IllegalArgumentException("Expected '" + ch + "' at position " + index);
            }
            index++;
        }

        char peek() {
            if (isAtEnd()) {
                return '\0';
            }
            return input.charAt(index);
        }

        boolean isAtEnd() {
            return index >= input.length();
        }
    }
}
