package com.workerrobotics.vrssagenttemplatebuilder.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
class OpenAiConfigTest {

    private OpenAiConfig config;

    @Mock
    private OpenAiChatModel openAiChatModel;

    @BeforeEach
    void setUp() {
        config = new OpenAiConfig();
    }

    @Test
    void openAiApi_createsBean() {
        var api = config.openAiApi("test-key", RestClient.builder(), WebClient.builder());
        assertNotNull(api);
    }

    @Test
    void openAiChatModel_createsBean() {
        var api = config.openAiApi("test-key", RestClient.builder(), WebClient.builder());
        assertNotNull(config.openAiChatModel(api));
    }

    @Test
    void openAiChatClientBuilder_createsBean() {
        assertNotNull(config.openAiChatClientBuilder(openAiChatModel));
    }
}