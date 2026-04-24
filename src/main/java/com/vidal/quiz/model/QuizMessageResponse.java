package com.vidal.quiz.model;

import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuizMessageResponse {
    private String regNo;
    private String setId;
    private int pollIndex;
    private List<Event> events;
}
