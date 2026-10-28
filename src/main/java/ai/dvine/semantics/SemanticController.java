package ai.dvine.semantics;

import ai.dvine.deskroom.DeskRoomController;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The phone can ask the desk for a second reading. The desk does not execute tools. */
@RestController
public class SemanticController {
    public record Request(@NotBlank @Size(max = 2000) String text) {
    }

    private final SemanticInterpretationService semantics;

    public SemanticController(SemanticInterpretationService semantics) {
        this.semantics = semantics;
    }

    @PostMapping("/api/v1/interpret")
    public SemanticInterpretationService.Reading interpret(Authentication auth, @RequestBody Request body) {
        DeskRoomController.user(auth);
        return semantics.interpret(body.text());
    }
}
