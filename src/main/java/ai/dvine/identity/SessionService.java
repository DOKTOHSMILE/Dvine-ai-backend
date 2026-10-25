package ai.dvine.identity;

import ai.dvine.store.DvineStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionService {
    public record Issued(String token, String userId, Instant expiresAt) {
    }

    private final DvineStore store;
    private final boolean bootstrapEnabled;
    private final String bootstrapToken;
    private final SecureRandom random = new SecureRandom();

    public SessionService(DvineStore store,
                          @Value("${dvine.bootstrap-enabled:false}") boolean bootstrapEnabled,
                          @Value("${dvine.bootstrap-token:}") String bootstrapToken) {
        this.store = store;
        this.bootstrapEnabled = bootstrapEnabled;
        this.bootstrapToken = bootstrapToken == null ? "" : bootstrapToken;
    }

    @Transactional
    public Issued bootstrap(String deviceId, String presented) {
        if (!bootstrapEnabled || bootstrapToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "bootstrap_disabled");
        }
        if (!constantTime(bootstrapToken, presented)) {
            store.audit("anonymous", null, "session.bootstrap", "deny", "bad_token", Instant.now());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthorized");
        }
        String device = deviceId == null ? "" : deviceId.trim();
        if (device.isEmpty() || device.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad_device");
        }
        Instant now = Instant.now();
        String userId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expires = now.plusSeconds(12 * 60 * 60);
        store.insertUser(userId, now);
        store.insertSession(sessionId, userId, sha256(token), device, expires);
        store.audit(userId, userId, "session.bootstrap", "allow", sessionId, now);
        return new Issued(token, userId, expires);
    }

    public Optional<String> userForRawToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return store.userForToken(sha256(raw), Instant.now());
    }

    static String sha256(String raw) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean constantTime(String expected, String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
