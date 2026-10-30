package com.yupi.yuaiagent.reading.research;

import com.yupi.yuaiagent.reading.api.ReadingTypes;
import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.net.*;

import static org.junit.jupiter.api.Assertions.*;

class ModeRegistryHistoryTest {
    @TempDir
    Path directory;

    @Test
    void modesReloadAndReportInvalidAndDuplicateFiles() throws Exception {
        String text = "---\nid: collaborative-research\nname: 深入研究\ndescription: 核实证据\n---\n第一次指引";
        Path file = directory.resolve("a.md");
        Files.writeString(file, text);
        Files.writeString(directory.resolve("bad.md"), "缺少元数据");
        var p = new ReadingProperties();
        p.setSkillsDirectory(directory.toString());
        var registry = new ModeRegistry(p);
        registry.refresh();
        var original = registry.get("collaborative-research");
        assertEquals("第一次指引", original.content());
        Files.writeString(file, text.replace("第一次", "第二次"));
        registry.refresh();
        assertEquals("第二次指引", registry.get("collaborative-research").content());
        assertEquals("第一次指引", original.content());
        Files.writeString(directory.resolve("b.md"), text);
        var result = registry.refresh();
        assertEquals(2, ((java.util.List<?>) result.get("errors")).size());
        assertThrows(IllegalArgumentException.class, () -> ModeRegistry.parse(text.replace("id: collaborative-research", "id: collaborative-research\nid: duplicate")));
    }

    @Test
    void invalidCitationNumbersAreRemovedAndSnapshotsRemainReadable() {
        var store = org.mockito.Mockito.mock(ReadingStore.class);
        var run = new ResearchRun("run", "chat", new ReadingTypes.Settings(java.util.List.of(), "", false), new ReadingTypes.Mode("", "", "", ""), store, new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(), new ReadingProperties());
        run.source(new ReadingTypes.Source("chunk", "BOOK", "已删除书籍", "第二章", "", "历史原文"));
        assertEquals("引用[S1]和（无效来源编号，已移除）", run.validateCitations("引用[S1]和[S9]"));
        assertEquals("历史原文", run.sources.getFirst().text());
    }
}
