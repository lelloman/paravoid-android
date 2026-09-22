package com.lelloman.paravoidandroid.contract;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import static com.lelloman.paravoidandroid.contract.ContractException.Code.*;

/** Bounded JSON subset selected by V1. Integers only; no permissive duplicate-key parser. */
public final class StrictJson {
    private StrictJson() {}
    public static Object parse(byte[] bytes, int maxBytes) throws ContractException {
        if (bytes.length > maxBytes) throw new ContractException(LIMIT_EXCEEDED, "JSON byte limit");
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException error) { throw new ContractException(MALFORMED, "Invalid UTF-8"); }
        Parser parser = new Parser(text, maxBytes);
        Object result = parser.value(0);
        parser.space();
        if (parser.at != text.length()) throw new ContractException(MALFORMED, "Trailing JSON content");
        return result;
    }

    /** RFC 8785 canonical encoding for the selected nonnegative safe-integer schema. */
    public static byte[] canonical(Object value) throws ContractException {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void write(Object value, StringBuilder out, int depth) throws ContractException {
        if (depth > 32) throw new ContractException(LIMIT_EXCEEDED, "JSON nesting limit");
        if (value == null) out.append("null");
        else if (value instanceof Boolean) out.append(value);
        else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long n = ((Number) value).longValue();
            if (n < 0 || n > Protocol.MAX_INTEGER) throw new ContractException(MALFORMED, "Integer range");
            out.append(n);
        } else if (value instanceof String) quote((String) value, out);
        else if (value instanceof List) {
            out.append('['); boolean first = true;
            for (Object item : (List<?>) value) { if (!first) out.append(','); first = false; write(item, out, depth + 1); }
            out.append(']');
        } else if (value instanceof Map) {
            TreeMap<String,Object> sorted = new TreeMap<>();
            for (Map.Entry<?,?> entry : ((Map<?,?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new ContractException(MALFORMED, "JSON key type");
                sorted.put((String) entry.getKey(), entry.getValue());
            }
            out.append('{'); boolean first = true;
            for (Map.Entry<String,Object> entry : sorted.entrySet()) {
                if (!first) out.append(','); first = false;
                quote(entry.getKey(), out); out.append(':'); write(entry.getValue(), out, depth + 1);
            }
            out.append('}');
        } else throw new ContractException(MALFORMED, "Unsupported JSON value type");
    }
    private static void quote(String value, StringBuilder out) throws ContractException {
        unicode(value);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 32) {
                        out.append("\\u00").append(Character.forDigit(c >>> 4, 16)).append(Character.forDigit(c & 15, 16));
                    } else out.append(c);
            }
        }
        out.append('"');
    }
    static void unicode(String text) throws ContractException {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new ContractException(MALFORMED, "Unpaired Unicode surrogate");
            } else if (Character.isLowSurrogate(c)) throw new ContractException(MALFORMED, "Unpaired Unicode surrogate");
        }
    }
    private static final class Parser {
        final String text;
        final int maxString;
        int at;
        Parser(String text, int maxString) { this.text = text; this.maxString = maxString; }
        void space() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
        ContractException bad() { return new ContractException(MALFORMED, "Invalid JSON"); }
        boolean take(char c) { if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
        Object value(int depth) throws ContractException {
            if (depth > 32) throw new ContractException(LIMIT_EXCEEDED, "JSON nesting limit");
            space();
            if (at == text.length()) throw bad();
            char c = text.charAt(at);
            if (c == '"') return string();
            if (take('{')) {
                Map<String,Object> result = new LinkedHashMap<>(); space();
                if (take('}')) return result;
                do {
                    space(); if (at == text.length() || text.charAt(at) != '"') throw bad();
                    String key = string(); space(); if (!take(':') || result.containsKey(key)) throw bad();
                    result.put(key, value(depth + 1)); space();
                    if (take('}')) return result;
                } while (take(','));
                throw bad();
            }
            if (take('[')) {
                List<Object> result = new ArrayList<>(); space(); if (take(']')) return result;
                do { result.add(value(depth + 1)); space(); if (take(']')) return result; } while (take(','));
                throw bad();
            }
            if (text.startsWith("true", at)) { at += 4; return true; }
            if (text.startsWith("false", at)) { at += 5; return false; }
            if (text.startsWith("null", at)) { at += 4; return null; }
            if (c < '0' || c > '9') throw bad();
            int start = at++;
            if (c != '0') while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
            String number = text.substring(start, at);
            if (number.length() > 16) throw bad();
            long n = Long.parseLong(number);
            if (n > Protocol.MAX_INTEGER) throw bad();
            return n;
        }
        String string() throws ContractException {
            at++; StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') { String result = out.toString(); unicode(result); return result; }
                if (c < 32) throw bad();
                if (c == '\\') {
                    if (at == text.length()) throw bad();
                    c = text.charAt(at++);
                    switch (c) {
                        case '"': case '\\': case '/': out.append(c); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'u':
                            if (text.length() - at < 4) throw bad();
                            int n = 0;
                            for (int i = 0; i < 4; i++) {
                                char h = text.charAt(at++);
                                int d = h >= '0' && h <= '9' ? h - '0' : h >= 'a' && h <= 'f' ? h - 'a' + 10 : h >= 'A' && h <= 'F' ? h - 'A' + 10 : -1;
                                if (d < 0) throw bad(); n = (n << 4) | d;
                            }
                            out.append((char)n); break;
                        default: throw bad();
                    }
                } else out.append(c);
                if (out.length() > maxString) throw new ContractException(LIMIT_EXCEEDED, "JSON string limit");
            }
            throw bad();
        }
    }
}
