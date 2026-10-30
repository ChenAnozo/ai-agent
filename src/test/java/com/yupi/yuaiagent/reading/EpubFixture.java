package com.yupi.yuaiagent.reading;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Small EPUB with a navigation document for upload and database tests. */
public final class EpubFixture {
    private EpubFixture() {}

    public static byte[] book(String html) throws Exception {
        var document = org.jsoup.Jsoup.parse(html);
        var toc = new StringBuilder("<html><body><nav epub:type='toc'><ol>");
        int index = 0;
        for (var heading : document.select("h1,h2,h3,h4,h5,h6")) {
            String id = "heading" + ++index;
            heading.attr("id", id);
            toc.append("<li><a href='chapter.xhtml#").append(id).append("'>").append(heading.text()).append("</a></li>");
        }
        toc.append("</ol></nav></body></html>");
        var entries = Map.of(
                "mimetype", "application/epub+zip",
                "META-INF/container.xml", "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
                "OPS/book.opf", "<package xmlns='http://www.idpf.org/2007/opf'><manifest><item id='chapter' href='chapter.xhtml'/><item id='nav' href='nav.xhtml' properties='nav'/></manifest><spine><itemref idref='chapter'/></spine></package>",
                "OPS/chapter.xhtml", document.outerHtml(), "OPS/nav.xhtml", toc.toString());
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
