package com.yupi.yuaiagent.reading.knowledge;

import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AbstractParser;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.epub.EpubParser;
import org.apache.tika.parser.html.JSoupParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Adapts Tika extraction to the text units used by reading and citation offsets. */
@Component
@ConditionalOnProperty(name = "reading.enabled", havingValue = "true")
public class DocumentParsers {
    public static final Set<String> SUPPORTED_FORMATS = Set.of("epub", "pdf", "doc", "docx", "txt", "md");
    private static final int MAX_TEXT_LENGTH = 20_000_000;
    private static final Map<String, Set<String>> MEDIA_TYPES = Map.of(
            "epub", Set.of("application/epub+zip"),
            "pdf", Set.of("application/pdf"),
            "doc", Set.of("application/msword"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "txt", Set.of("text/plain"),
            "md", Set.of("text/plain", "text/markdown", "text/x-web-markdown"));

    public record ParsedDocument(List<String> sections, Map<String, List<String>> metadata) {
        public ParsedDocument {
            sections = List.copyOf(sections);
            metadata = Map.copyOf(metadata);
        }
    }

    public static void requireSupported(String format) {
        if (!SUPPORTED_FORMATS.contains(Objects.toString(format, "")))
            throw new IllegalArgumentException("支持 EPUB、PDF、DOC、DOCX、TXT 和 Markdown");
    }

    public List<String> parse(Path file, String format) throws Exception {
        return parseDocument(file, format).sections();
    }

    public ParsedDocument parseDocument(Path file, String format) throws Exception {
        requireSupported(format);
        if (Files.size(file) == 0 || Files.size(file) > 20 * 1024 * 1024L)
            throw new IllegalArgumentException("文件不能为空或超过 20 MB");
        if (Set.of("epub", "docx").contains(format)) checkArchive(file, format);
        var metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "document." + format);
        var context = new ParseContext();
        // Embedded attachments do not belong in the document's citation coordinate space.
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) { return false; }
            @Override
            public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata, boolean outputHtml) {}
        });
        var pdf = new PDFParserConfig();
        pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        context.set(PDFParserConfig.class, pdf);
        List<String> sections = new ArrayList<>();
        var parser = new AutoDetectParser();
        try (var input = TikaInputStream.get(file)) {
            String detected = parser.getDetector().detect(input, metadata).toString();
            if (!MEDIA_TYPES.get(format).contains(detected))
                throw new IllegalArgumentException("文件内容与扩展名不匹配：" + format + "（" + detected + "）");
            if ("epub".equals(format)) {
                var epub = new EpubParser();
                // JSoupParser normalizes XHTML namespaces and filters non-body content.
                var content = new JSoupParser();
                // Tika follows the OPF spine; collect each content document as one text unit.
                epub.setContentParser(new AbstractParser() {
                    private int length;
                    @Override
                    public Set<MediaType> getSupportedTypes(ParseContext context) {
                        return content.getSupportedTypes(context);
                    }
                    @Override
                    public void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)
                            throws IOException, SAXException, org.apache.tika.exception.TikaException {
                        var body = new BodyContentHandler(MAX_TEXT_LENGTH - length);
                        try (var chapter = TikaInputStream.get(stream)) {
                            content.parse(chapter, body, new Metadata(), context);
                        }
                        String text = body.toString();
                        length += text.length();
                        if (!text.isBlank()) sections.add(text);
                    }
                });
                epub.parse(input, new BodyContentHandler(MAX_TEXT_LENGTH), metadata, context);
            } else {
                var body = new BodyContentHandler(MAX_TEXT_LENGTH);
                parser.parse(input, body, metadata, context);
                if (!body.toString().isBlank()) sections.add(body.toString());
            }
        } catch (Exception e) {
            if (WriteLimitReachedException.isWriteLimitReached(e))
                throw new IllegalArgumentException("提取文本过大", e);
            throw e;
        }
        if (sections.isEmpty()) throw new IllegalArgumentException("pdf".equals(format)
                ? "无法提取有效文本；扫描版 PDF 需要先进行 OCR" : "无法提取有效文本");
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (String name : metadata.names()) values.put(name, List.of(metadata.getValues(name)));
        return new ParsedDocument(sections, values);
    }

    /** Retain upload resource limits without implementing archive document parsing. */
    private static void checkArchive(Path file, String format) throws IOException {
        long total = 0;
        int count = 0;
        try (var zip = new ZipInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[8192];
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++count > 5000) throw new IllegalArgumentException("压缩文档文件数量过多");
                if ("epub".equals(format) && entry.getName().equals("META-INF/encryption.xml"))
                    throw new IllegalArgumentException("不支持含加密资源或 DRM 的 EPUB");
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    total += read;
                    if (total > 100_000_000) throw new IllegalArgumentException("压缩文档解压内容过大");
                }
            }
        }
    }
}
