package dev.demo.selection.web;

import dev.demo.selection.rpc.SelectionRpc;
import dev.demo.selection.rpc.StatusQuery;
import dev.demo.selection.rpc.StatusView;
import dev.demo.selection.rpc.SubmitCommand;
import dev.demo.selection.rpc.SubmitResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.UUID;
import org.apache.dubbo.config.annotation.DubboReference;
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
public class SelectionController {
    @DubboReference(lazy = true, check = false, timeout = 8000)
    private SelectionRpc selection;
    private final WebSettings settings;

    public SelectionController(WebSettings settings) {
        this.settings = settings;
    }

    public record Submit(@Positive long courseId) { }
    public record Response(UUID requestId, String state, String reason, String statusUrl) { }

    @PostMapping
    public ResponseEntity<Response> submit(@PathVariable("term") long term,
                                           @RequestHeader("Idempotency-Key") UUID id,
                                           @RequestHeader("X-Student-Id") long student,
                                           @Valid @RequestBody Submit body) {
        checkStudent(term, student);
        SubmitResult result = selection.submit(new SubmitCommand(id, student, term, body.courseId()));
        return switch (result.getCode()) {
            case "RATE_LIMITED" -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, result.getCode());
            case "COURSE_UNKNOWN" -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, result.getCode());
            case "STUDENT_BUSY", "SOLD_OUT", "KEY_REUSED" -> throw new ResponseStatusException(HttpStatus.CONFLICT, result.getCode());
            case "NOT_READY" -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ADMISSION_NOT_READY_OR_COURSE_UNKNOWN");
            case "OK" -> {
                String path = path(term, id);
                yield ResponseEntity.status("PROCESSING".equals(result.getState()) ? 202 : 200)
                        .location(URI.create(path)).header("Retry-After", "1").header("Cache-Control", "no-store")
                        .body(new Response(id, result.getState(), result.getReason(), path));
            }
            default -> throw new IllegalStateException("Unexpected submit code: " + result.getCode());
        };
    }

    @GetMapping("/{id}")
    public ResponseEntity<Response> status(@PathVariable("term") long term,
                                           @PathVariable("id") UUID id,
                                           @RequestHeader("X-Student-Id") long student) {
        checkStudent(term, student);
        StatusView status = selection.status(new StatusQuery(id, student, term));
        if ("RATE_LIMITED".equals(status.getState())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
        }
        if ("NOT_FOUND".equals(status.getState())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND");
        }
        return ResponseEntity.ok().header("Retry-After", "1").header("Cache-Control", "no-store")
                .body(new Response(id, status.getState(), status.getReason(), path(term, id)));
    }

    private void checkStudent(long term, long student) {
        if (term != settings.term()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TERM_NOT_FOUND");
        if (student < settings.studentFrom() || student > settings.studentTo()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "STUDENT_UNKNOWN");
        }
    }

    private String path(long term, UUID id) { return "/api/terms/" + term + "/selections/" + id; }
}
