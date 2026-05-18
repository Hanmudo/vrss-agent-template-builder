package com.workerrobotics.vrssagenttemplatebuilder.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RateLimiterConfigTest {

    private RateLimiterConfig config;

    @BeforeEach
    void setUp() {
        config = new RateLimiterConfig();
    }

    @Test
    void openAiRateLimiter_withConfiguredPermits_createsBean() {
        var semaphore = config.openAiRateLimiter(5);
        assertNotNull(semaphore);
        assertEquals(5, semaphore.availablePermits());
    }

    @Test
    void openAiRateLimiter_withDefaultValue_usesThreePermits() {
        assertEquals(3, config.openAiRateLimiter(3).availablePermits());
    }

    @Test
    void openAiRateLimiter_withOnePermit_allowsSingleConcurrentRequest() {
        assertEquals(1, config.openAiRateLimiter(1).availablePermits());
    }
}
