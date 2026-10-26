package ai.dvine.learning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stores learning entries as PENDING for review. The user comes only from the
 * session. Repeats are accepted without new rows. Anything that looks like a
 * secret is rejected here too, even though the phone already drops it.
 * Nothing is promoted into a shared pack automatically.
 */
@Service
public class LearningService {
    public record Result(List<String> accepted, List<Map<String, String>> rejected) {
    }

    private static final Set<String> KINDS = Set.of("TAUGHT_FACT", "CORRECTION", "UNANSWERED", "FEEDBACK");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d[\\d\\s-]{10,}\\d");
    private static final Pattern SECRET_WORDS = Pattern.compile(
            "\\b(password|passcode|pin|cvv|otp|seed phrase|private key|recovery phrase|secret key|api key|token)\\b");

    private final JdbcTemplate jdbc;

    public LearningService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Result intake(String ownerId, String deviceId, List<LearningController.Entry> entries) {
        List<String> accepted = new ArrayList<>();
        List<Map<String, String>> rejected = new ArrayList<>();
        Instant now = Instant.now();
        for (LearningController.Entry e : entries) {
            if (!KINDS.contains(e.kind())) {
                rejected.add(Map.of("id", e.id(), "reason", "unknown kind"));
                continue;
            }
            if (sensitive(e.text()) || sensitive(e.answer())) {
                rejected.add(Map.of("id", e.id(), "reason", "sensitive"));
                continue;
            }
            Integer seen = jdbc.queryForObject(
                    "select count(*) from learning_intake where owner_user_id = ? and entry_id = ?",
                    Integer.class, ownerId, e.id());
            if (seen == null || seen == 0) {
                try {
                    jdbc.update("insert into learning_intake (id, owner_user_id, entry_id, device_id, kind, text, answer, "
                                + "client_created_at, status, received_at) values (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)",
                        UUID.randomUUID().toString(), ownerId, e.id(), deviceId, e.kind(), e.text(), e.answer(),
                        e.createdAt() == null ? null : Timestamp.from(Instant.ofEpochMilli(e.createdAt())),
                        Timestamp.from(now));
                } catch (org.springframework.dao.DuplicateKeyException raced) {
                    // a concurrent retry stored it first: same outcome
                }
            }
            accepted.add(e.id());
        }
        jdbc.update("insert into audit_events (actor, owner_user_id, action, decision, object_ref, created_at) "
                        + "values ('phone', ?, 'LEARNING_SYNC', 'ALLOW', ?, ?)",
                ownerId, "accepted=" + accepted.size() + ",rejected=" + rejected.size(), Timestamp.from(now));
        return new Result(accepted, rejected);
    }

    static boolean sensitive(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return LONG_DIGITS.matcher(lower).find() || SECRET_WORDS.matcher(lower).find();
    }
}
