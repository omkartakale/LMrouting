package com.example.LMrouting;

import com.example.LMrouting.dto.ScoreWeights;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@SpringBootApplication
@EnableConfigurationProperties(ScoreWeights.class)
public class LMroutingApplication {

    public static void main(String[] args) {
        SpringApplication.run(LMroutingApplication.class, args);
    }

    @Bean
    public ScoreWeights scoreWeights() {
        return new ScoreWeights();
    }

    /**
     * RestTemplate with 8-second connect + read timeouts.
     * Used by HubBoundaryService for external API calls.
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }
}
