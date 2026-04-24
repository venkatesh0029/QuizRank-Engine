package com.vidal.quiz.service;

import com.vidal.quiz.model.Event;
import com.vidal.quiz.model.LeaderboardEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LeaderboardBuilder {

    private final Map<String, AtomicInteger> participantScores = new ConcurrentHashMap<>();

    public void processEvents(List<Event> events) {
        for (Event event : events) {
            participantScores.computeIfAbsent(event.getParticipant(), k -> new AtomicInteger(0))
                             .addAndGet(event.getScore());
        }
    }

    public List<LeaderboardEntry> build() {
        // Sort descending by score
        List<Map.Entry<String, AtomicInteger>> sortedEntries = participantScores.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, AtomicInteger> e) -> e.getValue().get()).reversed())
                .collect(Collectors.toList());

        // Use LinkedHashMap semantics by creating an ordered list
        List<LeaderboardEntry> leaderboard = sortedEntries.stream()
                .map(entry -> new LeaderboardEntry(0, entry.getKey(), entry.getValue().get()))
                .collect(Collectors.toList());

        // Assign ranks and calculate checksum
        int rank = 1;
        long checksum = 0;
        for (LeaderboardEntry entry : leaderboard) {
            entry.setRank(rank++);
            checksum += entry.getTotalScore();
        }

        log.info("[PRE-SUBMIT CHECKSUM]: {}", checksum);
        log.info("Current Leaderboard State: {}", leaderboard);

        return leaderboard;
    }
    
    public void reset() {
        participantScores.clear();
    }
}
