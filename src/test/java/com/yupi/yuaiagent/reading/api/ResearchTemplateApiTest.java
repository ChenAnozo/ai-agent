package com.yupi.yuaiagent.reading.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;
import com.yupi.yuaiagent.reading.research.ResearchTemplates;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.*;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResearchTemplateApiTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    ResearchTemplates templates;
    ReadingStore store = mock(ReadingStore.class);
    MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        var p = new ReadingProperties(); p.setTemplatesDirectory(directory.toString());
        Files.createDirectories(directory.resolve("official"));
        for (String id : List.of(ResearchTemplates.DEFAULT, ResearchTemplates.ORIGINAL))
            Files.copy(Path.of("research-templates/official/" + id + ".json"), directory.resolve("official/" + id + ".json"));
        templates = new ResearchTemplates(p);
        mvc = MockMvcBuilders.standaloneSetup(new ResearchTemplateController(templates, store)).build();
    }

    @Test
    void listDetailsAndEffectivePreviewUseServerAssembledGuidance() throws Exception {
        mvc.perform(get("/reading/research-templates")).andExpect(status().isOk())
                .andExpect(jsonPath("$.templates", hasSize(2))).andExpect(jsonPath("$.defaultId").value(ResearchTemplates.DEFAULT));
        mvc.perform(get("/reading/research-templates/" + ResearchTemplates.DEFAULT).param("retrievalMode", "BROAD"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.template.sections", aMapWithSize(8)))
                .andExpect(jsonPath("$.effective.constraints.tools", containsString("最多返回3个完整原文分片")));
        when(store.settings("session")).thenReturn(new ReadingTypes.Settings(List.of(), "collaborative-research", ""));
        mvc.perform(get("/reading/sessions/session/research-template"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.template.id").value(ResearchTemplates.DEFAULT));
        mvc.perform(get("/reading/research-templates/missing-template"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message", containsString("不存在")));
    }

    @Test
    void legacyRetrievalParametersAreIgnoredForCopyUpdateAndPreview() throws Exception {
        var response = mvc.perform(post("/reading/research-templates").param("retrievalMode", "BROAD")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.constraints.tools", containsString("readPassage")))
                .andExpect(jsonPath("$.effective.constraints.tools", containsString("最多返回3个")))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).path("template").path("id").asText();
        var copied = templates.get(id);
        String edit = json.writeValueAsString(new ResearchTemplates.Edit(1, "旧参数仍可更新", "说明", copied.sections()));
        mvc.perform(put("/reading/research-templates/" + id).param("retrievalMode", "INVALID")
                        .contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isOk()).andExpect(jsonPath("$.template.version").value(2));
        mvc.perform(post("/reading/research-templates/preview").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("modeId", "collaborative-research", "retrievalMode", "BROAD"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.constraints.tools", containsString("最多返回3个")))
                .andExpect(jsonPath("$.effective.constraints.tools", containsString("readPassage")));
    }

    @Test
    void copyUpdateConflictAndOfficialReadOnlyAreEnforcedByApi() throws Exception {
        var response = mvc.perform(post("/reading/research-templates").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.template.engine").value("LEGACY_V2"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).path("template").path("id").asText();
        var copied = templates.get(id);
        String edit = json.writeValueAsString(new ResearchTemplates.Edit(1, "已修改名称", "说明", copied.sections()));
        mvc.perform(put("/reading/research-templates/" + id).contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isOk()).andExpect(jsonPath("$.template.version").value(2));
        mvc.perform(put("/reading/research-templates/" + id).contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message", containsString("其他页面更新")));
        mvc.perform(put("/reading/research-templates/" + ResearchTemplates.ORIGINAL).contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isForbidden());
        mvc.perform(get("/reading/research-templates/" + id).param("version", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.template.name").value(copied.name()));
    }
}
