package com.example.LMrouting.dto;

/**
 * Response for GET /api/attendance/{date} — per-SR attendance status.
 */
public record SrAttendanceDto(String srName, boolean present) {}
