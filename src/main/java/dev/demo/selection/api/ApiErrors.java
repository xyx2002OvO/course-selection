package dev.demo.selection.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> known(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).header("Retry-After", "2")
                .body(Map.of("code", e.getReason() == null ? "REQUEST_ERROR" : e.getReason()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> identity(IllegalArgumentException e) {
        return ResponseEntity.status(409).body(Map.of("code", "IDEMPOTENCY_KEY_REUSED"));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Map<String, String>> unavailable(Exception e) {
        log.warn("Infrastructure unavailable; do not release a possibly accepted reservation", e);
        return ResponseEntity.status(503).header("Retry-After", "2").body(Map.of(
                "code", "OUTCOME_UNCONFIRMED", "action", "Query or retry with the SAME Idempotency-Key"));
    }
}
