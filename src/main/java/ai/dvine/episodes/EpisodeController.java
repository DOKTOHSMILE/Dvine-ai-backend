package ai.dvine.episodes;

import ai.dvine.deskroom.DeskRoomController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Secondary brain functions that live on the server: the long-term episode
 * archive and its consolidation into a small profile the phone pulls back.
 * The phone's engine keeps working without them.
 */
@RestController
public class EpisodeController {
    public record Episode(@NotBlank @Size(max = 64) String id,
                          @NotBlank @Size(max = 16) String kind,
                          @NotBlank @Size(max = 64) String tag,
                          @NotNull @Size(max = 500) String summary,
                          @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double importance,
                          Long occurredAt) {
    }

    public record SyncRequest(@NotNull Integer schemaVersion,
                              @NotBlank @Size(max = 128) String deviceId,
                              @NotNull @Size(max = 200) List<@Valid Episode> episodes) {
    }

    private final EpisodeService episodes;

    public EpisodeController(EpisodeService episodes) {
        this.episodes = episodes;
    }

    @PostMapping("/api/v1/episodes/sync")
    public ResponseEntity<Map<String, Object>> sync(Authentication auth, @Valid @RequestBody SyncRequest body) {
        if (body.schemaVersion() != 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported schemaVersion"));
        }
        EpisodeService.Result r = episodes.intake(DeskRoomController.user(auth), body.deviceId(), body.episodes());
        return ResponseEntity.ok(Map.of("accepted", r.accepted(), "rejected", r.rejected()));
    }

    @GetMapping("/api/v1/episodes/profile")
    public Map<String, Object> profile(Authentication auth) {
        return episodes.profile(DeskRoomController.user(auth));
    }
}
