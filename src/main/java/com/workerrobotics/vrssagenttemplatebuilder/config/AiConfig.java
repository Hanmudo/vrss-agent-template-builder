package com.workerrobotics.vrssagenttemplatebuilder.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AiConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    public AnthropicApi anthropicApi(
            @Value("${spring.ai.anthropic.api-key}") String apiKey,
            RestClient.Builder restClientBuilder,
            WebClient.Builder webClientBuilder) {

        return AnthropicApi.builder()
                .apiKey(new SimpleApiKey(apiKey))
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .build();
    }

    @Bean
    public AnthropicChatModel anthropicChatModel(AnthropicApi anthropicApi) {
        var options = AnthropicChatOptions.builder()
                .model("claude-3-opus-20240229")
                .temperature(0.7)
                .build();
        return AnthropicChatModel.builder().anthropicApi(anthropicApi).defaultOptions(options).build();
    }

    @Bean
    public ChatClient.Builder chatClientBuilder(@Qualifier("anthropicChatModel") ChatModel chatModel) {
        // Hier maken we de builder handmatig aan met het beschikbare model
        return ChatClient.builder(chatModel);
    }

    @Bean
    ChatClient chatClient(@Qualifier("chatClientBuilder") ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }
}
