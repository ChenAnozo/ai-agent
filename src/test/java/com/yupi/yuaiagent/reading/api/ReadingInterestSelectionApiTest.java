package com.yupi.yuaiagent.reading.api;

import com.yupi.yuaiagent.reading.knowledge.KnowledgeService;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;
import com.yupi.yuaiagent.reading.research.ModeRegistry;
import com.yupi.yuaiagent.reading.research.ResearchAgent;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReadingInterestSelectionApiTest {
    ResearchAgent agent = mock(ResearchAgent.class);
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ReadingController(mock(ReadingStore.class), mock(KnowledgeService.class), mock(ModeRegistry.class), agent)).build();
    }

    @Test void confirmationRoutesOnlySelectionIdAndTwoDistinctIds() throws Exception {
        mvc.perform(post("/reading/runs/run/interest-selection").contentType(MediaType.APPLICATION_JSON)
                .content("{\"selectionId\":\"choice\",\"selectedIds\":[\"R1-I2\",\"R1-I4\"]}"))
                .andExpect(status().isOk());
        verify(agent).confirmInterests("run", new ReadingTypes.InterestSelectionRequest("choice", List.of("R1-I2", "R1-I4")));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{}", "{\"selectionId\":\"choice\",\"selectedIds\":[\"R1-I1\"]}",
            "{\"selectionId\":\"choice\",\"selectedIds\":[\"R1-I1\",\"R1-I1\"]}"})
    void malformedConfirmationNeverReachesAgent(String body) throws Exception {
        mvc.perform(post("/reading/runs/run/interest-selection").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(agent);
    }

    @Test void staleConfirmationReturnsConflict() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "选择请求已失效"))
                .when(agent).confirmInterests(eq("run"), any());
        mvc.perform(post("/reading/runs/run/interest-selection").contentType(MediaType.APPLICATION_JSON)
                .content("{\"selectionId\":\"old\",\"selectedIds\":[\"R1-I2\",\"R1-I4\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("选择请求已失效"));
    }
}
