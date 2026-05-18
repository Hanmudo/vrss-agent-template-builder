package com.workerrobotics.vrssagenttemplatebuilder.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAiChatServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ToolCallbackProvider tools;

    @Mock
    private ChatClient chatClient;

    private OpenAiChatService service;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.defaultSystem(anyString())).thenReturn(chatClientBuilder);
        when(chatClientBuilder.defaultToolCallbacks(any(ToolCallbackProvider[].class))).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        service = new OpenAiChatService(chatClientBuilder, tools, new Semaphore(1));
    }

    @Test
    void streamChatResponse_returnsJsonEncodedContent() {
        stubChatResponse("Dogs are loyal.");

        StepVerifier.create(service.streamChatResponse("tell me about dogs"))
                .expectNext("{\"content\":\"Dogs are loyal.\"}")
                .verifyComplete();
    }

    @Test
    void streamChatResponse_encodesSpecialCharactersInContent() {
        stubChatResponse("He said \"hello\".\nNew line.");

        StepVerifier.create(service.streamChatResponse("test"))
                .expectNext("{\"content\":\"He said \\\"hello\\\".\\nNew line.\"}")
                .verifyComplete();
    }

    @Test
    void streamChatResponse_onNonRetryableError_returnsErrorResponse() {
        stubChatError(new RuntimeException("connection reset"));

        StepVerifier.create(service.streamChatResponse("test"))
                .expectNext("{\"error\":\"Service temporarily unavailable. Please try again later.\"}")
                .verifyComplete();
    }

    @Test
    void streamChatResponse_onWebClientError_logsAndReturnsErrorResponse() {
        var ex = WebClientResponseException.create(400, "Bad Request", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        stubChatError(ex);

        StepVerifier.create(service.streamChatResponse("test"))
                .expectNext("{\"error\":\"Service temporarily unavailable. Please try again later.\"}")
                .verifyComplete();
    }

    @Test
    void streamChatResponse_semaphoreReleasedOnSuccess() {
        stubChatResponse("response");
        var semaphore = new Semaphore(1);
        var localService = new OpenAiChatService(chatClientBuilder, tools, semaphore);

        StepVerifier.create(localService.streamChatResponse("test"))
                .expectNext("{\"content\":\"response\"}")
                .verifyComplete();

        assertEquals(1, semaphore.availablePermits(), "Permit must be released after successful call");
    }

    @Test
    void streamChatResponse_semaphoreReleasedOnError() {
        stubChatError(new RuntimeException("upstream failure"));
        var semaphore = new Semaphore(1);
        var localService = new OpenAiChatService(chatClientBuilder, tools, semaphore);

        StepVerifier.create(localService.streamChatResponse("test"))
                .expectNext("{\"error\":\"Service temporarily unavailable. Please try again later.\"}")
                .verifyComplete();

        assertEquals(1, semaphore.availablePermits(), "Permit must be released even when call errors out");
    }

    @Test
    void streamChatResponse_blocksUntilPermitAvailable() throws InterruptedException {
        stubChatResponse("ok");
        var semaphore = new Semaphore(0);
        var localService = new OpenAiChatService(chatClientBuilder, tools, semaphore);

        var releaseThread = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            semaphore.release();
        });
        releaseThread.start();

        StepVerifier.create(localService.streamChatResponse("test"))
                .expectNext("{\"content\":\"ok\"}")
                .verifyComplete();

        releaseThread.join();
        assertEquals(1, semaphore.availablePermits(), "Permit must be released after blocking acquire completes");
    }

    @Test
    void isRetryable_withTooManyRequests_returnsTrue() {
        var ex = WebClientResponseException.create(429, "Too Many Requests", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        assertTrue(service.isRetryable(ex));
    }

    @Test
    void isRetryable_withServerError_returnsTrue() {
        var ex = WebClientResponseException.create(500, "Internal Server Error", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        assertTrue(service.isRetryable(ex));
    }

    @Test
    void isRetryable_withClientError_returnsFalse() {
        var ex = WebClientResponseException.create(400, "Bad Request", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        assertFalse(service.isRetryable(ex));
    }

    @Test
    void isRetryable_withNonWebClientException_returnsFalse() {
        assertFalse(service.isRetryable(new RuntimeException("unexpected")));
    }

    private void stubChatResponse(String content) {
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(content);
    }

    private void stubChatError(Throwable error) {
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenThrow(error instanceof RuntimeException re ? re : new RuntimeException(error));
    }
}
