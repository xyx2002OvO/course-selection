package dev.demo.selection.rpc;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

public class StatusView implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private UUID requestId;
    private String state;
    private String reason;

    public StatusView() { }

    public StatusView(UUID requestId, String state, String reason) {
        this.requestId = requestId;
        this.state = state;
        this.reason = reason;
    }

    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
