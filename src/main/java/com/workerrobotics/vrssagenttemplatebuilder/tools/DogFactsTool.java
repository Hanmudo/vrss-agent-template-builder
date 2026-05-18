package com.workerrobotics.vrssagenttemplatebuilder.tools;

import com.workerrobotics.vrssagenttemplatebuilder.service.DogFactsService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class DogFactsTool {

    private final DogFactsService dogFactsService;

    public DogFactsTool(DogFactsService dogFactsService) {
        this.dogFactsService = dogFactsService;
    }

    @Tool(description = "Retrieves random dog facts. Call this when the user asks for dog facts or interesting facts about dogs.")
    public String getRandomDogFacts(
            @ToolParam(description = "Number of facts to retrieve, between 1 and 5. Default is 1.") int limit
    ) {
        return dogFactsService.getFacts(limit);
    }
}
