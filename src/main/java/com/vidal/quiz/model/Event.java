package com.vidal.quiz.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Event {
    private String roundId;
    private String participant;
    private int score;
}
