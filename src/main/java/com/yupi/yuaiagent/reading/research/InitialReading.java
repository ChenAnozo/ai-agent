package com.yupi.yuaiagent.reading.research;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Splits two Markdown documents without interpreting their contents as JSON. */
record InitialReading(String draft, String bookOverview) {
    static final String OVERVIEW_MARKER = "<<<BOOK_OVERVIEW>>>";
    private static final Pattern BOUNDARY = Pattern.compile("(?m)^\\h*" + Pattern.quote(OVERVIEW_MARKER) + "\\h*(?=\\R|\\z)");

    record Parsed(InitialReading reading, List<String> normalizations) { }

    static InitialReading parse(String raw) {
        return parseWithDiagnostics(raw).reading();
    }

    static Parsed parseWithDiagnostics(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("初稿与概览输出为空");
        var normalizations = new ArrayList<String>();
        String text = raw.strip();
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1).strip();
            normalizations.add("移除正文前的BOM");
        }
        if (text.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t'))
            throw new IllegalArgumentException("初稿与概览包含非法控制字符");
        var boundary = BOUNDARY.matcher(text);
        if (!boundary.find()) throw new IllegalArgumentException("初稿与概览缺少独占一行的" + OVERVIEW_MARKER + "分隔符");
        int start = boundary.start(), end = boundary.end();
        if (boundary.find()) throw new IllegalArgumentException("初稿与概览分隔符重复，无法确定两部分边界");
        String draft = text.substring(0, start).strip();
        String overview = text.substring(end).strip();
        if (draft.isBlank() || overview.isBlank()) throw new IllegalArgumentException("初稿与书籍概览均须为非空Markdown正文");
        if (draft.length() > 60000 || overview.length() > 60000)
            throw new IllegalArgumentException("初稿与书籍概览每部分不得超过60000字符");
        return new Parsed(new InitialReading(draft, overview), List.copyOf(normalizations));
    }
}
