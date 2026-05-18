package com.workerrobotics.vrssagenttemplatebuilder.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.time.Duration;
import java.util.concurrent.Semaphore;

@Service
public class OpenAiChatService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatService.class);
    private static final String ERROR_RESPONSE = "{\"error\":\"Service temporarily unavailable. Please try again later.\"}";

    private final ChatClient chatClient;
    private final Semaphore rateLimiter;

    public OpenAiChatService(
            @Qualifier("openAiChatClientBuilder") ChatClient.Builder chatClientBuilder,
            ToolCallbackProvider tools,
            @Qualifier("openAiRateLimiter") Semaphore rateLimiter) {
        this.chatClient = chatClientBuilder
                .defaultSystem("Answer concisely in complete sentences. No markdown, no bullet points, no headers, no special characters.")
                .defaultToolCallbacks(tools)
                .build();
        this.rateLimiter = rateLimiter;
    }

    public Flux<String> streamChatResponse(String userText) {
        return acquireRateLimitPermit()
                .thenMany(executeChatCall(userText))
                .doOnError(this::logChatError)
                .doFinally(signal -> rateLimiter.release())
                .retryWhen(retryPolicy())
                .onErrorReturn(ERROR_RESPONSE);
    }

    private Mono<Void> acquireRateLimitPermit() {
        return Mono.<Void>fromRunnable(rateLimiter::acquireUninterruptibly)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<String> executeChatCall(String userText) {
        long start = System.currentTimeMillis();
        return Mono.fromCallable(() -> chatClient.prompt().user(userText).call().content())
                .doOnNext(ignored -> log.info("/chat responded in {}ms", System.currentTimeMillis() - start))
                .map(this::encodeResponse)
                .flux();
    }

    private String encodeResponse(String content) {
        String escaped = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "{\"content\":\"" + escaped + "\"}";
    }

    private void logChatError(Throwable throwable) {
        if (throwable instanceof WebClientResponseException ex) {
            log.error("OpenAI API error: {} - {}", ex.getStatusCode(), ex.getResponseBodyAsString());
        } else {
            log.error("OpenAI API error: {}", throwable.getMessage());
        }
    }

    private RetryBackoffSpec retryPolicy() {
        return Retry.backoff(5, Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(30))
                .filter(this::isRetryable);
    }

    boolean isRetryable(Throwable throwable) {
        if (throwable instanceof WebClientResponseException ex) {
            int status = ex.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return false;
    }
}
