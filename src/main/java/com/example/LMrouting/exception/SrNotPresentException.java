package com.example.LMrouting.exception;

/**
 * Thrown when a supervisor attempts to reassign a shipment to an SR who is
 * not marked present on the given date.
 * Maps to HTTP 400 via {@link com.example.LMrouting.controller.GlobalExceptionHandler}.
 */
public class SrNotPresentException extends RuntimeException {

    public SrNotPresentException(String message) {
        super(message);
    }
}
