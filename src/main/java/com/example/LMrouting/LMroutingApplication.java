package com.example.LMrouting;

import com.example.LMrouting.dto.ScoreWeights;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
}
