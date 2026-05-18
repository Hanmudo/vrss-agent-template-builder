package com.workerrobotics.vrssagenttemplatebuilder.service;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class DogFactsService {

    private final WebClient webClient;

    public DogFactsService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .baseUrl("https://dogapi.dog/api/v2")
                .build();
    }

    public String getFacts(int limit) {
        DogFactsResponse response = webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/facts").queryParam("limit", limit).build())
                .retrieve()
                .bodyToMono(DogFactsResponse.class)
                .block();

        if (response == null || response.data() == null) {
            return "No dog facts available.";
        }

        return response.data().stream()
                .map(d -> d.attributes().body())
                .collect(Collectors.joining("\n"));
    }

    record DogFactsResponse(List<DogFactData> data) {}
    record DogFactData(String id, String type, DogFactAttributes attributes) {}
    record DogFactAttributes(String body) {}
}
