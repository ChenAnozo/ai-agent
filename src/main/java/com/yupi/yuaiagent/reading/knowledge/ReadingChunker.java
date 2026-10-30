package com.yupi.yuaiagent.reading.knowledge;


import java.util.*;

import static com.yupi.yuaiagent.reading.api.ReadingTypes.*;

public final class ReadingChunker {
    private ReadingChunker() {
    }

    public static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replace('\0', ' ').strip();
    }

    public static String fullText(List<String> sections) {
        return String.join("\n\n", sections.stream().map(ReadingChunker::normalize).toList());
    }

    public static List<Chunk> split(List<String> sections, int size, int overlap) {
        if (size <= 0 || overlap < 0 || overlap >= size) throw new IllegalArgumentException("无效切分参数");
        List<Chunk> result = new ArrayList<>();
        int base = 0;
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            String text = normalize(sections.get(sectionIndex));
            for (int start = 0; start < text.length(); ) {
                int end = Math.min(text.length(), start + size);
                if (end < text.length()) {
                    int boundary = text.lastIndexOf("\n\n", end);
                    if (boundary > start + Math.max(size / 2, overlap)) end = boundary;
                    if (end > start && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                }
                int from = start, to = end;
                while (from < to && Character.isWhitespace(text.charAt(from))) from++;
                while (to > from && Character.isWhitespace(text.charAt(to - 1))) to--;
                if (from < to)
                    result.add(new Chunk(text.substring(from, to), base + from, base + to, result.size()));
                if (end == text.length()) break;
                int next = Math.max(start + 1, end - overlap);
                if (Character.isLowSurrogate(text.charAt(next))) next++;
                start = next;
            }
            base += text.length() + 2;
        }
        return result;
    }
}
