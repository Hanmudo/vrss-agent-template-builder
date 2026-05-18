package com.workerrobotics.vrssagenttemplatebuilder.tools;

import com.workerrobotics.vrssagenttemplatebuilder.service.DogFactsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DogFactsToolTest {

    @Mock
    private DogFactsService dogFactsService;

    @InjectMocks
    private DogFactsTool dogFactsTool;

    @Test
    void getRandomDogFacts_delegatesToService() {
        when(dogFactsService.getFacts(3)).thenReturn("Fact one.\nFact two.\nFact three.");

        String result = dogFactsTool.getRandomDogFacts(3);

        assertEquals("Fact one.\nFact two.\nFact three.", result);
    }

    @Test
    void getRandomDogFacts_singleFact_returnsSingleString() {
        when(dogFactsService.getFacts(1)).thenReturn("Dogs can smell fear.");

        String result = dogFactsTool.getRandomDogFacts(1);

        assertEquals("Dogs can smell fear.", result);
    }
}
