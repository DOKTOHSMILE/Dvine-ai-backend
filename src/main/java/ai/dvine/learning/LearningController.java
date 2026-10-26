package ai.dvine.learning;

import ai.dvine.deskroom.DeskRoomController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** S-L: consented offline learning from the phone, owner-scoped, into a review queue. */
@RestController
public class LearningController {
    public record Entry(@NotBlank @Size(max = 64) String id,
                        @NotBlank @Size(max = 24) String kind,
                        @NotNull @Size(max = 2000) String text,
                        @NotNull @Size(max = 2000) String answer,
                        Long createdAt) {
    }

    public record SyncRequest(@NotNull Integer schemaVersion,
                              @NotBlank @Size(max = 128) String deviceId,
                              @NotNull @Size(max = 100) List<@Valid Entry> entries) {
    }

    private final LearningService learning;

    public LearningController(LearningService learning) {
        this.learning = learning;
    }

    @PostMapping("/api/v1/learning/sync")
    public ResponseEntity<Map<String, Object>> sync(Authentication auth, @Valid @RequestBody SyncRequest body) {
        if (body.schemaVersion() != 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported schemaVersion"));
        }
        LearningService.Result r = learning.intake(DeskRoomController.user(auth), body.deviceId(), body.entries());
        return ResponseEntity.ok(Map.of("accepted", r.accepted(), "rejected", r.rejected()));
    }
}
