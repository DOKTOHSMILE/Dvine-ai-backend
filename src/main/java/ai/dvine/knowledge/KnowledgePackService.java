package ai.dvine.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The base knowledge pack the phone ships with, served for updates. It holds
 * no user data. Loaded once; the manifest carries its version and SHA-256 so
 * the phone can verify the download before installing it.
 */
@Service
public class KnowledgePackService {
    public record Manifest(int version, String sha256, int size, String url) {
    }

    private final byte[] body;
    private final Manifest manifest;

    public KnowledgePackService(ObjectMapper mapper) throws IOException {
        try (InputStream in = new ClassPathResource("knowledge/base_knowledge.json").getInputStream()) {
            body = in.readAllBytes();
        }
        JsonNode root = mapper.readTree(body);
        if (root.path("schemaVersion").asInt() != 1 || root.path("version").asInt() < 1) {
            throw new IllegalStateException("bundled knowledge pack is not schema 1 with a version");
        }
        int version = root.path("version").asInt();
        manifest = new Manifest(version, sha256(body), body.length, "/api/v1/knowledge/pack/" + version);
    }

    public Manifest manifest() {
        return manifest;
    }

    /** The pack bytes for {@code version}, or null when that version is not served. */
    public byte[] pack(int version) {
        return version == manifest.version() ? body.clone() : null;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
