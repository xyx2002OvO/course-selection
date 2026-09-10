package dev.demo.selection.rpc;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

public class SubmitCommand implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private UUID requestId;
    private long studentId;
    private long termId;
    private long courseId;

    public SubmitCommand() { }

    public SubmitCommand(UUID requestId, long studentId, long termId, long courseId) {
        this.requestId = requestId;
        this.studentId = studentId;
        this.termId = termId;
        this.courseId = courseId;
    }

    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }
    public long getStudentId() { return studentId; }
    public void setStudentId(long studentId) { this.studentId = studentId; }
    public long getTermId() { return termId; }
    public void setTermId(long termId) { this.termId = termId; }
    public long getCourseId() { return courseId; }
    public void setCourseId(long courseId) { this.courseId = courseId; }
}
