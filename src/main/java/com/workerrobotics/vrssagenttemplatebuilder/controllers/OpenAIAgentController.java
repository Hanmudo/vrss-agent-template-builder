package com.workerrobotics.vrssagenttemplatebuilder.controllers;

import com.workerrobotics.vrssagenttemplatebuilder.service.OpenAiChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class OpenAIAgentController {

    private final OpenAiChatService chatService;

    public OpenAIAgentController(OpenAiChatService chatService) {
        this.chatService = chatService;
    }

    record ChatRequest(String text) {}

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request) {
        return chatService.streamChatResponse(request.text());
    }
}
