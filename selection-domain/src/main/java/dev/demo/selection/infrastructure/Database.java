package dev.demo.selection.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.demo.selection.domain.Course;
import dev.demo.selection.domain.Selection;
import dev.demo.selection.domain.Selection.State;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class Database {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public Database(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insertRequest(Selection s) {
        jdbc.update("""
            INSERT INTO selection_request(request_id,student_id,term_id,course_id,state,reason,deadline)
            VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id
            """, s.requestId().toString(), s.studentId(), s.termId(), s.courseId(),
                s.state().name(), s.reason(), Timestamp.from(s.deadline()));
    }

    public Optional<Selection> find(UUID id) {
        return jdbc.query("SELECT * FROM selection_request WHERE request_id=?", this::selection,
                id.toString()).stream().findFirst();
    }

    public Selection lockRequest(UUID id) {
        return jdbc.queryForObject("SELECT * FROM selection_request WHERE request_id=? FOR UPDATE",
                this::selection, id.toString());
    }

    public Optional<Integer> lockStudent(long student, long term) {
        return jdbc.query("""
            SELECT max_credits FROM student_term_state WHERE student_id=? AND term_id=? FOR UPDATE
            """, (rs, n) -> rs.getInt(1), student, term).stream().findFirst();
    }

    public List<Course> courses(long term) {
        return jdbc.query("SELECT * FROM course WHERE term_id=? ORDER BY id", this::course, term);
    }

    public Optional<Course> course(long id, long term) {
        return jdbc.query("SELECT * FROM course WHERE id=? AND term_id=?", this::course, id, term)
                .stream().findFirst();
    }

    public Optional<Boolean> catalogFrozen(long term) {
        return jdbc.query("SELECT frozen FROM term_catalog WHERE term_id=?",
                (rs, n) -> rs.getBoolean(1), term).stream().findFirst();
    }

    public void saveCatalogFrozen(long term, boolean frozen) {
        jdbc.update("""
            INSERT INTO term_catalog(term_id,frozen) VALUES (?,?)
            ON DUPLICATE KEY UPDATE frozen=VALUES(frozen)
            """, term, frozen);
    }

    public List<Course> enrolled(long student, long term) {
        return jdbc.query("""
            SELECT c.* FROM enrollment e JOIN course c ON c.id=e.course_id AND c.term_id=e.term_id
            WHERE e.student_id=? AND e.term_id=? ORDER BY c.id
            """, this::course, student, term);
    }

    public Set<Long> passed(long student) {
        return new HashSet<>(jdbc.query("SELECT course_id FROM passed_course WHERE student_id=?",
                (rs, n) -> rs.getLong(1), student));
    }

    public boolean decrement(long course, long term) {
        return jdbc.update("UPDATE course SET remaining=remaining-1 WHERE id=? AND term_id=? AND remaining>0",
                course, term) == 1;
    }

    public void enroll(Selection s) {
        jdbc.update("INSERT INTO enrollment(student_id,term_id,course_id,request_id) VALUES (?,?,?,?)",
                s.studentId(), s.termId(), s.courseId(), s.requestId().toString());
    }

    public Selection finish(Selection old, State state, String reason) {
        return finish(old, state, reason, true);
    }

    public Selection finish(Selection old, State state, String reason, boolean writeOutbox) {
        jdbc.update("UPDATE selection_request SET state=?,reason=?,updated_at=CURRENT_TIMESTAMP(6) WHERE request_id=?",
                state.name(), reason, old.requestId().toString());
        Selection result = new Selection(old.requestId(), old.studentId(), old.termId(), old.courseId(),
                state, reason, old.deadline());
        if (writeOutbox) event(result, "RESULT");
        return result;
    }

    public void event(Selection s, String kind) {
        try {
            jdbc.update("""
                INSERT INTO outbox_event(request_id,kind,aggregatetype,message_key,payload)
                VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id
                """, s.requestId().toString(), kind, "COMMAND".equals(kind) ? "requests" : "results",
                    s.studentId() + ":" + s.termId(), json.writeValueAsString(s));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize selection event", e);
        }
    }

    public List<Selection> expired(int limit) {
        return jdbc.query("""
            SELECT * FROM selection_request WHERE state='ACCEPTED' AND deadline<=CURRENT_TIMESTAMP(6)
            ORDER BY deadline LIMIT ?
            """, this::selection, limit);
    }

    public long requestCount(long term) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM selection_request WHERE term_id=?", Long.class, term);
    }

    private Selection selection(ResultSet rs, int n) throws SQLException {
        return new Selection(UUID.fromString(rs.getString("request_id")), rs.getLong("student_id"),
                rs.getLong("term_id"), rs.getLong("course_id"), State.valueOf(rs.getString("state")),
                rs.getString("reason"), rs.getTimestamp("deadline").toInstant());
    }

    private Course course(ResultSet rs, int n) throws SQLException {
        return new Course(rs.getLong("id"), rs.getLong("term_id"), rs.getString("title"),
                rs.getInt("remaining"), rs.getInt("credits"), rs.getInt("weekday"),
                rs.getInt("start_slot"), rs.getInt("end_slot"), rs.getString("exclusion_group"),
                rs.getObject("prerequisite_id", Long.class));
    }
}
