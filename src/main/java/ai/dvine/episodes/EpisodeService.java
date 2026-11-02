package ai.dvine.episodes;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stores episodes per owner (idempotent by the phone's id) and consolidates
 * them into favourites: the poses, scenes, objects and persona this user
 * asks for most, and which body parts they touch. Consolidation is a server
 * job because it needs the whole history; the phone only keeps recent events.
 */
@Service
public class EpisodeService {
    public record Result(List<String> accepted, List<Map<String, String>> rejected) {
    }

    static final Set<String> KINDS = Set.of("ACTION", "SCENE", "TOUCH", "PERSONA", "FAILURE", "LEARN", "TALK");
    private static final Pattern TAG = Pattern.compile("[a-z]+:[a-z0-9 _-]{1,56}");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d[\\d\\s-]{10,}\\d");
    private static final Pattern SECRET_WORDS = Pattern.compile(
            "\\b(password|passcode|pin|cvv|otp|seed phrase|private key|recovery phrase|secret key|api key|token)\\b");
    /** Consolidation looks back this far. */
    static final Duration WINDOW = Duration.ofDays(90);

    private final JdbcTemplate jdbc;

    public EpisodeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Result intake(String ownerId, String deviceId, List<EpisodeController.Episode> list) {
        List<String> accepted = new ArrayList<>();
        List<Map<String, String>> rejected = new ArrayList<>();
        Instant now = Instant.now();
        for (EpisodeController.Episode e : list) {
            if (!KINDS.contains(e.kind())) {
                rejected.add(Map.of("id", e.id(), "reason", "unknown kind"));
                continue;
            }
            if (!TAG.matcher(e.tag()).matches()) {
                rejected.add(Map.of("id", e.id(), "reason", "bad tag"));
                continue;
            }
            if (sensitive(e.summary())) {
                rejected.add(Map.of("id", e.id(), "reason", "sensitive"));
                continue;
            }
            Integer seen = jdbc.queryForObject("select count(*) from episodes where owner_user_id = ? and entry_id = ?",
                    Integer.class, ownerId, e.id());
            if (seen == null || seen == 0) {
                try {
                    jdbc.update("insert into episodes (id, owner_user_id, entry_id, device_id, kind, tag, summary, importance, "
                                    + "occurred_at, received_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID().toString(), ownerId, e.id(), deviceId, e.kind(), e.tag(), e.summary(),
                            e.importance(), e.occurredAt() == null ? null : Timestamp.from(Instant.ofEpochMilli(e.occurredAt())),
                            Timestamp.from(now));
                } catch (org.springframework.dao.DuplicateKeyException raced) {
                    // a concurrent retry stored it first: same outcome
                }
            }
            accepted.add(e.id());
        }
        jdbc.update("insert into audit_events (actor, owner_user_id, action, decision, object_ref, created_at) "
                        + "values ('phone', ?, 'EPISODE_SYNC', 'ALLOW', ?, ?)",
                ownerId, "accepted=" + accepted.size() + ",rejected=" + rejected.size(), Timestamp.from(now));
        return new Result(accepted, rejected);
    }

    /**
     * The consolidated profile: for each kind of tag (pose, scene, object,
     * touch, persona) the three most frequent values in the last 90 days, plus
     * totals. Only this owner's episodes are read.
     */
    public Map<String, Object> profile(String ownerId) {
        Timestamp since = Timestamp.from(Instant.now().minus(WINDOW));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select tag, count(*) as n from episodes where owner_user_id = ? and received_at >= ? "
                        + "group by tag order by n desc, tag asc", ownerId, since);
        Map<String, List<String>> favourites = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String tag = String.valueOf(row.get("tag"));
            int colon = tag.indexOf(':');
            String group = tag.substring(0, colon);
            List<String> top = favourites.computeIfAbsent(group, k -> new ArrayList<>());
            if (top.size() < 3) {
                top.add(tag.substring(colon + 1));
            }
        }
        Integer total = jdbc.queryForObject("select count(*) from episodes where owner_user_id = ?", Integer.class, ownerId);
        Integer failures = jdbc.queryForObject(
                "select count(*) from episodes where owner_user_id = ? and kind = 'FAILURE' and received_at >= ?",
                Integer.class, ownerId, since);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", 1);
        out.put("episodes", total == null ? 0 : total);
        out.put("recentFailures", failures == null ? 0 : failures);
        out.put("favourites", favourites);
        return out;
    }

    static boolean sensitive(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return LONG_DIGITS.matcher(lower).find() || SECRET_WORDS.matcher(lower).find();
    }
}
