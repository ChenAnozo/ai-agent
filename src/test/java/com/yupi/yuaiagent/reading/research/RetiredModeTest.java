package com.yupi.yuaiagent.reading.research;

import com.yupi.yuaiagent.reading.config.ReadingProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class RetiredModeTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"deep-research", "compare-views", "structure-outline", "general-research"})
    void oldSessionAndCustomModeCannotReenterRemovedWorkflow(String modeId) throws Exception {
        Files.writeString(directory.resolve("old.md"), "---\nid: " + modeId + "\nname: old\ndescription: old\n---\nold rules");
        var p = new ReadingProperties();
        p.setSkillsDirectory(directory.toString());
        var registry = new ModeRegistry(p);
        var result = registry.refresh();
        assertEquals(1, ((java.util.List<?>) result.get("errors")).size());
        assertTrue(((java.util.List<?>) result.get("modes")).isEmpty());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> registry.get(modeId)).getMessage().contains("已移除"));
        assertEquals("", registry.get("").id());
    }
}
