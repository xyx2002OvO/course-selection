package dev.demo.selection.api;

import dev.demo.selection.Settings;
import dev.demo.selection.application.SelectionService;
import dev.demo.selection.domain.Selection;
import dev.demo.selection.infrastructure.ReservationStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/terms/{term}/selections")
@ConditionalOnProperty(name = "app.api-enabled", havingValue = "true")
public class SelectionController {
    private final ReservationStore store;
    private final SelectionService service;
    private final Settings settings;
    private final Timer accept;

    public SelectionController(ReservationStore store, SelectionService service, Settings settings,
                               MeterRegistry meters) {
        this.store = store;
        this.service = service;
        this.settings = settings;
        this.accept = Timer.builder("selection.accept.duration")
                .description("accept() including connection acquire, SQL, and commit")
                .register(meters);
    }

    public record Submit(@Positive long courseId) { }
    public record Response(UUID requestId, String state, String reason, String statusUrl) { }

    @PostMapping
    public ResponseEntity<Response> submit(@PathVariable long term, @RequestHeader("Idempotency-Key") UUID id,
                                           @Valid @RequestBody Submit body, Principal principal) {
        long student = student(term, principal, false);
        String outcome = store.reserve(id, student, term, body.courseId());
        switch (outcome) {
            case "STUDENT_BUSY", "SOLD_OUT", "KEY_REUSED" -> throw new ResponseStatusException(HttpStatus.CONFLICT, outcome);
            case "NOT_READY" -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ADMISSION_NOT_READY_OR_COURSE_UNKNOWN");
            case "RESERVED", "EXISTING" -> { }
            default -> throw new IllegalStateException("Unexpected reservation outcome: " + outcome);
        }
        Selection reservation = store.reservation(term, id).orElseThrow();
        // A timeout here is an uncertain outcome: leave the reservation for retries/reconciliation.
        Selection accepted = accept.record(() -> service.accept(reservation));
        String path = path(term, id);
        String state = accepted.state().terminal() ? accepted.state().name() : "PROCESSING";
        return ResponseEntity.status(accepted.state().terminal() ? 200 : 202)
                .location(URI.create(path)).header("Retry-After", "1").header("Cache-Control", "no-store")
                .body(new Response(id, state, accepted.reason(), path));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Response> status(@PathVariable long term, @PathVariable UUID id, Principal principal) {
        long student = student(term, principal, true);
        var status = store.status(term, id, student);
        if (status.state().equals("NOT_FOUND")) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND");
        return ResponseEntity.ok().header("Retry-After", "1").header("Cache-Control", "no-store")
                .body(new Response(id, status.state(), status.reason(), path(term, id)));
    }

    private long student(long term, Principal principal, boolean query) {
        if (term != settings.term()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TERM_NOT_FOUND");
        long student = Long.parseLong(principal.getName());
        if (!store.allow(term, student, query)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
        return student;
    }

    private String path(long term, UUID id) { return "/api/terms/" + term + "/selections/" + id; }
}
