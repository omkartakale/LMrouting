package com.example.LMrouting.controller;

import com.example.LMrouting.exception.AllocationAlreadyFinalizedException;
import com.example.LMrouting.exception.AllocationNotFoundException;
import com.example.LMrouting.exception.NoPresentSrsException;
import com.example.LMrouting.exception.SrNotPresentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
