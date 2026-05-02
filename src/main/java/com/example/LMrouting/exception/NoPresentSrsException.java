package com.example.LMrouting.exception;

/**
 * Thrown when no SRs are marked present for the requested allocation date.
 * Maps to HTTP 400 via {@link com.example.LMrouting.controller.GlobalExceptionHandler}.
 */
public class NoPresentSrsException extends RuntimeException {

    public NoPresentSrsException(String message) {
        super(message);
    }
}
