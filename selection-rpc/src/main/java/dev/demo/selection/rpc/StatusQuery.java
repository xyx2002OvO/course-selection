package dev.demo.selection.rpc;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

public class StatusQuery implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private UUID requestId;
    private long studentId;
    private long termId;

    public StatusQuery() { }

    public StatusQuery(UUID requestId, long studentId, long termId) {
        this.requestId = requestId;
        this.studentId = studentId;
        this.termId = termId;
    }

    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }
    public long getStudentId() { return studentId; }
    public void setStudentId(long studentId) { this.studentId = studentId; }
    public long getTermId() { return termId; }
    public void setTermId(long termId) { this.termId = termId; }
}
