package dev.kairos.core.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny recursive-descent JSON reader. Hand-written on purpose: the project takes no
 * third-party dependencies (AGENTS.md, ADR 0002), and scenario files are small.
 *
 * <p>Pure Java: this package must never import {@code net.minecraft.*} (AGENTS.md rule 1).
 *
 * <p>Mapping: object -> {@link LinkedHashMap} (insertion order preserved), array ->
 * {@link ArrayList}, string -> {@link String}, number -> {@link Double} (JSON has one
 * number type), {@code true}/{@code false} -> {@link Boolean}, {@code null} -> {@code null}.
 */
public final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /**
     * @throws IllegalArgumentException on malformed JSON, with the offset in the message.
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON text must not be null");
        }
        Json p = new Json(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.pos != text.length()) {
            throw p.error("trailing characters after the JSON value");
        }
        return value;
    }

    private Object readValue() {
        if (this.pos >= this.text.length()) {
            throw error("unexpected end of input");
        }
        char c = this.text.charAt(this.pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expectLiteral("true");
                return Boolean.TRUE;
            case 'f':
                expectLiteral("false");
                return Boolean.FALSE;
            case 'n':
                expectLiteral("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        this.pos++; // '{'
        skipWhitespace();
        if (peek() == '}') {
            this.pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected a string key");
            }
            String key = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw error("expected ':' after the key");
            }
            this.pos++;
            skipWhitespace();
            if (map.containsKey(key)) {
                throw error("duplicate key \"" + key + "\"");
            }
            map.put(key, readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                this.pos++;
            } else if (c == '}') {
                this.pos++;
                return map;
            } else {
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<Object>();
        this.pos++; // '['
        skipWhitespace();
        if (peek() == ']') {
            this.pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                this.pos++;
            } else if (c == ']') {
                this.pos++;
                return list;
            } else {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String readString() {
        this.pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (this.pos >= this.text.length()) {
                throw error("unterminated string");
            }
            char c = this.text.charAt(this.pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (this.pos >= this.text.length()) {
                throw error("unterminated escape");
            }
            char e = this.text.charAt(this.pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (this.pos + 4 > this.text.length()) {
                        throw error("truncated \\u escape");
                    }
                    String hex = this.text.substring(this.pos, this.pos + 4);
                    try {
                        sb.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException nfe) {
                        throw error("bad \\u escape \\u" + hex);
                    }
                    this.pos += 4;
                    break;
                default:
                    throw error("bad escape \\" + e);
            }
        }
    }

    private Double readNumber() {
        int start = this.pos;
        if (peek() == '-' || peek() == '+') {
            this.pos++;
        }
        while (this.pos < this.text.length()) {
            char c = this.text.charAt(this.pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                this.pos++;
            } else {
                break;
            }
        }
        String token = this.text.substring(start, this.pos);
        try {
            return Double.valueOf(Double.parseDouble(token));
        } catch (NumberFormatException nfe) {
            throw error("not a number: \"" + token + "\"");
        }
    }

    private void expectLiteral(String literal) {
        if (!this.text.startsWith(literal, this.pos)) {
            throw error("expected " + literal);
        }
        this.pos += literal.length();
    }

    private char peek() {
        if (this.pos >= this.text.length()) {
            throw error("unexpected end of input");
        }
        return this.text.charAt(this.pos);
    }

    private void skipWhitespace() {
        while (this.pos < this.text.length()) {
            char c = this.text.charAt(this.pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                this.pos++;
            } else {
                break;
            }
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("JSON error at offset " + this.pos + ": " + message);
    }
}
