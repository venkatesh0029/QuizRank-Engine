package com.vidal.quiz.controller;

import com.vidal.quiz.model.SubmitResponse;
import com.vidal.quiz.service.PollOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/quiz")
@RequiredArgsConstructor
public class QuizController {

    private final PollOrchestrator pollOrchestrator;

    @PostMapping("/process")
    public ResponseEntity<?> processQuiz(
            @RequestParam(value = "regNo", required = false) String regNo) {
        log.info("Received request to process quiz for regNo: {}", regNo);
        
        try {
            // Block and wait for the result to keep the API synchronous for easy testing.
            // In a true production environment, we might return an accepted status immediately
            // and process asynchronously, or use DeferredResult.
            CompletableFuture<SubmitResponse> futureResult = pollOrchestrator.orchestrate(regNo);
            SubmitResponse response = futureResult.join(); 
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to process quiz", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body("Failed to process quiz: " + e.getMessage());
        }
    }
}
