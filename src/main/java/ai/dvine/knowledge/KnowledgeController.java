package ai.dvine.knowledge;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** S-K: public, read-only knowledge pack manifest and download. */
@RestController
public class KnowledgeController {
    private final KnowledgePackService packs;

    public KnowledgeController(KnowledgePackService packs) {
        this.packs = packs;
    }

    @GetMapping("/api/v1/knowledge/manifest")
    public ResponseEntity<KnowledgePackService.Manifest> manifest() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(10))).body(packs.manifest());
    }

    @GetMapping("/api/v1/knowledge/pack/{version}")
    public ResponseEntity<byte[]> pack(@PathVariable int version) {
        byte[] body = packs.pack(version);
        if (body == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(body);
    }
}
