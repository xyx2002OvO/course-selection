package dev.demo.selection.web;

import org.apache.dubbo.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(RpcException.class)
    ResponseEntity<Map<String, String>> rpc(RpcException e) {
        log.warn("Domain RPC unavailable; do not release a possibly accepted reservation", e);
        return ResponseEntity.status(503).header("Retry-After", "2").body(Map.of(
                "code", "OUTCOME_UNCONFIRMED", "action", "Query or retry with the SAME Idempotency-Key"));
    }
}
