package ai.dvine.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DVINE's own online model: a model too big for a phone (Qwen3 14B, 32B…) runs on the server under llama.cpp's
 * {@code llama-server}, and signed-in phones use it like any API model, in the OpenAI chat-completions form. This
 * relays the request to that server (the model's name and the reply length are the server's to set) and streams the
 * reply back. Off until {@code dvine.llm.url} is set: then the routes say so with 503 instead of failing.
 */
@RestController
public class ServerModelController {
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final HttpClient http;
    private final String url, model, key;
    private final int maxTokens, perMinute, timeoutSeconds, window;
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    public ServerModelController(ObjectMapper json, JdbcTemplate jdbc,
                                 @Value("${dvine.llm.url:}") String url,
                                 @Value("${dvine.llm.model:qwen3-14b}") String model,
                                 @Value("${dvine.llm.key:}") String key,
                                 @Value("${dvine.llm.max-tokens:8192}") int maxTokens,
                                 @Value("${dvine.llm.context:16384}") int window,
                                 @Value("${dvine.llm.requests-per-minute:30}") int perMinute,
                                 @Value("${dvine.llm.timeout-seconds:600}") int timeoutSeconds) {
        this.json = json;
        this.jdbc = jdbc;
        this.url = url == null ? "" : url.trim().replaceAll("/+$", "");
        this.model = model;
        this.key = key == null ? "" : key.trim();
        this.maxTokens = maxTokens;
        this.window = window;
        this.perMinute = perMinute;
        this.timeoutSeconds = timeoutSeconds;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    boolean on() {
        return !url.isEmpty();
    }

    /** The server's model, as the OpenAI list of models (one), with its context window. */
    @GetMapping("/api/v1/llm/models")
    public ResponseEntity<Object> models() {
        if (!on()) {
            return off();
        }
        return ResponseEntity.ok(Map.of("object", "list", "data",
                List.of(Map.of("id", model, "object", "model", "owned_by", "dvine", "context_window", window))));
    }

    /** One chat turn: relayed to the model server, streamed back when the phone asks for a stream. */
    @PostMapping("/api/v1/llm/chat/completions")
    public ResponseEntity<StreamingResponseBody> chat(Authentication auth, @RequestBody JsonNode body) throws IOException, InterruptedException {
        // one return type for every answer: Spring streams only a reply declared as a StreamingResponseBody
        if (!on()) {
            return json(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED);
        }
        String user = (String) auth.getPrincipal();
        if (!allow(user)) {
            audit(user, "deny", "rate");
            return json(HttpStatus.TOO_MANY_REQUESTS, error("too many requests to the server's model; wait a minute", "rate_limit"));
        }
        if (!body.isObject() || !body.path("messages").isArray() || body.path("messages").isEmpty()) {
            return json(HttpStatus.BAD_REQUEST, error("messages are required", "invalid_request_error"));
        }
        ObjectNode ask = ((ObjectNode) body).deepCopy();
        ask.put("model", model); // the server decides which model answers
        int asked = Math.max(ask.path("max_tokens").asInt(0), ask.path("max_completion_tokens").asInt(0));
        ask.remove("max_completion_tokens");
        ask.put("max_tokens", asked <= 0 ? maxTokens : Math.min(asked, maxTokens));
        ask.remove("reasoning_effort"); // OpenAI-only fields llama-server doesn't take
        ask.remove("store");
        boolean stream = ask.path("stream").asBoolean(false);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(ask)));
        if (!key.isEmpty()) {
            req.header("Authorization", "Bearer " + key);
        }
        audit(user, "allow", model);
        HttpResponse<InputStream> r;
        try {
            r = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            return json(HttpStatus.BAD_GATEWAY, error("the server's model isn't answering", "upstream_error"));
        }
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            try (InputStream in = r.body()) {
                in.readNBytes(4096); // its own message may name internals: the phone gets a plain one
            }
            return json(HttpStatus.BAD_GATEWAY, error("the server's model said " + r.statusCode(), "upstream_error"));
        }
        if (!stream) {
            final byte[] whole;
            try (InputStream in = r.body()) {
                whole = in.readAllBytes();
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(o -> o.write(whole));
        }
        final InputStream in = r.body();
        StreamingResponseBody out = o -> {
            try (in) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    o.flush(); // each piece reaches the phone as it is written
                }
            }
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("Cache-Control", "no-cache").body(out);
    }

    private static final Map<String, Object> NOT_CONFIGURED =
            error("this server has no model of its own yet (set DVINE_LLM_URL)", "not_configured");

    private ResponseEntity<Object> off() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(NOT_CONFIGURED);
    }

    private ResponseEntity<StreamingResponseBody> json(HttpStatus status, Object body) throws IOException {
        final byte[] bytes = json.writeValueAsBytes(body);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(o -> o.write(bytes));
    }

    private static Map<String, Object> error(String message, String type) {
        return Map.of("error", Map.of("message", message, "type", type));
    }

    /** At most {@code perMinute} requests a minute for each user. */
    boolean allow(String user) {
        long now = System.currentTimeMillis();
        Deque<Long> q = recent.computeIfAbsent(user, u -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) {
                q.removeFirst();
            }
            if (q.size() >= perMinute) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }

    private void audit(String user, String decision, String what) {
        jdbc.update("insert into audit_events (actor, owner_user_id, action, decision, object_ref, created_at) "
                + "values (?, ?, 'SERVER_MODEL', ?, ?, ?)", user, user, decision, what, Timestamp.from(Instant.now()));
    }
}
