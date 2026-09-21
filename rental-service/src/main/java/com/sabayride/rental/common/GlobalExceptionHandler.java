package com.sabayride.rental.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /** PostgreSQL exclusion_violation — the no_double_booking constraint fired. */
    private static final String EXCLUSION_VIOLATION = "23P01";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus())
                .body(ApiError.of(e.getCode(), e.getMessage(), e.getField()));
    }

    /**
     * THE SAFETY NET.
     *
     * If the pessimistic lock is ever wrong, removed in a refactor, or bypassed,
     * the database still refuses the overlapping row and raises SQLSTATE 23P01.
     * Without this handler that surfaces as a 500 — a server error for what is
     * really an ordinary "someone else got there first".
     *
     * Note this reads the SQLState from java.sql.SQLException rather than
     * importing the PostgreSQL driver's PSQLException. The driver is a RUNTIME
     * dependency — correct, since nothing should compile against a specific
     * database driver — and SQLSTATE codes are an ISO standard, so this works
     * for any driver that reports them.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException e) {
        if (EXCLUSION_VIOLATION.equals(sqlStateOf(e))) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(
                    "NO_UNITS_AVAILABLE",
                    "That bike was just booked by someone else. Please choose another.",
                    null));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(
                "CONSTRAINT_VIOLATION", "That operation conflicts with existing data.", null));
    }

    /** Walks the cause chain for the first SQLException carrying a state. */
    private static String sqlStateOf(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            if (c.getCause() == c) break;   // guard against a self-referencing cause
        }
        return null;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        var fe = e.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        return ResponseEntity.badRequest().body(ApiError.of(
                "VALIDATION_FAILED",
                fe != null ? fe.getDefaultMessage() : "Request is not valid.",
                fe != null ? fe.getField() : null));
    }
}
