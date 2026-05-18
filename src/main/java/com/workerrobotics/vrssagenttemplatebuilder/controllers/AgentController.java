package com.workerrobotics.vrssagenttemplatebuilder.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final ChatClient chatClient;

    public AgentController(ChatClient.Builder chatClientBuilder,
                           ToolCallbackProvider tools) {
        this.chatClient = chatClientBuilder
                .defaultSystem("Answer all questions with complete sentences.")
                .defaultToolCallbacks(tools)
                .build();
    }

    @PostMapping("/ask")
    public String ask(@RequestBody String question) {
        long start = System.currentTimeMillis();
        String result = chatClient.prompt()
                .user(question)
                .call()
                .entity(String.class);
        log.info("/ask completed in {}ms", System.currentTimeMillis() - start);
        return result;
    }

}
