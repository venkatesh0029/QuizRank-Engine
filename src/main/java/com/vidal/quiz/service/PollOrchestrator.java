package com.vidal.quiz.service;

import com.vidal.quiz.api.QuizApiClient;
import com.vidal.quiz.config.AppConfig;
import com.vidal.quiz.model.Event;
import com.vidal.quiz.model.LeaderboardEntry;
import com.vidal.quiz.model.QuizMessageResponse;
import com.vidal.quiz.model.SubmitRequest;
import com.vidal.quiz.model.SubmitResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class PollOrchestrator {

    private final AppConfig appConfig;
    private final QuizApiClient apiClient;
    private final DeduplicationEngine deduplicationEngine;
    private final LeaderboardBuilder leaderboardBuilder;

    public CompletableFuture<SubmitResponse> orchestrate(String regNoParam) {
        String regNo = (regNoParam != null && !regNoParam.isEmpty()) ? regNoParam : appConfig.getDefaultRegNo();
        log.info("Starting orchestrated polling for registration number: {}", regNo);

        deduplicationEngine.reset();
        leaderboardBuilder.reset();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        CompletableFuture<SubmitResponse> futureResult = new CompletableFuture<>();
        
        AtomicInteger currentPoll = new AtomicInteger(0);
        AtomicInteger consecutiveFailures = new AtomicInteger(0);

        Runnable pollingTask = new Runnable() {
            @Override
            public void run() {
                int pollIndex = currentPoll.get();
                if (pollIndex >= appConfig.getTotalPolls()) {
                    scheduler.shutdown();
                    submitFinalLeaderboard(regNo, futureResult);
                    return;
                }

                try {
                    log.info("--- Executing Poll {}/{} ---", pollIndex, appConfig.getTotalPolls() - 1);
                    QuizMessageResponse response = apiClient.fetchPoll(regNo, pollIndex);
                    
                    List<Event> validEvents = deduplicationEngine.filterAndRecord(response);
                    leaderboardBuilder.processEvents(validEvents);
                    
                    // Reset circuit breaker on success
                    consecutiveFailures.set(0);
                    currentPoll.incrementAndGet();

                } catch (Exception e) {
                    log.error("Poll {} failed critically: {}", pollIndex, e.getMessage());
                    if (consecutiveFailures.incrementAndGet() >= 3) {
                        log.error("Circuit Breaker Open! 3 consecutive failures. Aborting process.");
                        scheduler.shutdown();
                        futureResult.completeExceptionally(new RuntimeException("Process aborted due to consecutive failures."));
                    } else {
                        // We will retry the same poll index next time
                        log.warn("Will retry poll {} on next scheduled execution", pollIndex);
                    }
                }
            }
        };

        // scheduleWithFixedDelay guarantees at least 5 seconds delay between the END of one poll and the START of the next.
        // This is safer than scheduleAtFixedRate if the API strictly validates the delay between requests.
        scheduler.scheduleWithFixedDelay(pollingTask, 0, appConfig.getPollDelayMs(), TimeUnit.MILLISECONDS);

        return futureResult;
    }

    private void submitFinalLeaderboard(String regNo, CompletableFuture<SubmitResponse> futureResult) {
        log.info("All polls completed. Building final leaderboard...");
        try {
            List<LeaderboardEntry> leaderboard = leaderboardBuilder.build();
            SubmitRequest request = new SubmitRequest(regNo, leaderboard);
            SubmitResponse response = apiClient.submitLeaderboard(request);
            log.info("Final Submission Response: {}", response);
            futureResult.complete(response);
        } catch (Exception e) {
            log.error("Failed to submit final leaderboard", e);
            futureResult.completeExceptionally(e);
        }
    }
}
