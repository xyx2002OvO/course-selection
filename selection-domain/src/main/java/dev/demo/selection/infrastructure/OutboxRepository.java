package dev.demo.selection.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxRepository {
    public record Event(long id, String kind, String key, String payload, int attempts, String claim) { }
    private final JdbcTemplate jdbc;
    public OutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public List<Event> claim(int limit) {
        int size = Math.max(1, limit);
        String claim = UUID.randomUUID().toString();
        List<Event> events = jdbc.query("""
            SELECT * FROM outbox_event
            WHERE (state='NEW' AND next_attempt_at<=CURRENT_TIMESTAMP(6))
               OR (state='IN_FLIGHT' AND lease_until<=CURRENT_TIMESTAMP(6))
            ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
            """, (rs, n) -> new Event(rs.getLong("id"), rs.getString("kind"), rs.getString("message_key"),
                rs.getString("payload"), rs.getInt("attempts"), claim), size);
        for (Event event : events) {
            jdbc.update("""
                UPDATE outbox_event SET state='IN_FLIGHT',claim_token=?,
                  lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=?
                """, claim, event.id());
        }
        return events;
    }

    public int sent(String claim, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        Object[] args = new Object[ids.size() + 1];
        args[0] = claim;
        int i = 1;
        for (Long id : ids) args[i++] = id;
        return jdbc.update("UPDATE outbox_event SET state='SENT',lease_until=NULL WHERE claim_token=? AND id IN ("
                + placeholders + ")", args);
    }

    public void retry(Event event, Exception failure) {
        int seconds = Math.min(60, 1 << Math.min(event.attempts(), 6));
        String message = failure.toString();
        jdbc.update("""
            UPDATE outbox_event SET state='NEW',attempts=attempts+1,lease_until=NULL,last_error=?,
              next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) WHERE id=? AND claim_token=?
            """, message.substring(0, Math.min(500, message.length())), seconds, event.id(), event.claim());
    }
}
