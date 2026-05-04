package com.example.LMrouting.dto;

import java.util.List;

/**
 * DTO for configuration validation result.
 */
public record ValidationResultDto(
        boolean isValid,
        List<String> warnings
) {}
