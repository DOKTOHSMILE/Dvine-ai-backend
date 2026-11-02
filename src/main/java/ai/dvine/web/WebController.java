package ai.dvine.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/** "Look this up": the server reads a page through the egress proxy and returns its text. */
@RestController
public class WebController {
    public record FetchRequest(@NotBlank @Size(max = 2000) String url) {
    }

    private final WebFetchService web;
    private final JdbcTemplate jdbc;

    public WebController(WebFetchService web, JdbcTemplate jdbc) {
        this.web = web;
        this.jdbc = jdbc;
    }

    @PostMapping("/api/v1/web/fetch")
    public Map<String, Object> fetch(Authentication auth, @Valid @RequestBody FetchRequest body) {
        String user = (String) auth.getPrincipal();
        String host = WebFetchService.parse(body.url()).getHost();
        jdbc.update("insert into audit_events (actor, owner_user_id, action, decision, object_ref, created_at) "
                + "values (?, ?, 'WEB_FETCH', 'allow', ?, ?)", user, user, host, Timestamp.from(Instant.now()));
        return web.fetch(body.url());
    }
}
