package com.yupi.yuaiagent.reading.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.yupi.yuaiagent.reading.knowledge.ReadingRetrieval;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;
import com.yupi.yuaiagent.reading.persistence.mapper.ReadingKbEntity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ReadingEmbeddingConfigurationTest {
    private StandardEnvironment environment(Map<String, Object> overrides) throws Exception {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove("systemEnvironment");
        environment.getPropertySources().remove("systemProperties");
        for (var source : new YamlPropertySourceLoader().load("reading", new ClassPathResource("application-reading.yml")))
            environment.getPropertySources().addLast(source);
        environment.getPropertySources().addFirst(new MapPropertySource("test", overrides));
        return environment;
    }

    @Test
    void configuredModelAndDimensionsReachDashScopeRequest() throws Exception {
        var environment = environment(Map.of());
        var options = Binder.get(environment).bind("spring.ai.dashscope.embedding.options", DashScopeEmbeddingOptions.class).get();
        assertEquals("qwen3.7-text-embedding", options.getModel());
        assertEquals(1024, options.getDimensions());
        assertEquals(new ReadingProperties().getEmbeddingModelId(), environment.getProperty("reading.embedding-model-id"));
        var rest = RestClient.builder();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("https://embedding.example/api/v1/services/embeddings/text-embedding/text-embedding"))
                .andExpect(jsonPath("$.model").value(options.getModel()))
                .andExpect(jsonPath("$.parameters.dimension").value(1024))
                .andExpect(jsonPath("$.input.texts[0]").value("检索问题"))
                .andRespond(withSuccess("{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":[1.0,0.0]}]},\"usage\":{\"total_tokens\":2},\"request_id\":\"test\"}", MediaType.APPLICATION_JSON));
        var api = DashScopeApi.builder().apiKey("test-key").baseUrl("https://embedding.example")
                .restClientBuilder(rest).build();
        var model = DashScopeEmbeddingModel.builder().dashScopeApi(api).defaultOptions(options).build();
        assertArrayEquals(new float[]{1, 0}, model.embed("检索问题"));
        server.verify();
    }

    @Test
    void modelOverrideAlsoChangesKnowledgeBaseIdentity() throws Exception {
        var environment = environment(Map.of("READING_EMBEDDING_MODEL", "text-embedding-v4"));
        assertEquals("text-embedding-v4", environment.getProperty("spring.ai.dashscope.embedding.options.model"));
        assertEquals("dashscope/text-embedding-v4", environment.getProperty("reading.embedding-model-id"));
    }

    @Test
    void previousModelVectorsAreRejectedEvenWithTheSameDimensions() {
        var store = mock(ReadingStore.class);
        var embedding = mock(org.springframework.ai.embedding.EmbeddingModel.class);
        var kb = new ReadingKbEntity();
        kb.setStatus("READY");
        kb.setName("旧知识库");
        kb.setModelId("dashscope/text-embedding-v3");
        kb.setDimensions(1024);
        when(store.knowledgeBase("book")).thenReturn(kb);
        var retrieval = new ReadingRetrieval(store, new ReadingProperties(), embedding);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> retrieval.search(List.of("book"), "问题"))
                .getMessage().contains("请重建知识库"));
        verifyNoInteractions(embedding);
    }
}
