package com.workerrobotics.vrssagenttemplatebuilder.config;

import com.workerrobotics.vrssagenttemplatebuilder.service.DogFactsService;
import com.workerrobotics.vrssagenttemplatebuilder.tools.DogFactsTool;
import com.workerrobotics.vrssagenttemplatebuilder.tools.PrintDirectionTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
class ToolsConfigTest {

    @Mock
    private DogFactsService dogFactsService;

    @Test
    void toolCallbackProvider_registersBothTools() {
        var dogFactsTool = new DogFactsTool(dogFactsService);
        var printDirectionTool = new PrintDirectionTool();
        var config = new ToolsConfig();
        var provider = config.toolCallbackProvider(dogFactsTool, printDirectionTool);
        assertNotNull(provider);
        assertNotNull(provider.getToolCallbacks());
        assertEquals(2, provider.getToolCallbacks().length);
    }
}
