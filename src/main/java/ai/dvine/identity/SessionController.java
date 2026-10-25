package ai.dvine.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class SessionController {
    public record SessionRequest(
            @NotBlank @Size(max = 128) String deviceId,
            @NotBlank @Size(max = 200) String bootstrap
    ) {
    }

    private final SessionService sessions;

    public SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/api/v1/sessions")
    public Map<String, String> create(@Valid @RequestBody SessionRequest body) {
        SessionService.Issued issued = sessions.bootstrap(body.deviceId(), body.bootstrap());
        return Map.of(
                "token", issued.token(),
                "userId", issued.userId(),
                "expiresAt", Instant.from(issued.expiresAt()).toString());
    }
}
