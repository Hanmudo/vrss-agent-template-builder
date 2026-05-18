package com.workerrobotics.vrssagenttemplatebuilder.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Semaphore;

@Configuration
public class RateLimiterConfig {

    @Bean
    public Semaphore openAiRateLimiter(
            @Value("${openai.rate-limiter.max-concurrent-requests:3}") int maxConcurrentRequests) {
        return new Semaphore(maxConcurrentRequests, true);
    }
}
