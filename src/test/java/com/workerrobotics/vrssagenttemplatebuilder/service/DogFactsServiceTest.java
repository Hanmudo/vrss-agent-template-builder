package com.workerrobotics.vrssagenttemplatebuilder.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DogFactsServiceTest {

    @Mock
    private WebClient.Builder webClientBuilder;

    @Mock
    private WebClient webClient;

    private DogFactsService service;

    @BeforeEach
    void setUp() {
        when(webClientBuilder.baseUrl(any(String.class))).thenReturn(webClientBuilder);
        when(webClientBuilder.build()).thenReturn(webClient);
        service = new DogFactsService(webClientBuilder);
    }

    @Test
    void getFacts_returnsSingleFact() {
        stubWebClient("Dogs have a sense of time.");

        String result = service.getFacts(1);

        assertEquals("Dogs have a sense of time.", result);
    }

    @Test
    void getFacts_returnsMultipleFactsJoinedByNewline() {
        stubWebClientMultiple(List.of("Fact one.", "Fact two."));

        String result = service.getFacts(2);

        assertEquals("Fact one.\nFact two.", result);
    }

    @Test
    void getFacts_whenResponseIsNull_returnsNoFactsMessage() {
        var requestHeadersUriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        var requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
        var responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(java.util.function.Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(DogFactsService.DogFactsResponse.class)).thenReturn(Mono.empty());

        String result = service.getFacts(1);

        assertEquals("No dog facts available.", result);
    }

    @SuppressWarnings("unchecked")
    private void stubWebClient(String body) {
        var requestHeadersUriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        var requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
        var responseSpec = mock(WebClient.ResponseSpec.class);

        var response = new DogFactsService.DogFactsResponse(
                List.of(new DogFactsService.DogFactData("1", "fact",
                        new DogFactsService.DogFactAttributes(body))));

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(java.util.function.Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(DogFactsService.DogFactsResponse.class)).thenReturn(Mono.just(response));
    }

    @SuppressWarnings("unchecked")
    private void stubWebClientMultiple(List<String> bodies) {
        var requestHeadersUriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        var requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
        var responseSpec = mock(WebClient.ResponseSpec.class);

        var data = bodies.stream()
                .map(b -> new DogFactsService.DogFactData("1", "fact",
                        new DogFactsService.DogFactAttributes(b)))
                .toList();
        var response = new DogFactsService.DogFactsResponse(data);

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(java.util.function.Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(DogFactsService.DogFactsResponse.class)).thenReturn(Mono.just(response));
    }
}
