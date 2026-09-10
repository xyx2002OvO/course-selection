package dev.demo.selection.rpc;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

public class SubmitResult implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private String code;
    private UUID requestId;
    private String state;
    private String reason;

    public SubmitResult() { }

    public SubmitResult(String code, UUID requestId, String state, String reason) {
        this.code = code;
        this.requestId = requestId;
        this.state = state;
        this.reason = reason;
    }

    public static SubmitResult rejected(String code) {
        return new SubmitResult(code, null, "", "");
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
