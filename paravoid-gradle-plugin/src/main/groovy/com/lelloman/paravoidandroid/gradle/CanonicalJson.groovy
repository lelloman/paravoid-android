package com.lelloman.paravoidandroid.gradle

import org.gradle.api.GradleException

/** RFC 8785's string/key rules and exact safe integers; no floating-point schema fields. */
final class CanonicalJson {
    static String encode(Object value) {
        if (value == null) return 'null'
        if (value instanceof Boolean) return value.toString()
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger) {
            if (value < 0 || value > 9007199254740991L) throw new GradleException('Canonical JSON integer outside supported range')
            return value.toString()
        }
        if (value instanceof String) return quote(value)
        if (value instanceof List) return '[' + value.collect { encode(it) }.join(',') + ']'
        if (value instanceof Map) {
            if (!value.keySet().every { it instanceof String }) throw new GradleException('Canonical JSON keys must be strings')
            return '{' + value.keySet().sort().collect { quote(it) + ':' + encode(value[it]) }.join(',') + '}'
        }
        throw new GradleException('Unsupported canonical JSON value type: ' + value.getClass().name)
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder('"')
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i)
            if (Character.isHighSurrogate(c)) {
                if (i + 1 == value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) throw new GradleException('Unpaired JSON surrogate')
                out.append(c).append(value.charAt(++i)); continue
            }
            if (Character.isLowSurrogate(c)) throw new GradleException('Unpaired JSON surrogate')
            switch ((int)c) {
                case 34: out.append('\\"'); break
                case 92: out.append('\\\\'); break
                case 8: out.append('\\b'); break
                case 9: out.append('\\t'); break
                case 10: out.append('\\n'); break
                case 12: out.append('\\f'); break
                case 13: out.append('\\r'); break
                default:
                    if (c < 32) out.append(String.format('\\u%04x', (int)c))
                    else out.append(c)
            }
        }
        out.append('"').toString()
    }
}
