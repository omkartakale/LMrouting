package com.example.LMrouting.exception;

/**
 * Thrown when a supervisor attempts to override a shipment after the allocation
 * for that date has already been finalized.
 * Maps to HTTP 409 via {@link com.example.LMrouting.controller.GlobalExceptionHandler}.
 */
public class AllocationAlreadyFinalizedException extends RuntimeException {

    public AllocationAlreadyFinalizedException(String message) {
        super(message);
    }
}
