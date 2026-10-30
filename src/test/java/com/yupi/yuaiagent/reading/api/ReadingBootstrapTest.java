package com.yupi.yuaiagent.reading.api;

import com.yupi.yuaiagent.reading.book.BookTexts;
import com.yupi.yuaiagent.reading.knowledge.KnowledgeService;
import com.yupi.yuaiagent.reading.knowledge.ReadingRetrieval;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.*;
import org.springframework.mock.web.MockMultipartFile;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "READING_BOOTSTRAP_DATABASE_URL", matches = ".+")
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "reading.enabled=true", "reading.database-url=${READING_BOOTSTRAP_DATABASE_URL}",
        "reading.database-user=${READING_TEST_DATABASE_USER}", "reading.database-password=${READING_TEST_DATABASE_PASSWORD}",
        "spring.ai.mcp.client.enabled=false", "app.rag.enabled=false"})
class ReadingBootstrapTest {
    @MockitoBean
    ChatModel chatModel;
    @MockitoBean
    EmbeddingModel embedding;
    @Autowired
    ReadingStore store;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate readingJdbc;
    @Autowired
    KnowledgeService knowledge;
    @Autowired
    ReadingRetrieval retrieval;
    @Autowired
    MockMvc mvc;
    @Autowired
    BookTexts bookTexts;

    @Test
    void uploadImmediatelySupportsFullTextResearchWithoutMaps() throws Exception {
        when(embedding.embed(anyList(), any(EmbeddingOptions.class), any(BatchingStrategy.class))).thenAnswer(i -> ((List<?>) i.getArgument(0)).stream().map(d -> new float[]{1, 0, 0}).toList());
        String id = (String) knowledge.upload(new MockMultipartFile("file", "原文测试.epub", "application/epub+zip", com.yupi.yuaiagent.reading.EpubFixture.book("<h1>阅读证据</h1><p>必须读取原文。</p>"))).get("id");
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!"READY".equals(store.kb(id).get("status")) && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals("READY", store.kb(id).get("status"));
            var book = bookTexts.load(id);
            assertTrue(book.text().contains("必须读取原文。"));
            verifyNoInteractions(chatModel);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/reading/knowledge-bases/" + id + "/map"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is4xxClientError());
            when(embedding.embed(anyString())).thenReturn(new float[]{1, 0, 0});
            var step = new java.util.concurrent.atomic.AtomicInteger();
            when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(i -> {
                int number = step.getAndIncrement();
                String args = "", tool = "";
                if (number == 0)
                    return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("必须读取原文。[S1]"))));
                if (number == 1) {
                    tool = "selectInterests";
                    args = "{\"interests\":[{\"quote\":\"必须读取原文。\",\"reason\":\"检查依据\",\"question\":\"如何核验\",\"hypothesis\":\"\",\"query\":\"证据\"}]}";
                }
                if (number == 2) {
                    tool = "readPassage";
                    args = "{\"bookId\":\"" + id + "\",\"start\":0,\"end\":" + book.text().length() + ",\"before\":0,\"after\":0}";
                }
                org.springframework.ai.chat.prompt.Prompt prompt = i.getArgument(0);
                if (number == 3 || prompt.getInstructions().stream().anyMatch(m -> Objects.toString(m.getText(), "").contains("findings关联pointId")))
                    return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("{\"findings\":[{\"id\":\"F1\",\"pointId\":\"R1-I1\",\"kind\":\"SUPPORT\",\"statement\":\"原文要求核验\",\"sourceIds\":[\"S2\"]}],\"gaps\":[]}"))));
                var message = number >= 4 ? new org.springframework.ai.chat.messages.AssistantMessage("必须读取原文。[S2]\n<<<RESEARCH_CONTROL>>>\nFINISH") : org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(UUID.randomUUID().toString(), "function", tool, args))).build();
                return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(message)));
            });
            String session = (String) store.createSession().get("id");
            try {
                var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/reading/sessions/" + session + "/messages").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"message\":\"核验阅读证据\",\"settings\":{\"knowledgeBaseIds\":[\"" + id + "\"],\"modeId\":\"collaborative-research\"}}"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
                result.getAsyncResult(5000);
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
                var run = store.history(session).getFirst();
                assertEquals("COMPLETED", run.get("status"));
                assertFalse(run.containsKey("snapshot"));
                assertTrue(store.encode(run.get("events")).contains("lexicalRank"));
            } finally {
                store.deleteSession(session);
            }
        } finally {
            knowledge.delete(id);
        }
    }

    @Test
    void springProxiedRepositorySupportsCompleteUploadAndSearch() throws Exception {
        assertNotNull(readingJdbc);
        when(embedding.embed(anyString())).thenReturn(new float[]{1, 0, 0});
        when(embedding.embed(anyList(), any(EmbeddingOptions.class), any(BatchingStrategy.class))).thenAnswer(i -> ((List<?>) i.getArgument(0)).stream().map(d -> new float[]{1, 0, 0}).toList());
        var file = new MockMultipartFile("file", "Spring上传测试.epub", "application/epub+zip", com.yupi.yuaiagent.reading.EpubFixture.book("<h1>原文</h1><p>理解需要证据。</p>"));
        String id = (String) knowledge.upload(file).get("id");
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            Map<String, Object> kb;
            do {
                kb = store.kb(id);
                if (Set.of("READY", "FAILED").contains(kb.get("status"))) break;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            assertEquals("READY", kb.get("status"), Objects.toString(kb.get("error")));
            assertEquals(1, retrieval.search(List.of(id), "理解").size());
        } finally {
            knowledge.delete(id);
        }
    }

}
