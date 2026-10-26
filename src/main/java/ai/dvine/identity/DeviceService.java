package ai.dvine.identity;

import ai.dvine.store.DvineStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Device sign-in without any secret shipped in the app: a phone registers
 * once (rate-limited per address) and receives its own device secret, then
 * exchanges it for 12-hour session tokens. Each device is its own user.
 */
@Service
public class DeviceService {
    public record Registered(String userId, String deviceSecret) {
    }

    private final JdbcTemplate jdbc;
    private final DvineStore store;
    private final SessionService sessions;
    private final boolean enabled;
    private final int perHour;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public DeviceService(JdbcTemplate jdbc, DvineStore store, SessionService sessions,
                         @Value("${dvine.device-registration-enabled:true}") boolean enabled,
                         @Value("${dvine.device-registrations-per-hour:10}") int perHour) {
        this.jdbc = jdbc;
        this.store = store;
        this.sessions = sessions;
        this.enabled = enabled;
        this.perHour = perHour;
    }

    @Transactional
    public Registered register(String deviceId, String remote) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "registration_disabled");
        }
        String device = clean(deviceId);
        if (!allow(remote)) {
            store.audit("anonymous", null, "device.register", "deny", "rate_limited", Instant.now());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "slow_down");
        }
        Integer taken = jdbc.queryForObject("select count(*) from devices where device_id = ?", Integer.class, device);
        if (taken != null && taken > 0) {
            // a registered device keeps its secret; nobody else can take its identity
            throw new ResponseStatusException(HttpStatus.CONFLICT, "device_registered");
        }
        Instant now = Instant.now();
        String userId = UUID.randomUUID().toString();
        String secret = randomToken();
        store.insertUser(userId, now);
        jdbc.update("insert into devices (id, user_id, device_id, secret_hash, created_at) values (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), userId, device, SessionService.sha256(secret), Timestamp.from(now));
        store.audit(userId, userId, "device.register", "allow", device, now);
        return new Registered(userId, secret);
    }

    @Transactional
    public SessionService.Issued signIn(String deviceId, String deviceSecret) {
        String device = clean(deviceId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select user_id, secret_hash from devices where device_id = ? and revoked_at is null", device);
        if (rows.isEmpty() || deviceSecret == null
                || !MessageDigest.isEqual(((String) rows.get(0).get("secret_hash")).getBytes(),
                SessionService.sha256(deviceSecret).getBytes())) {
            store.audit("anonymous", null, "device.signin", "deny", "bad_secret", Instant.now());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthorized");
        }
        return sessions.issue((String) rows.get(0).get("user_id"), device);
    }

    private boolean allow(String remote) {
        long now = System.currentTimeMillis();
        Deque<Long> q = attempts.computeIfAbsent(remote == null ? "?" : remote, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 3_600_000L) {
                q.pollFirst();
            }
            if (q.size() >= perHour) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }

    private static String clean(String deviceId) {
        String d = deviceId == null ? "" : deviceId.trim();
        if (d.isEmpty() || d.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad_device");
        }
        return d;
    }

    private String randomToken() {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
