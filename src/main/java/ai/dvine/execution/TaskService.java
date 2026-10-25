package ai.dvine.execution;

import ai.dvine.agent.Deliberation;
import ai.dvine.store.DvineStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TaskService {
    public record TaskView(String taskId, String requestId, String state, String resultText, boolean replay) {
    }

    private final DvineStore store;
    private final Deliberation deliberation;

    public TaskService(DvineStore store, Deliberation deliberation) {
        this.store = store;
        this.deliberation = deliberation;
    }

    @Transactional
    public TaskView submit(String userId, String roomId, String requestId, String text, String activeAgent) {
        if (!store.roomOwned(roomId, userId)) {
            store.audit(userId, userId, "task.submit", "deny", roomId, Instant.now());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not_found");
        }
        var existing = store.findByIdempotency(userId, requestId);
        if (existing.isPresent()) {
            return new TaskView(existing.get().id(), requestId, existing.get().state(), existing.get().resultText(), true);
        }
        String taskId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        try {
            store.insertTask(taskId, userId, roomId, requestId, text, now);
        } catch (DuplicateKeyException ex) {
            var raced = store.findByIdempotency(userId, requestId).orElseThrow();
            return new TaskView(raced.id(), requestId, raced.state(), raced.resultText(), true);
        }
        Deliberation.Script script = deliberation.plan(text, activeAgent);
        List<Mark> marks = new ArrayList<>();
        marks.add(new Mark("RECEIVED", null, "STATUS", "Received"));
        marks.add(new Mark("AUTHORIZED", null, "STATUS", "Session accepted"));
        marks.add(new Mark("UNDERSTOOD", null, "STATUS", script.kind().name().toLowerCase()));
        marks.add(new Mark("PLAN_CREATED", null, "STATUS", planLabel(script)));
        marks.add(new Mark("POLICY_CHECK", null, "STATUS", policyLabel(script)));
        marks.add(new Mark("QUEUED", null, "STATUS", "Queued"));
        for (Deliberation.Line line : script.lines()) {
            marks.add(new Mark("EXECUTING", line.agent(), line.cue(), clip(line.message())));
        }
        marks.add(new Mark("VERIFYING", null, "STATUS", "No tool was called."));
        marks.add(new Mark("COMPLETED", null, "STATUS", "Completed"));
        String state = "RECEIVED";
        for (Mark mark : marks) {
            state = mark.state;
            store.insertEvent(taskId, userId, mark.state, mark.agent, mark.cue, mark.message, Instant.now());
        }
        store.updateTask(taskId, userId, state, script.result(), Instant.now());
        return new TaskView(taskId, requestId, state, script.result(), false);
    }

    public TaskView get(String userId, String taskId) {
        var row = store.findTask(taskId, userId);
        if (row.isEmpty()) {
            if (store.taskExists(taskId)) {
                store.audit(userId, userId, "task.read", "deny", taskId, Instant.now());
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not_found");
        }
        return new TaskView(row.get().id(), "", row.get().state(), row.get().resultText(), false);
    }

    public List<DvineStore.EventRow> events(String userId, String taskId, long after) {
        if (store.findTask(taskId, userId).isEmpty()) {
            if (store.taskExists(taskId)) {
                store.audit(userId, userId, "task.events", "deny", taskId, Instant.now());
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not_found");
        }
        return store.eventsAfter(taskId, userId, after);
    }

    private static String planLabel(Deliberation.Script script) {
        return switch (script.kind()) {
            case SIMPLE -> "Simple request. One voice.";
            case BLOCKED -> "Consequential request. No side effect will run.";
            case RUSH -> "Disagreement. One rebuttal, then a joint close.";
            case COMPLEX -> "Both voices. One turn each, then a joint close.";
        };
    }

    private static String policyLabel(Deliberation.Script script) {
        if (script.kind() == Deliberation.Kind.BLOCKED) {
            return "Tier 2 would require approval. None is executed.";
        }
        return "Tier 0. No side effect.";
    }

    private static String clip(String message) {
        if (message == null) {
            return "";
        }
        String clean = message.replace('\u0000', ' ').trim();
        return clean.length() <= 500 ? clean : clean.substring(0, 500);
    }

    private record Mark(String state, String agent, String cue, String message) {
    }
}
