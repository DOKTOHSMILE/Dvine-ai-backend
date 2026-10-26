package ai.dvine.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One private directory per user (named by a hash of the user id), with a
 * size quota. Paths from clients are relative, never leave the directory
 * (no "..", no absolute paths, no following symlinks the code may have made).
 */
@Component
public class Workspaces {
    static final int MAX_READ = 256 * 1024;
    private final Path root;
    private final long quota;

    public Workspaces(@Value("${dvine.workspace.root:${java.io.tmpdir}/dvine-workspaces}") String root,
                      @Value("${dvine.workspace.quota-bytes:104857600}") long quota) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.quota = quota;
    }

    public Path dir(String userId) throws IOException {
        Path d = root.resolve(hash(userId));
        Files.createDirectories(d);
        return d;
    }

    /** A client path inside the user's workspace, or 400. */
    Path resolve(String userId, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.length() > 300 || relative.indexOf('\0') >= 0
                || relative.startsWith("/") || relative.startsWith("\\")) {
            throw bad();
        }
        Path base = dir(userId);
        Path p = base.resolve(relative).normalize();
        if (!p.startsWith(base) || p.equals(base)) {
            throw bad();
        }
        for (Path part : base.relativize(p)) {
            if (part.toString().equals("..")) {
                throw bad();
            }
        }
        // no step on the way may be a symlink (code in the sandbox can create them)
        Path walk = base;
        for (Path part : base.relativize(p)) {
            walk = walk.resolve(part);
            if (Files.isSymbolicLink(walk)) {
                throw bad();
            }
        }
        return p;
    }

    public List<Map<String, Object>> list(String userId) throws IOException {
        Path base = dir(userId);
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(base, 6)) {
            s.filter(p -> !p.equals(base) && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).limit(500).forEach(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", base.relativize(p).toString().replace('\\', '/'));
                try {
                    m.put("size", Files.size(p));
                } catch (IOException e) {
                    m.put("size", -1);
                }
                out.add(m);
            });
        }
        return out;
    }

    public String read(String userId, String relative) throws IOException {
        Path p = resolve(userId, relative);
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not_found");
        }
        if (Files.size(p) > MAX_READ) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large");
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    public void write(String userId, String relative, String text) throws IOException {
        Path p = resolve(userId, relative);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        long old = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) ? Files.size(p) : 0L;
        if (usage(userId) - old + bytes.length > quota) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "quota");
        }
        Files.createDirectories(p.getParent());
        Files.write(p, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    public boolean delete(String userId, String relative) throws IOException {
        Path p = resolve(userId, relative);
        return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && Files.deleteIfExists(p);
    }

    /** Bytes used by the user's files. */
    public long usage(String userId) throws IOException {
        long[] sum = {0L};
        Files.walkFileTree(dir(userId), new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                sum[0] += attrs.size();
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return sum[0];
    }

    public boolean overQuota(String userId) throws IOException {
        return usage(userId) > quota;
    }

    private static ResponseStatusException bad() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad_path");
    }

    static String hash(String userId) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(userId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
