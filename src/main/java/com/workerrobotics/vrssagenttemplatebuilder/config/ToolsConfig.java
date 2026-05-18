package com.workerrobotics.vrssagenttemplatebuilder.config;

import com.workerrobotics.vrssagenttemplatebuilder.tools.DogFactsTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolsConfig {

    @Bean
    public ToolCallbackProvider toolCallbackProvider(DogFactsTool dogFactsTool) {
        return ToolCallbackProvider.from(ToolCallbacks.from(dogFactsTool));
    }
}
