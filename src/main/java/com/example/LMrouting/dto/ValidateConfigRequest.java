package com.example.LMrouting.dto;

import com.example.LMrouting.model.AffinityConfiguration;
import com.example.LMrouting.model.Shipment;

import java.util.List;

/**
 * Request DTO for validating an affinity configuration.
 */
public record ValidateConfigRequest(
        AffinityConfiguration config,
        List<Shipment> currentShipments
) {}
