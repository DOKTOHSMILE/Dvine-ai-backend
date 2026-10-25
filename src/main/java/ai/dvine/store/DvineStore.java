package ai.dvine.store;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class DvineStore {
    public record TaskRow(String id, String state, String resultText, boolean replay) {
    }

    public record EventRow(long eventId, String taskId, String state, String agent, String cue, String message, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    public DvineStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertUser(String id, Instant now) {
        jdbc.update("insert into users (id, status, created_at) values (?, 'ACTIVE', ?)", id, Timestamp.from(now));
    }

    public void insertSession(String id, String userId, String tokenHash, String deviceId, Instant expiresAt) {
        jdbc.update(
                "insert into sessions (id, user_id, token_hash, device_id, expires_at) values (?, ?, ?, ?, ?)",
                id, userId, tokenHash, deviceId, Timestamp.from(expiresAt));
    }

    public Optional<String> userForToken(String tokenHash, Instant now) {
        List<String> rows = jdbc.query(
                "select user_id from sessions where token_hash = ? and revoked_at is null and expires_at > ?",
                (rs, n) -> rs.getString(1),
                tokenHash, Timestamp.from(now));
        return rows.stream().findFirst();
    }

    public void insertRoom(String id, String ownerId, String title, Instant now) {
        jdbc.update(
                "insert into desk_rooms (id, owner_user_id, title, created_at) values (?, ?, ?, ?)",
                id, ownerId, title, Timestamp.from(now));
    }

    public List<String> listRooms(String ownerId) {
        return jdbc.query(
                "select id from desk_rooms where owner_user_id = ? order by created_at",
                (rs, n) -> rs.getString(1),
                ownerId);
    }

    public boolean roomOwned(String roomId, String ownerId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from desk_rooms where id = ? and owner_user_id = ?",
                Integer.class, roomId, ownerId);
        return n != null && n > 0;
    }

    public Optional<TaskRow> findByIdempotency(String ownerId, String key) {
        List<TaskRow> rows = jdbc.query(
                "select id, state, result_text from tasks where owner_user_id = ? and idempotency_key = ?",
                (rs, n) -> new TaskRow(rs.getString(1), rs.getString(2), rs.getString(3), true),
                ownerId, key);
        return rows.stream().findFirst();
    }

    public void insertTask(String id, String ownerId, String roomId, String requestId, String text, Instant now) {
        jdbc.update("""
                insert into tasks
                (id, owner_user_id, desk_room_id, request_id, state, input_text, idempotency_key, created_at, updated_at)
                values (?, ?, ?, ?, 'RECEIVED', ?, ?, ?, ?)
                """, id, ownerId, roomId, requestId, text, requestId, Timestamp.from(now), Timestamp.from(now));
    }

    public void updateTask(String id, String ownerId, String state, String result, Instant now) {
        jdbc.update(
                "update tasks set state = ?, result_text = ?, updated_at = ? where id = ? and owner_user_id = ?",
                state, result, Timestamp.from(now), id, ownerId);
    }

    public Optional<TaskRow> findTask(String id, String ownerId) {
        List<TaskRow> rows = jdbc.query(
                "select id, state, result_text from tasks where id = ? and owner_user_id = ?",
                (rs, n) -> new TaskRow(rs.getString(1), rs.getString(2), rs.getString(3), false),
                id, ownerId);
        return rows.stream().findFirst();
    }

    public boolean taskExists(String id) {
        Integer n = jdbc.queryForObject("select count(*) from tasks where id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    public void insertEvent(String taskId, String ownerId, String state, String agent, String cue, String message, Instant now) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into task_events (task_id, owner_user_id, state, agent, cue, message, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"event_id"});
            ps.setString(1, taskId);
            ps.setString(2, ownerId);
            ps.setString(3, state);
            ps.setString(4, agent);
            ps.setString(5, cue);
            ps.setString(6, message);
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, keys);
    }

    public List<EventRow> eventsAfter(String taskId, String ownerId, long after) {
        return jdbc.query("""
                select event_id, task_id, state, agent, cue, message, created_at
                from task_events
                where task_id = ? and owner_user_id = ? and event_id > ?
                order by event_id
                """, (rs, n) -> new EventRow(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getTimestamp(7).toInstant()), taskId, ownerId, after);
    }

    public int countTasks() {
        Integer n = jdbc.queryForObject("select count(*) from tasks", Integer.class);
        return n == null ? 0 : n;
    }

    public void audit(String actor, String ownerId, String action, String decision, String objectRef, Instant now) {
        jdbc.update(
                "insert into audit_events (actor, owner_user_id, action, decision, object_ref, created_at) values (?, ?, ?, ?, ?, ?)",
                actor, ownerId, action, decision, objectRef, Timestamp.from(now));
    }

    public int countAudit(String decision) {
        try {
            Integer n = jdbc.queryForObject(
                    "select count(*) from audit_events where decision = ?", Integer.class, decision);
            return n == null ? 0 : n;
        } catch (EmptyResultDataAccessException ex) {
            return 0;
        }
    }
}
