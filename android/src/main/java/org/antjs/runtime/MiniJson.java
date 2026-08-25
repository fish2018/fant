package org.antjs.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON parser used for native result payloads and package metadata. */
final class MiniJson {
    private MiniJson() {
    }

    static Object parse(String input) {
        if (input == null) throw new AntRuntime.AntRuntimeException("JSON must not be null");
        Parser parser = new Parser(input);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) throw parser.error("Trailing JSON data");
        return value;
    }

    private static final class Parser {
        private final String input;
        private int offset;

        Parser(String input) {
            this.input = input;
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) throw error("Unexpected end of JSON");
            char ch = input.charAt(offset);
            if (ch == '{') return parseObject();
            if (ch == '[') return parseArray();
            if (ch == '"') return parseString();
            if (ch == 't' && consume("true")) return Boolean.TRUE;
            if (ch == 'f' && consume("false")) return Boolean.FALSE;
            if (ch == 'n' && consume("null")) return null;
            if (ch == '-' || (ch >= '0' && ch <= '9')) return parseNumber();
            throw error("Unexpected JSON token");
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (take('}')) return object;
            while (true) {
                skipWhitespace();
                if (atEnd() || input.charAt(offset) != '"') {
                    throw error("Object key must be a string");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                object.put(key, parseValue());
                skipWhitespace();
                if (take('}')) return object;
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> array = new ArrayList<Object>();
            skipWhitespace();
            if (take(']')) return array;
            while (true) {
                array.add(parseValue());
                skipWhitespace();
                if (take(']')) return array;
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (!atEnd()) {
                char ch = input.charAt(offset++);
                if (ch == '"') return result.toString();
                if (ch < 0x20) throw error("Control character in JSON string");
                if (ch != '\\') {
                    result.append(ch);
                    continue;
                }
                if (atEnd()) throw error("Unterminated JSON escape");
                char escaped = input.charAt(offset++);
                switch (escaped) {
                    case '"': result.append('"'); break;
                    case '\\': result.append('\\'); break;
                    case '/': result.append('/'); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u': result.append(parseHexCodeUnit()); break;
                    default: throw error("Invalid JSON escape");
                }
            }
            throw error("Unterminated JSON string");
        }

        private char parseHexCodeUnit() {
            if (offset + 4 > input.length()) throw error("Incomplete unicode escape");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(input.charAt(offset++), 16);
                if (digit < 0) throw error("Invalid unicode escape");
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Number parseNumber() {
            int start = offset;
            if (take('-')) {
                if (atEnd()) throw error("Invalid number");
            }
            if (take('0')) {
                if (!atEnd() && isDigit(input.charAt(offset))) {
                    throw error("Leading zero in number");
                }
            } else {
                requireDigits();
            }
            boolean real = false;
            if (take('.')) {
                real = true;
                requireDigits();
            }
            if (!atEnd() && (input.charAt(offset) == 'e' || input.charAt(offset) == 'E')) {
                real = true;
                offset++;
                if (!atEnd() && (input.charAt(offset) == '+' || input.charAt(offset) == '-')) offset++;
                requireDigits();
            }
            String text = input.substring(start, offset);
            try {
                if (!real) return Long.valueOf(text);
                return Double.valueOf(text);
            } catch (NumberFormatException ex) {
                throw error("Invalid number");
            }
        }

        private void requireDigits() {
            int start = offset;
            while (!atEnd() && isDigit(input.charAt(offset))) offset++;
            if (start == offset) throw error("Expected a digit");
        }

        private static boolean isDigit(char ch) {
            return ch >= '0' && ch <= '9';
        }

        private boolean consume(String value) {
            if (input.regionMatches(offset, value, 0, value.length())) {
                offset += value.length();
                return true;
            }
            return false;
        }

        private boolean take(char expected) {
            if (!atEnd() && input.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (!take(expected)) throw error("Expected '" + expected + "'");
        }

        private void skipWhitespace() {
            while (!atEnd()) {
                char ch = input.charAt(offset);
                if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') return;
                offset++;
            }
        }

        private boolean atEnd() {
            return offset >= input.length();
        }

        private AntRuntime.AntRuntimeException error(String message) {
            return new AntRuntime.AntRuntimeException(message + " at offset " + offset);
        }
    }
}
