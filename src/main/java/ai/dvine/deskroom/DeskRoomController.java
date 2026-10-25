package ai.dvine.deskroom;

import ai.dvine.store.DvineStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
public class DeskRoomController {
    public record CreateRoom(@Size(max = 120) String title) {
    }

    private final DvineStore store;

    public DeskRoomController(DvineStore store) {
        this.store = store;
    }

    @PostMapping("/api/v1/desk-rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> create(Authentication auth, @Valid @RequestBody(required = false) CreateRoom body) {
        String userId = user(auth);
        String id = UUID.randomUUID().toString();
        String title = body == null || body.title() == null ? "" : body.title().trim();
        store.insertRoom(id, userId, title, Instant.now());
        return Map.of("deskRoomId", id);
    }

    @GetMapping("/api/v1/desk-rooms")
    public Map<String, Object> list(Authentication auth) {
        return Map.of("deskRoomIds", store.listRooms(user(auth)));
    }

    public static String user(Authentication auth) {
        return (String) auth.getPrincipal();
    }
}
