package com.example.LMrouting.controller;

import com.example.LMrouting.exception.AllocationAlreadyFinalizedException;
import com.example.LMrouting.exception.AllocationNotFoundException;
import com.example.LMrouting.exception.NoPresentSrsException;
import com.example.LMrouting.exception.SrNotPresentException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralized exception handler for allocation-related errors.
 *
 * <ul>
 *   <li>{@link AllocationNotFoundException} → HTTP 404</li>
 *   <li>{@link NoPresentSrsException} → HTTP 400</li>
 *   <li>{@link SrNotPresentException} → HTTP 400</li>
 *   <li>{@link AllocationAlreadyFinalizedException} → HTTP 409</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AllocationNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleAllocationNotFound(
            AllocationNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(NoPresentSrsException.class)
    public ResponseEntity<Map<String, Object>> handleNoPresentSrs(
            NoPresentSrsException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(SrNotPresentException.class)
    public ResponseEntity<Map<String, Object>> handleSrNotPresent(
            SrNotPresentException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(AllocationAlreadyFinalizedException.class)
    public ResponseEntity<Map<String, Object>> handleAllocationAlreadyFinalized(
            AllocationAlreadyFinalizedException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * Static resource / SPA paths — do not turn these into HTTP 500 JSON from the generic handler.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(NoResourceFoundException ex) {
        log.debug("No static resource: {}", ex.getResourcePath());
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * Bad-request bucket: any IllegalArgumentException / IllegalStateException
     * thrown from validation paths should reach the client with its message,
     * not Spring's default empty 500.
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Catch-all: ensures unhandled RuntimeExceptions (NPE, NoSuchElement, etc.)
     * still return a usable message to the UI rather than an empty 500 body.
     * The full stack trace is logged server-side for debugging.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        if (ex instanceof NoResourceFoundException nr) {
            return handleNoResourceFound(nr);
        }
        for (Throwable c = ex.getCause(); c != null; c = c.getCause()) {
            if (c instanceof NoResourceFoundException nr) {
                return handleNoResourceFound(nr);
            }
        }
        log.error("Unhandled exception in API:", ex);
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = ex.getClass().getSimpleName() + " (see server logs)";
        }
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, msg);
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
