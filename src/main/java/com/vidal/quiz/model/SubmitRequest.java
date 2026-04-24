package com.vidal.quiz.model;

import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubmitRequest {
    private String regNo;
    private List<LeaderboardEntry> leaderboard;
}
