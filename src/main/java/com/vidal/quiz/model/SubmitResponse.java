package com.vidal.quiz.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubmitResponse {
    private boolean isCorrect;
    private boolean isIdempotent;
    private int submittedTotal;
    private int expectedTotal;
    private String message;
}
