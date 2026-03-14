package com.studysync.util;

/**
 * Утилиты для очистки JSON-ответов от LLM-моделей.
 *
 * Модели могут возвращать:
 * - JSON в кодблоках (```json ... ```)
 * - сырые переводы строк внутри JSON-строк
 * - невалидные escape-последовательности (\\l, \\T из LaTeX)
 * - мусорный текст до/после JSON
 */
public final class JsonSanitizer {

    private JsonSanitizer() {}

    /**
     * Полная очистка: убирает кодблоки, экранирует переносы, фиксит escape.
     */
    public static String sanitize(String raw) {
        String s = stripCodeFence(raw);
        s = extractJsonObject(s);
        s = escapeRawNewlinesInsideStrings(s);
        s = fixInvalidJsonEscapes(s);
        return s;
    }

    public static String stripCodeFence(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```(?:json)?\\s*", "");
            s = s.replaceFirst("\\s*```\\s*$", "");
            s = s.trim();
        }
        return s;
    }

    public static String extractJsonObject(String s) {
        if (s == null) return "";
        s = s.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1);
        }
        return s.trim();
    }

    public static String escapeRawNewlinesInsideStrings(String json) {
        if (json == null || json.isEmpty()) return "";

        StringBuilder out = new StringBuilder(json.length() + 64);
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (inString) {
                if (c == '\n' || c == '\r') {
                    out.append("\\n");
                    continue;
                }
                if (escaped) {
                    out.append(c);
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    out.append(c);
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                    out.append(c);
                    continue;
                }
                out.append(c);
            } else {
                out.append(c);
                if (c == '"') inString = true;
            }
        }

        return out.toString();
    }

    public static String fixInvalidJsonEscapes(String s) {
        if (s == null) return "";

        StringBuilder out = new StringBuilder(s.length() + 64);

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }

            if (i + 1 >= s.length()) {
                out.append("\\\\");
                continue;
            }

            char n = s.charAt(i + 1);

            if (n == '"' || n == '\\' || n == '/' || n == 'b' || n == 'f'
                    || n == 'n' || n == 'r' || n == 't') {
                out.append('\\').append(n);
                i++;
                continue;
            }

            if (n == 'u' && i + 5 < s.length()) {
                String hex = s.substring(i + 2, i + 6);
                boolean ok = true;
                for (int k = 0; k < 4; k++) {
                    if (Character.digit(hex.charAt(k), 16) == -1) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    out.append("\\u").append(hex);
                    i += 5;
                    continue;
                }
            }

            out.append("\\\\").append(n);
            i++;
        }

        return out.toString();
    }
}