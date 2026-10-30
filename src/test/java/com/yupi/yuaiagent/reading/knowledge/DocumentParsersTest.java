package com.yupi.yuaiagent.reading.knowledge;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;
import static com.yupi.yuaiagent.reading.api.ReadingTypes.*;

class DocumentParsersTest {
    @TempDir
    Path directory;
    DocumentParsers parsers = new DocumentParsers();

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "READING_ACCEPTANCE_BOOK", matches = ".+")
    void actualAcceptanceEpubParsesIntoTextUnits() throws Exception {
        var sections = parsers.parse(Path.of(System.getenv("READING_ACCEPTANCE_BOOK")), "epub");
        var chunks = ReadingChunker.split(sections, 1200, 200);
        assertTrue(sections.size() > 5);
        assertTrue(chunks.size() > 20);
        assertTrue(sections.stream().allMatch(s -> !s.isBlank()));
        System.out.println("Acceptance EPUB: " + sections.size() + " chapters, " + chunks.size() + " chunks");
    }

    private Path epub(boolean encrypted, boolean dangerous) throws Exception {
        Path secret = directory.resolve("secret.txt");
        Files.writeString(secret, "PRIVATE_ENTITY_SENTINEL");
        Path path = directory.resolve(UUID.randomUUID() + ".epub");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("mimetype", "application/epub+zip");
        entries.put("META-INF/container.xml", "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>");
        entries.put("OPS/book.opf", (dangerous ? "<!DOCTYPE package [<!ENTITY x SYSTEM '" + secret.toUri() + "'>]>" : "") + "<package xmlns='http://www.idpf.org/2007/opf'>" + (dangerous ? "<metadata><title>&x;</title></metadata>" : "") + "<manifest><item id='a' href='first.xhtml'/><item id='b' href='second.xhtml'/></manifest><spine><itemref idref='b'/><itemref idref='a'/></spine></package>");
        entries.put("OPS/first.xhtml", "<html><body><h1>第一章</h1><p>第一个概念。</p></body></html>");
        entries.put("OPS/second.xhtml", "<html><body><h1>第二章</h1><p>第二个概念。</p></body></html>");
        if (encrypted) entries.put("META-INF/encryption.xml", "<encryption/>");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return path;
    }

    @Test
    void epubFollowsSpineInsteadOfZipEntryOrder() throws Exception {
        var result = parsers.parse(epub(false, false), "epub");
        assertTrue(result.getFirst().contains("第二个概念"));
        assertTrue(result.getFirst().contains("第二章"));
        assertTrue(result.getLast().contains("第一个概念"));
    }

    @Test
    void encryptedAndUnsafeEpubAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> parsers.parse(epub(true, false), "epub"));
        // Tika may reject the XML or ignore its external entity, but must not resolve it.
        Path unsafe = epub(false, true);
        try {
            var parsed = parsers.parseDocument(unsafe, "epub");
            assertFalse(parsed.sections().toString().contains("PRIVATE_ENTITY_SENTINEL"));
            assertFalse(parsed.metadata().toString().contains("PRIVATE_ENTITY_SENTINEL"));
        } catch (org.apache.tika.exception.TikaException | org.xml.sax.SAXException expected) {
            // A hard rejection is also safe.
        }
    }

    @Test
    void emptyAndUnsupportedFormatsFailClearly() throws Exception {
        Path empty = directory.resolve("empty.epub");
        Files.write(empty, com.yupi.yuaiagent.reading.EpubFixture.book("<p>   </p>"));
        assertThrows(IllegalArgumentException.class, () -> parsers.parse(empty, "epub"));
        for (String format : List.of("exe", "zip", "unknown"))
            assertThrows(IllegalArgumentException.class, () -> parsers.parse(empty, format));
    }

    @Test
    void textAndMarkdownKeepUnicodeAndChunkOffsets() throws Exception {
        for (String format : List.of("txt", "md")) {
            Path file = directory.resolve("book." + format);
            Files.writeString(file, "# 第一章\n\n中文正文😀。\n\n第二段。", StandardCharsets.UTF_8);
            var result = parsers.parseDocument(file, format);
            String text = ReadingChunker.fullText(result.sections());
            assertTrue(text.contains("中文正文😀"));
            assertFalse(result.metadata().isEmpty());
            for (var chunk : ReadingChunker.split(result.sections(), 12, 3))
                assertEquals(chunk.text(), text.substring(chunk.start(), chunk.end()));
        }
    }

    @Test
    void docxExtractsBodyAndTitleMetadata() throws Exception {
        Path file = directory.resolve("book.docx");
        try (var word = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            word.getProperties().getCoreProperties().setTitle("文档标题");
            word.createParagraph().createRun().setText("Word 中文正文。");
            try (var out = Files.newOutputStream(file)) { word.write(out); }
        }
        var result = parsers.parseDocument(file, "docx");
        assertTrue(String.join("", result.sections()).contains("Word 中文正文"));
        assertEquals(List.of("文档标题"), result.metadata().get("dc:title"));
    }

    @Test
    void legacyWordExtractsRealBinaryDocument() throws Exception {
        Path file = directory.resolve("book.doc");
        try (var input = getClass().getResourceAsStream("/reading/simple.doc")) {
            assertNotNull(input);
            Files.copy(input, file);
        }
        assertTrue(String.join("", parsers.parse(file, "doc")).contains("This is a simple file"));
    }

    @Test
    void pdfExtractsTextAndTitleAndRejectsImageOnlyDocuments() throws Exception {
        Path file = directory.resolve("book.pdf");
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            pdf.addPage(page);
            pdf.getDocumentInformation().setTitle("PDF title");
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("PDF knowledge text");
                content.endText();
            }
            pdf.save(file.toFile());
        }
        var result = parsers.parseDocument(file, "pdf");
        assertTrue(String.join("", result.sections()).contains("PDF knowledge text"));
        assertEquals(List.of("PDF title"), result.metadata().get("dc:title"));
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            pdf.save(file.toFile());
        }
        assertThrows(IllegalArgumentException.class, () -> parsers.parse(file, "pdf"));
    }

    @Test
    void disguisedAndMalformedDocumentsAreRejected() throws Exception {
        Path file = directory.resolve("fake.pdf");
        Files.writeString(file, "plain text pretending to be PDF");
        assertThrows(IllegalArgumentException.class, () -> parsers.parse(file, "pdf"));
        Files.writeString(file, "%PDF-1.7\nnot a valid PDF");
        assertThrows(Exception.class, () -> parsers.parse(file, "pdf"));
    }

    @Test
    void oversizedArchiveAndExtractedTextAreRejectedWithoutTruncation() throws Exception {
        Path archive = directory.resolve("large.epub");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("large.xhtml"));
            byte[] buffer = new byte[8192];
            for (int i = 0; i < 100_000_001 / buffer.length + 1; i++) zip.write(buffer);
            zip.closeEntry();
        }
        var error = assertThrows(IllegalArgumentException.class, () -> parsers.parse(archive, "epub"));
        assertTrue(error.getMessage().contains("解压内容过大"));
        Path text = directory.resolve("large.txt");
        Files.writeString(text, "a".repeat(20_000_001));
        error = assertThrows(IllegalArgumentException.class, () -> parsers.parse(text, "txt"));
        assertEquals("提取文本过大", error.getMessage());
    }

    @Test
    void chunkOffsetsIdentifyTheirTextUnitAfterNormalizationAndTrimming() {
        var sections = List.of("  第一段\r\n\r\n第二段  ", "  后续😀正文  ");
        var chunks = ReadingChunker.split(sections, 6, 2);
        assertTrue(chunks.stream().anyMatch(c -> c.start() >= ReadingChunker.normalize(sections.getFirst()).length() + 2));
        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            String text = ReadingChunker.fullText(sections);
            assertEquals(chunk.text(), text.substring(chunk.start(), chunk.end()));
            assertEquals(i, chunk.ordinal());
            assertFalse(Character.isLowSurrogate(chunk.text().charAt(0)));
            assertFalse(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length() - 1)));
        }
    }

    @Test
    void chunksCoverLongTextOverlapAndKeepOffsets() {
        String text = "阅读证据。".repeat(900);
        var chunks = ReadingChunker.split(List.of(text), 1200, 200);
        assertTrue(chunks.size() > 3);
        StringBuilder rebuilt = new StringBuilder(chunks.getFirst().text());
        for (int i = 1; i < chunks.size(); i++) {
            var previous = chunks.get(i - 1).text();
            var next = chunks.get(i).text();
            assertEquals(previous.substring(previous.length() - 200), next.substring(0, 200));
            rebuilt.append(next.substring(200));
        }
        assertEquals(text, rebuilt.toString());
        for (var chunk : chunks) {
            assertEquals(chunk.text(), text.substring(chunk.start(), chunk.end()));
            assertTrue(chunk.text().length() <= 1200);
        }
    }
}
