package ai.dvine.execution;

import ai.dvine.deskroom.DeskRoomController;
import ai.dvine.store.DvineStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TaskController {
    public record CommandRequest(
            @NotBlank @Size(min = 8, max = 80) @Pattern(regexp = "[A-Za-z0-9_.:\\-]{8,80}") String requestId,
            @NotBlank @Size(max = 4000) String text,
            @Size(max = 16) String activeAgent
    ) {
    }

    private final TaskService tasks;

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @PostMapping("/api/v1/desk-rooms/{id}/commands")
    public ResponseEntity<TaskService.TaskView> command(Authentication auth, @PathVariable("id") String roomId,
                                                         @Valid @RequestBody CommandRequest body) {
        String agent = body.activeAgent();
        if (agent != null && !agent.isBlank() && !"obi".equalsIgnoreCase(agent) && !"ivie".equalsIgnoreCase(agent)) {
            return ResponseEntity.badRequest().build();
        }
        TaskService.TaskView view = tasks.submit(DeskRoomController.user(auth), roomId, body.requestId(), body.text(), agent);
        return ResponseEntity.status(view.replay() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    @GetMapping("/api/v1/tasks/{id}")
    public TaskService.TaskView task(Authentication auth, @PathVariable("id") String id) {
        return tasks.get(DeskRoomController.user(auth), id);
    }

    @GetMapping("/api/v1/tasks/{id}/events")
    public List<DvineStore.EventRow> events(Authentication auth, @PathVariable("id") String id,
                                             @RequestParam(value = "after", defaultValue = "0") long after) {
        return tasks.events(DeskRoomController.user(auth), id, after);
    }
}
