package com.vidal.quiz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.quiz")
public class AppConfig {
    private String apiBaseUrl = "https://devapigw.vidalhealthtpa.com/srm-quiz-task";
    private String defaultRegNo = "2024CS101";
    private int totalPolls = 10;
    private long pollDelayMs = 5000;
    private int maxRetries = 3;
}
