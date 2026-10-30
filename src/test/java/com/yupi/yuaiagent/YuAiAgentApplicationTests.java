package com.yupi.yuaiagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "reading.enabled=false")
class YuAiAgentApplicationTests {
    @Autowired ApplicationContext context;

    @Test
    void readingApplicationDoesNotLoadRemovedAgents() {
        assertFalse(context.containsBean("loveApp"));
        assertFalse(context.containsBean("aiController"));
        assertFalse(context.containsBean("codeController"));
        assertFalse(context.containsBean("planningFlow"));
    }
}
