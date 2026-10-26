package ai.dvine.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caps how many new questions one user may send per minute, so a stuck
 * client (or a stolen token) can't run up model costs. Replays of a request
 * id are free: they return the stored answer without running anything.
 */
@Component
public class CommandLimiter {
    private final int perMinute;
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    public CommandLimiter(@Value("${dvine.commands-per-minute:20}") int perMinute) {
        this.perMinute = perMinute;
    }

    /** Records one question for this user; false when the user is over the limit. */
    public boolean allow(String userId) {
        long now = System.currentTimeMillis();
        Deque<Long> q = recent.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 60_000L) {
                q.pollFirst();
            }
            if (q.size() >= perMinute) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }
}
