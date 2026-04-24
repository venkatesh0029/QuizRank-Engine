package com.vidal.quiz.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidal.quiz.config.AppConfig;
import com.vidal.quiz.model.QuizMessageResponse;
import com.vidal.quiz.model.SubmitRequest;
import com.vidal.quiz.model.SubmitResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuizApiClient {

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public QuizMessageResponse fetchPoll(String regNo, int pollIndex) {
        String url = String.format("%s/quiz/messages?regNo=%s&poll=%d", appConfig.getApiBaseUrl(), regNo, pollIndex);
        log.info("[HTTP GET] Polling URL: {}", url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        int attempts = 0;
        int maxRetries = appConfig.getMaxRetries();
        long backoffDelay = 1000;

        while (attempts <= maxRetries) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                log.debug("[HTTP RESPONSE] Poll {} status={}, body={}", pollIndex, response.statusCode(), response.body());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    QuizMessageResponse quizResponse = objectMapper.readValue(response.body(), QuizMessageResponse.class);
                    // Defensive validation
                    if (quizResponse.getEvents() != null) {
                        int before = quizResponse.getEvents().size();
                        quizResponse.getEvents().removeIf(e -> e.getScore() < 0 || e.getParticipant() == null || e.getRoundId() == null);
                        int after = quizResponse.getEvents().size();
                        if (before != after) {
                            log.warn("Poll {}: Removed {} invalid events during defensive validation", pollIndex, before - after);
                        }
                    }
                    return quizResponse;
                } else {
                    log.warn("Poll {} returned status code {}. Response: {}", pollIndex, response.statusCode(), response.body());
                }
            } catch (Exception e) {
                log.warn("Poll {} failed on attempt {}: {}", pollIndex, attempts + 1, e.getMessage());
            }

            attempts++;
            if (attempts <= maxRetries) {
                try {
                    log.info("Retrying poll {} in {} ms...", pollIndex, backoffDelay);
                    Thread.sleep(backoffDelay);
                    backoffDelay *= 2; // Exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted for poll " + pollIndex, ie);
                }
            }
        }
        throw new RuntimeException("Failed to fetch poll " + pollIndex + " after " + (maxRetries + 1) + " attempts");
    }

    public SubmitResponse submitLeaderboard(SubmitRequest submitRequest) {
        String url = appConfig.getApiBaseUrl() + "/quiz/submit";
        
        try {
            String jsonBody = objectMapper.writeValueAsString(submitRequest);
            log.info("[HTTP POST] Submit URL: {}", url);
            log.info("[HTTP POST] Submit Payload: {}", jsonBody);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return objectMapper.readValue(response.body(), SubmitResponse.class);
            } else {
                log.error("Submission failed with status code {}. Response: {}", response.statusCode(), response.body());
                throw new RuntimeException("Submission failed. Status: " + response.statusCode());
            }
        } catch (Exception e) {
            log.error("Exception during submission", e);
            throw new RuntimeException("Failed to submit leaderboard", e);
        }
    }
}
