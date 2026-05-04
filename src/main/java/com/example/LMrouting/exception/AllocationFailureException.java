package com.example.LMrouting.exception;

/**
 * Exception thrown when affinity allocation fails to meet fairness constraints.
 * 
 * This exception indicates that the allocation algorithm could not produce a valid
 * allocation that satisfies the required fairness constraints (earnings variance,
 * capacity constraints, etc.) even after applying rebalancing.
 * 
 * When this exception is thrown, the system should suggest reverting to standard
 * allocation mode.
 * 
 * Requirements: 20.3
 */
public class AllocationFailureException extends RuntimeException {

    public AllocationFailureException(String message) {
        super(message);
    }

    public AllocationFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
