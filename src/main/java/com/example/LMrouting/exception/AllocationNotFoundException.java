package com.example.LMrouting.exception;

/**
 * Thrown when no shipment data exists for the requested allocation date.
 * Maps to HTTP 404 via {@link com.example.LMrouting.controller.GlobalExceptionHandler}.
 */
public class AllocationNotFoundException extends RuntimeException {

    public AllocationNotFoundException(String message) {
        super(message);
    }
}
