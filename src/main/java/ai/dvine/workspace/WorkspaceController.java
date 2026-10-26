package ai.dvine.workspace;

import ai.dvine.deskroom.DeskRoomController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** The user's Linux workspace on the server: run code, and read or write its files. */
@RestController
public class WorkspaceController {
    public record RunRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_.:\\-]{8,80}") String requestId,
            @NotBlank @Pattern(regexp = "bash|python|node") String language,
            @NotBlank @Size(max = 20000) String code,
            boolean network) {
    }

    private final WorkspaceService service;
    private final Workspaces files;

    public WorkspaceController(WorkspaceService service, Workspaces files) {
        this.service = service;
        this.files = files;
    }

    @GetMapping("/api/v1/workspace")
    public Map<String, Object> status() {
        return service.status();
    }

    @PostMapping("/api/v1/workspace/runs")
    public ResponseEntity<Map<String, Object>> run(Authentication auth, @Valid @RequestBody RunRequest body) throws IOException {
        Map<String, Object> v = service.submit(DeskRoomController.user(auth), body.requestId(), body.language(), body.code(),
                body.network());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(v);
    }

    @GetMapping("/api/v1/workspace/runs/{id}")
    public Map<String, Object> get(Authentication auth, @PathVariable("id") String id) {
        return service.view(DeskRoomController.user(auth), id);
    }

    @GetMapping("/api/v1/workspace/files")
    public Map<String, List<Map<String, Object>>> list(Authentication auth) throws IOException {
        return Map.of("files", files.list(DeskRoomController.user(auth)));
    }

    @GetMapping(value = "/api/v1/workspace/files/content", produces = MediaType.TEXT_PLAIN_VALUE)
    public String read(Authentication auth, @RequestParam("path") String path) throws IOException {
        return files.read(DeskRoomController.user(auth), path);
    }

    @PutMapping(value = "/api/v1/workspace/files/content", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> write(Authentication auth, @RequestParam("path") String path, @RequestBody String text)
            throws IOException {
        if (text.length() > Workspaces.MAX_READ) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        files.write(DeskRoomController.user(auth), path, text);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/workspace/files/content")
    public ResponseEntity<Void> delete(Authentication auth, @RequestParam("path") String path) throws IOException {
        return files.delete(DeskRoomController.user(auth), path) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
