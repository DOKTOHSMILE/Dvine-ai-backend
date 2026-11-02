package ai.dvine.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Accepts code runs for a user's sandbox, runs them a few at a time in the
 * background, and keeps the results owner-scoped. A run is idempotent by the
 * client's request id; each user has an hourly allowance.
 */
@Service
public class WorkspaceService {
    public static final Set<String> LANGUAGES = Set.of("bash", "python", "node");
    static final int MAX_CODE = 20_000;

    private final JdbcTemplate jdbc;
    private final SandboxRunner runner;
    private final Workspaces workspaces;
    private final Duration timeout;
    private final int perHour;
    private final boolean networkAllowed;
    private final ThreadPoolExecutor pool;
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    public WorkspaceService(JdbcTemplate jdbc, SandboxRunner runner, Workspaces workspaces,
                            @Value("${dvine.workspace.timeout-seconds:30}") int timeoutSeconds,
                            @Value("${dvine.workspace.runs-per-hour:30}") int perHour,
                            @Value("${dvine.workspace.concurrency:2}") int concurrency) {
        this.jdbc = jdbc;
        this.runner = runner;
        this.workspaces = workspaces;
        this.timeout = Duration.ofSeconds(Math.max(1, Math.min(120, timeoutSeconds)));
        this.perHour = perHour;
        this.networkAllowed = !(runner instanceof DockerRunner d) || d.networkConfigured();
        this.pool = new ThreadPoolExecutor(concurrency, concurrency, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20),
                r -> {
                    Thread t = new Thread(r, "dvine-sandbox");
                    t.setDaemon(true);
                    return t;
                });
    }

    public Map<String, Object> submit(String userId, String requestId, String language, String code, boolean network)
            throws IOException {
        if (!LANGUAGES.contains(language) || code == null || code.isBlank() || code.length() > MAX_CODE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad_run");
        }
        List<Map<String, Object>> existing = jdbc.queryForList(
                "select id from workspace_runs where owner_user_id = ? and request_id = ?", userId, requestId);
        if (!existing.isEmpty()) {
            return view(userId, (String) existing.get(0).get("id"));
        }
        if (!runner.available()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "sandbox_unavailable");
        }
        if (workspaces.overQuota(userId)) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "quota");
        }
        if (!allow(userId)) {
            audit(userId, "deny", "rate_limited");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "slow_down");
        }
        boolean net = network && networkAllowed;
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("insert into workspace_runs (id, owner_user_id, request_id, language, code, network, state, created_at) "
                    + "values (?, ?, ?, ?, ?, ?, 'QUEUED', ?)", id, userId, requestId, language, code, net, Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException raced) {
            return submit(userId, requestId, language, code, network);
        }
        audit(userId, "allow", "run=" + id + ",lang=" + language + ",net=" + net);
        SandboxRunner.RunSpec spec = new SandboxRunner.RunSpec(id, workspaces.dir(userId), language, code, net, timeout);
        try {
            pool.execute(() -> execute(spec));
        } catch (RejectedExecutionException busy) {
            finish(id, "FAILED", null, "", "The sandbox is busy. Try again in a minute.", false, 0L);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "busy");
        }
        return view(userId, id);
    }

    private void execute(SandboxRunner.RunSpec spec) {
        jdbc.update("update workspace_runs set state = 'RUNNING' where id = ?", spec.runId());
        try {
            SandboxRunner.RunResult r = runner.run(spec);
            String err = r.stderr() + (r.timedOut() ? "\n[stopped: time limit of " + spec.timeout().toSeconds() + " s]" : "")
                    + (r.truncated() ? "\n[output cut]" : "");
            finish(spec.runId(), "DONE", r.exitCode(), r.stdout(), err, r.timedOut(), r.durationMs());
        } catch (IOException | RuntimeException e) {
            finish(spec.runId(), "FAILED", null, "", "The sandbox could not run this.", false, 0L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(spec.runId(), "FAILED", null, "", "Stopped.", false, 0L);
        }
    }

    private void finish(String id, String state, Integer exit, String out, String err, boolean timedOut, long ms) {
        jdbc.update("update workspace_runs set state = ?, exit_code = ?, stdout = ?, stderr = ?, timed_out = ?, duration_ms = ?, "
                + "finished_at = ? where id = ?", state, exit, out, err, timedOut, ms, Timestamp.from(Instant.now()), id);
    }

    public Map<String, Object> view(String userId, String runId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, language, network, state, exit_code, stdout, stderr, timed_out, duration_ms from workspace_runs "
                        + "where id = ? and owner_user_id = ?", runId, userId);
        if (rows.isEmpty()) {
            Integer other = jdbc.queryForObject("select count(*) from workspace_runs where id = ?", Integer.class, runId);
            if (other != null && other > 0) {
                audit(userId, "deny", "read run " + runId);
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not_found");
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("runId", r.get("id"));
        v.put("language", r.get("language"));
        v.put("network", r.get("network"));
        v.put("state", r.get("state"));
        v.put("exitCode", r.get("exit_code"));
        v.put("stdout", r.get("stdout") == null ? "" : r.get("stdout"));
        v.put("stderr", r.get("stderr") == null ? "" : r.get("stderr"));
        v.put("timedOut", r.get("timed_out"));
        v.put("durationMs", r.get("duration_ms"));
        return v;
    }

    public Map<String, Object> status() {
        return Map.of("available", runner.available(), "languages", LANGUAGES, "network", networkAllowed,
                "timeoutSeconds", timeout.toSeconds());
    }

    private boolean allow(String userId) {
        long now = System.currentTimeMillis();
        Deque<Long> q = recent.computeIfAbsent(userId, k -> new ArrayDeque<>());
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

    private void audit(String userId, String decision, String ref) {
        jdbc.update("insert into audit_events (actor, owner_user_id, action, decision, object_ref, created_at) "
                + "values (?, ?, 'WORKSPACE_RUN', ?, ?, ?)", userId, userId, decision, ref, Timestamp.from(Instant.now()));
    }
}
