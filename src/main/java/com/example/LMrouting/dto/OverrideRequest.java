package com.example.LMrouting.dto;

/**
 * Request body for POST /api/allocate/{date}/override.
 */
public record OverrideRequest(String shippingId, String targetSrName) {}
