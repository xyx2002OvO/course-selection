package dev.demo.selection.rpc;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import dev.demo.selection.application.SelectionService;
import dev.demo.selection.domain.Selection;
import dev.demo.selection.infrastructure.CourseBloom;
import dev.demo.selection.infrastructure.HotspotRules;
import dev.demo.selection.infrastructure.ReservationStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class SelectionRpcImpl implements SelectionRpc {
    private final ReservationStore store;
    private final SelectionService service;
    private final CourseBloom bloom;
    private final Timer accept;

    public SelectionRpcImpl(ReservationStore store, SelectionService service, CourseBloom bloom, MeterRegistry meters) {
        this.store = store;
        this.service = service;
        this.bloom = bloom;
        this.accept = Timer.builder("selection.accept.duration")
                .description("accept() including connection acquire, SQL, and commit")
                .register(meters);
    }

    @Override
    public SubmitResult submit(SubmitCommand command) {
        if (!bloom.mightContain(command.getCourseId())) {
            return SubmitResult.rejected("COURSE_UNKNOWN");
        }
        Entry entry = null;
        try {
            entry = SphU.entry(HotspotRules.SELECT_COURSE, EntryType.IN, 1, command.getCourseId());
            return reserve(command);
        } catch (BlockException e) {
            return SubmitResult.rejected("RATE_LIMITED");
        } finally {
            if (entry != null) {
                entry.exit(1, command.getCourseId());
            }
        }
    }

    private SubmitResult reserve(SubmitCommand command) {
        if (!store.allow(command.getTermId(), command.getStudentId(), false)) {
            return SubmitResult.rejected("RATE_LIMITED");
        }
        String outcome = store.reserve(command.getRequestId(), command.getStudentId(),
                command.getTermId(), command.getCourseId());
        return switch (outcome) {
            case "STUDENT_BUSY", "SOLD_OUT", "KEY_REUSED", "NOT_READY" -> SubmitResult.rejected(outcome);
            case "RESERVED", "EXISTING" -> accept(command);
            default -> throw new IllegalStateException("Unexpected reservation outcome: " + outcome);
        };
    }

    @Override
    public StatusView status(StatusQuery query) {
        if (!store.allow(query.getTermId(), query.getStudentId(), true)) {
            return new StatusView(query.getRequestId(), "RATE_LIMITED", "");
        }
        var status = store.status(query.getTermId(), query.getRequestId(), query.getStudentId());
        return new StatusView(query.getRequestId(), status.state(), status.reason());
    }

    private SubmitResult accept(SubmitCommand command) {
        Selection reservation = store.reservation(command.getTermId(), command.getRequestId()).orElseThrow();
        try {
            Selection accepted = accept.record(() -> service.accept(reservation));
            String state = accepted.state().terminal() ? accepted.state().name() : "PROCESSING";
            return new SubmitResult("OK", accepted.requestId(), state, accepted.reason());
        } catch (IllegalArgumentException e) {
            return SubmitResult.rejected("KEY_REUSED");
        }
    }
}
