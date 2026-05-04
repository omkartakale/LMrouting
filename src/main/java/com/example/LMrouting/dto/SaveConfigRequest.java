package com.example.LMrouting.dto;

import com.example.LMrouting.model.AffinityConfiguration;

/**
 * Request DTO for saving an affinity configuration.
 */
public record SaveConfigRequest(
        AffinityConfiguration config,
        String configName
) {}
