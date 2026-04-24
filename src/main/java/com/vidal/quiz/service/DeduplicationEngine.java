package com.vidal.quiz.service;

import com.vidal.quiz.model.Event;
import com.vidal.quiz.model.QuizMessageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DeduplicationEngine {

    // Deduplicate individual events by roundId + "::" + participant
    // Removed Poll-Level deduplication to ensure we don't accidentally drop valid events 
    // if the validator API sends unexpected poll JSON structures.
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();

    public List<Event> filterAndRecord(QuizMessageResponse response) {
        if (response.getEvents() == null || response.getEvents().isEmpty()) {
            return List.of();
        }

        List<Event> uniqueEvents = response.getEvents().stream()
                .filter(event -> {
                    String eventKey = event.getRoundId() + "::" + event.getParticipant();
                    boolean isNew = processedEvents.add(eventKey);
                    if (!isNew) {
                        log.debug("Duplicate event ignored: {}", eventKey);
                    }
                    return isNew;
                })
                .collect(Collectors.toList());

        log.info("Poll {}: Received {} events, {} unique after deduplication.", 
                 response.getPollIndex(), response.getEvents().size(), uniqueEvents.size());
                 
        return uniqueEvents;
    }
    
    public void reset() {
        processedEvents.clear();
    }
}
