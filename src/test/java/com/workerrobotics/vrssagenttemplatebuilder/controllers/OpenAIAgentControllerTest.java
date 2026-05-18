package com.workerrobotics.vrssagenttemplatebuilder.controllers;

import com.workerrobotics.vrssagenttemplatebuilder.service.OpenAiChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAIAgentControllerTest {

    @Mock
    private OpenAiChatService chatService;

    @InjectMocks
    private OpenAIAgentController controller;

    @Test
    void chat_delegatesToChatService() {
        when(chatService.streamChatResponse("Hello")).thenReturn(Flux.just("{\"content\":\"world\"}"));

        StepVerifier.create(controller.chat(new OpenAIAgentController.ChatRequest("Hello")))
                .expectNext("{\"content\":\"world\"}")
                .verifyComplete();
    }

    @Test
    void chatRequest_accessorAndEquality() {
        var req1 = new OpenAIAgentController.ChatRequest("hi");
        var req2 = new OpenAIAgentController.ChatRequest("hi");

        assertEquals("hi", req1.text());
        assertEquals(req1, req2);
        assertEquals(req1.hashCode(), req2.hashCode());
        assertNotNull(req1.toString());
        assertNotEquals(req1, null);
        assertNotEquals(req1, "not a ChatRequest");
    }
}
