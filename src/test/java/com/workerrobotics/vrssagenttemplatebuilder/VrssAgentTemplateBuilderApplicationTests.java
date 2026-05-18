package com.workerrobotics.vrssagenttemplatebuilder;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key",
        "spring.ai.anthropic.api-key=test-key"
})
class VrssAgentTemplateBuilderApplicationTests {

    @Test
    void contextLoads() {
    }

}
