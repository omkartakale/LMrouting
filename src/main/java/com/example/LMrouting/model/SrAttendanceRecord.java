package com.example.LMrouting.model;

/**
 * Simple value object for SR attendance — replaces the JPA SrAttendance entity
 * in the in-memory store.
 */
public record SrAttendanceRecord(String srName, boolean present) {}
