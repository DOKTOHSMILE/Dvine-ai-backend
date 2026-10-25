package ai.dvine.realtime;

import ai.dvine.execution.TaskService;
import ai.dvine.identity.SessionService;
import ai.dvine.store.DvineStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class TaskWebSocketHandler extends TextWebSocketHandler {
    private final SessionService sessions;
    private final TaskService tasks;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "dvine-ws-auth");
        thread.setDaemon(true);
        return thread;
    });

    public TaskWebSocketHandler(SessionService sessions, TaskService tasks, ObjectMapper mapper) {
        this.sessions = sessions;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        scheduler.schedule(() -> {
            if (!Boolean.TRUE.equals(session.getAttributes().get("auth")) && session.isOpen()) {
                try {
                    session.close(CloseStatus.POLICY_VIOLATION);
                } catch (Exception ignored) {
                    // already gone
                }
            }
        }, 5, TimeUnit.SECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (message.getPayloadLength() > 8192) {
            session.close(CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        JsonNode node = mapper.readTree(message.getPayload());
        String op = node.path("op").asText("");
        if (!Boolean.TRUE.equals(session.getAttributes().get("auth"))) {
            if (!"auth".equals(op)) {
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            var user = sessions.userForRawToken(node.path("token").asText(""));
            if (user.isEmpty()) {
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            session.getAttributes().put("auth", true);
            session.getAttributes().put("userId", user.get());
            session.sendMessage(new TextMessage("{\"op\":\"auth\",\"ok\":true}"));
            return;
        }
        if (!"subscribe".equals(op)) {
            session.sendMessage(new TextMessage("{\"error\":\"bad_op\"}"));
            return;
        }
        String userId = (String) session.getAttributes().get("userId");
        String taskId = node.path("taskId").asText("");
        long after = node.path("after").asLong(0);
        try {
            for (DvineStore.EventRow row : tasks.events(userId, taskId, after)) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
                        "eventId", row.eventId(),
                        "taskId", row.taskId(),
                        "state", row.state(),
                        "agent", row.agent() == null ? "" : row.agent(),
                        "cue", row.cue(),
                        "message", row.message(),
                        "createdAt", row.createdAt().toString()
                ))));
            }
        } catch (ResponseStatusException ex) {
            session.sendMessage(new TextMessage("{\"error\":\"not_found\"}"));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // do not log payloads; they can carry the session token
    }
}
