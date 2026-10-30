package com.yupi.yuaiagent.reading.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.reading.api.ReadingTypes.Settings;
import com.yupi.yuaiagent.reading.config.ReadingProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ResearchTemplatesTest {
    @TempDir Path directory;
    ReadingProperties p;
    ResearchTemplates templates;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() throws Exception {
        p = new ReadingProperties();
        p.setTemplatesDirectory(directory.toString());
        Files.createDirectories(directory.resolve("official"));
        for (String id : List.of(ResearchTemplates.ORIGINAL, ResearchTemplates.DEFAULT))
            Files.copy(Path.of("research-templates/official/" + id + ".json"), directory.resolve("official/" + id + ".json"));
        templates = new ResearchTemplates(p);
    }

    @Test
    void explanationDevelopmentIsDiscoverableReadOnlyAndCopiesWithExistingEngine() throws Exception {
        String id = "explanation-development";
        Files.copy(Path.of("research-templates/official/" + id + ".json"), directory.resolve("official/" + id + ".json"));
        var official = templates.get(id);
        assertTrue(official.official());
        assertEquals("DEVELOPMENT_V4", official.engine());
        assertEquals(ResearchTemplates.DEFAULT, official.baseId());
        assertEquals(3, ((List<?>) templates.list().get("templates")).size());
        assertEquals(ResearchTemplates.DEFAULT, templates.list().get("defaultId"));
        assertEquals(8, official.sections().size());
        assertNotNull(templates.detail(official).get("effective"));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> templates.update(id,
                new ResearchTemplates.Edit(1, "覆盖官方", "", official.sections()))).getStatusCode().value());
        var custom = templates.copy(new ResearchTemplates.Copy(id, official.version(), "解释展开副本"));
        assertEquals(official.sections(), custom.sections());
        assertEquals(official.engine(), custom.engine());
        assertEquals(id, custom.baseId());
        assertFalse(custom.official());
    }

    @Test
    void defaultCopyIsOriginalAndOfficialTemplatesCannotBeEdited() {
        var original = templates.get(ResearchTemplates.ORIGINAL);
        var copy = templates.copy(null);
        assertEquals(original.sections(), copy.sections());
        assertEquals("LEGACY_V2", copy.engine());
        assertFalse(copy.official());
        var forbidden = assertThrows(ResponseStatusException.class, () -> templates.update(original.id(), new ResearchTemplates.Edit(1, "覆盖", "", original.sections())));
        assertEquals(403, forbidden.getStatusCode().value());
        assertEquals(2, ((List<?>) templates.list().get("templates")).stream().filter(t -> Boolean.TRUE.equals(((Map<?, ?>) t).get("official"))).count());
    }

    @Test
    void saveIsVersionedAndConflictingTabKeepsItsOldDraft() {
        var first = templates.copy(new ResearchTemplates.Copy(ResearchTemplates.DEFAULT, null, "我的模板"));
        var changes = new LinkedHashMap<>(first.sections());
        changes.put("integrationGuidance", "只发展最有价值的新联系");
        var edited = templates.update(first.id(), new ResearchTemplates.Edit(1, "新名称", "新说明", changes));
        assertEquals(2, edited.version());
        assertEquals(edited, templates.get(first.id()));
        assertEquals(first, templates.get(first.id(), 1));
        assertEquals(first.sections(), templates.detail(edited).get("initialSections"));
        var conflict = assertThrows(ResponseStatusException.class, () -> templates.update(first.id(), new ResearchTemplates.Edit(1, "旧页面", "", first.sections())));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("新名称", templates.get(first.id()).name());
        assertEquals("只发展最有价值的新联系", changes.get("integrationGuidance"));
    }

    @Test
    void capturedRequestDoesNotChangeWhileAnotherSessionUsesLatestRevision() {
        var first = templates.copy(null);
        var settings = new Settings(List.of(), "collaborative-research", first.id());
        var captured = templates.effective(settings);
        String oldDigest = templates.digest(captured);
        var changes = new LinkedHashMap<>(first.sections());
        changes.put("primaryRole", "新角色");
        templates.update(first.id(), new ResearchTemplates.Edit(1, first.name(), first.description(), changes));
        assertEquals(1, captured.version());
        assertEquals(oldDigest, templates.digest(captured));
        assertEquals(2, templates.effective(settings).version());
        assertNotEquals(oldDigest, templates.digest(templates.effective(settings)));
    }

    @Test
    void archivedOfficialRevisionSurvivesUpgradeWithoutDuplicateListEntries() throws Exception {
        var original = templates.get(ResearchTemplates.DEFAULT);
        templates.preserve(original);
        var next = new ResearchTemplate(original.id(), "升级后的官方模板", original.description(), original.engine(), original.version() + 1, "", 0, true, original.sections());
        json.writeValue(directory.resolve("official/" + original.id() + ".json").toFile(), next);
        assertEquals(original, templates.get(original.id(), original.version()));
        assertEquals(2, ((List<?>) templates.list().get("templates")).size());
        assertEquals(original.version() + 1, templates.get(original.id()).version());
    }

    @Test
    void invalidEditsAndOrphanRevisionsCannotOverwritePublishedContent() throws Exception {
        var first = templates.copy(null);
        var invalid = new LinkedHashMap<>(first.sections());
        invalid.put("primaryRole", " ");
        assertThrows(IllegalArgumentException.class, () -> templates.update(first.id(), new ResearchTemplates.Edit(1, "名称", "", invalid)));
        invalid.put("primaryRole", "字".repeat(16001));
        assertThrows(IllegalArgumentException.class, () -> templates.update(first.id(), new ResearchTemplates.Edit(1, "名称", "", invalid)));
        invalid.replaceAll((k, v) -> "字".repeat(16000));
        assertThrows(IllegalArgumentException.class, () -> templates.update(first.id(), new ResearchTemplates.Edit(1, "名称", "", invalid)));
        Path revisions = directory.resolve("custom/" + first.id() + "/revisions");
        // Block the next immutable write with a directory, rather than relying on OS-specific permissions.
        Files.createDirectory(revisions.resolve("2.json"));
        var next = templates.update(first.id(), new ResearchTemplates.Edit(1, "名称", "", first.sections()));
        assertEquals(3, next.version());
        assertEquals(first, templates.get(first.id(), 1));
        assertThrows(IllegalArgumentException.class, () -> templates.get("../escape"));
        assertThrows(ResponseStatusException.class, () -> templates.get("missing-template"));
    }

    @Test
    void bothProtocolsStayFixedWhileAllEightGuidanceSectionsCanChange() {
        for (String id : List.of(ResearchTemplates.ORIGINAL, ResearchTemplates.DEFAULT)) {
            var source = templates.get(id);
            var sections = new LinkedHashMap<String, String>();
            ResearchTemplates.KEYS.forEach(key -> sections.put(key, "MARKER_" + key));
            var custom = new ResearchTemplate("custom-test", "测试", "", source.engine(), 1, source.id(), 1, false, sections);
            var effective = ResearchPrompts.preview(custom, p);
            @SuppressWarnings("unchecked") var stages = (Map<String, String>) effective.get("stages");
            for (String key : ResearchTemplates.KEYS) assertTrue(stages.get(key).contains("MARKER_" + key));
            assertFalse(stages.get("integrationGuidance").contains("主要认识发展路径"));
            assertTrue(stages.get("primaryRole").contains("不编造引文"));
            assertEquals(!source.legacy(), ResearchPrompts.reportSchema(custom).toString().contains("explanatoryImpact"));
            assertEquals(p.getCollaborationMaxInvestigations(), ((Map<?, ?>) effective.get("constraints")).get("maximumInvestigations"));
            var actionFields = (Map<?, ?>) ResearchPrompts.decisionSchema().get("properties");
            assertEquals(Set.of("decision"), actionFields.keySet());
            assertThrows(IllegalArgumentException.class, () -> ResearchDecision.parse("FINISH"));
        }
    }

    @Test
    void manualInterestModeSurvivesNormalizationAndJsonRoundTrip() throws Exception {
        var manual = new Settings(List.of(), "collaborative-research", "", "MANUAL");
        var normalized = templates.normalize(manual);
        assertEquals("MANUAL", normalized.interestSelectionMode());
        assertEquals(ResearchTemplates.DEFAULT, normalized.researchTemplateId());
        assertEquals(normalized, json.readValue(json.writeValueAsString(normalized), Settings.class));
        assertEquals("AUTO", json.readValue("{}", Settings.class).interestSelectionMode());
        assertThrows(IllegalArgumentException.class, () -> new Settings(List.of(), "", "", "UNKNOWN"));
    }

    @Test
    void oldSettingsDeserializeWithoutTemplateAndOrdinaryChatDoesNotMigrate() throws Exception {
        var old = json.readValue("{\"knowledgeBaseIds\":[],\"modeId\":\"\",\"reviewRuleId\":\"old-rule\"}", Settings.class);
        assertEquals("", old.researchTemplateId());
        assertSame(old, templates.normalize(old));
        assertFalse(json.writeValueAsString(old).contains("reviewRuleId"));
        var settings = templates.normalize(new Settings(List.of(), "collaborative-research", ""));
        assertEquals(ResearchTemplates.DEFAULT, settings.researchTemplateId());
        assertFalse(Files.exists(directory.resolve("custom")), "Selecting the default must not create compatibility templates");
    }
}
