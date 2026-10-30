package com.yupi.yuaiagent.reading.model;


import java.util.regex.Pattern;

/**
 * Standardizes citation syntax only; callers still validate every source ID.
 */
public final class CitationFormat {
    public static String normalize(String text) {
        text = text.replaceAll("(?:\\\\)?\\[\\h*(S\\d+)\\h*(?:\\\\)?\\]", "[$1]");
        text = text.replaceAll("[（(](S\\d+)([，,:：][^()（）\\[\\]\\r\\n]*)?[）)]", "[$1$2]");
        return Pattern.compile("\\[(S\\d+)([，,:：][^\\]\\r\\n]+)?\\]").matcher(text).replaceAll(match -> {
            String location = match.group(2);
            if (location == null) return java.util.regex.Matcher.quoteReplacement("[" + match.group(1) + "]");
            String references = Pattern.compile("(?<![A-Za-z0-9])S\\d+(?!\\d)").matcher(location.substring(1).strip()).replaceAll(id -> "[" + id.group() + "]");
            return java.util.regex.Matcher.quoteReplacement("[" + match.group(1) + "]（" + references + "）");
        });
    }
}
