package ai.dvine.research;

import ai.dvine.deskroom.DeskRoomController;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ResearchController {
    public record Request(@NotBlank @Size(max = 300) String topic, boolean online) {
    }

    private final ResearchService research;

    public ResearchController(ResearchService research) {
        this.research = research;
    }

    @PostMapping("/api/v1/research")
    public ResearchService.Note research(Authentication auth, @RequestBody Request body) {
        DeskRoomController.user(auth);
        return research.research(body.topic(), body.online());
    }
}
