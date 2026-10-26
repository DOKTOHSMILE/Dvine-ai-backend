package ai.dvine.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs code in a throw-away Docker container:
 * <ul>
 *   <li>no capabilities, no privilege escalation, read-only root, a small
 *   /tmp, limited memory (no swap), CPU and processes, an unprivileged user;</li>
 *   <li>only the user's workspace is writable, at /work;</li>
 *   <li>no network at all, unless the run asks for it: then it joins the
 *   internal egress network, where the only way out is the DVINE egress
 *   proxy and its allow-list;</li>
 *   <li>killed when the time limit passes; output capped.</li>
 * </ul>
 * The image (sandbox/Dockerfile) has bash, python3, node and git.
 */
@Component
public class DockerRunner implements SandboxRunner {
    private final String docker;
    private final String image;
    private final String network;
    private final String proxy;
    private final String user;
    private final String memory;
    private final String cpus;
    private final int pids;
    private final int maxOutput;
    private volatile long checkedAt;
    private volatile boolean available;

    public DockerRunner(@Value("${dvine.workspace.docker:docker}") String docker,
                        @Value("${dvine.workspace.image:dvine-sandbox:1}") String image,
                        @Value("${dvine.workspace.egress-network:}") String network,
                        @Value("${dvine.workspace.egress-proxy:}") String proxy,
                        @Value("${dvine.workspace.user:1000:1000}") String user,
                        @Value("${dvine.workspace.memory:512m}") String memory,
                        @Value("${dvine.workspace.cpus:1.0}") String cpus,
                        @Value("${dvine.workspace.pids:128}") int pids,
                        @Value("${dvine.workspace.max-output:65536}") int maxOutput) {
        this.docker = docker;
        this.image = image;
        this.network = network;
        this.proxy = proxy;
        this.user = user;
        this.memory = memory;
        this.cpus = cpus;
        this.pids = pids;
        this.maxOutput = maxOutput;
    }

    /** Whether runs may use the network at all (an egress network and proxy are configured). */
    public boolean networkConfigured() {
        return !network.isBlank() && !proxy.isBlank();
    }

    @Override
    public boolean available() {
        long now = System.currentTimeMillis();
        if (now - checkedAt > 60_000L) {
            checkedAt = now;
            try {
                Process p = new ProcessBuilder(docker, "version", "--format", "{{.Server.Version}}")
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                available = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (IOException | InterruptedException e) {
                available = false;
            }
        }
        return available;
    }

    /** The full docker command line for a run (kept separate so it can be checked without a daemon). */
    public List<String> argv(RunSpec s) {
        List<String> a = new ArrayList<>(List.of(docker, "run", "--rm", "-i", "--name", containerName(s.runId()),
                "--read-only", "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m",
                "--memory", memory, "--memory-swap", memory, "--cpus", cpus, "--pids-limit", String.valueOf(pids),
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--user", user, "-e", "HOME=/work", "-v", s.workspace().toAbsolutePath() + ":/work:rw", "-w", "/work"));
        if (s.network() && networkConfigured()) {
            a.addAll(List.of("--network", network, "-e", "HTTP_PROXY=" + proxy, "-e", "HTTPS_PROXY=" + proxy,
                    "-e", "http_proxy=" + proxy, "-e", "https_proxy=" + proxy));
        } else {
            a.addAll(List.of("--network", "none"));
        }
        a.add(image);
        a.addAll(interpreter(s.language()));
        return a;
    }

    static List<String> interpreter(String language) {
        return switch (language) {
            case "bash" -> List.of("bash", "-s");
            case "python" -> List.of("python3", "-");
            case "node" -> List.of("node", "-");
            default -> throw new IllegalArgumentException("language");
        };
    }

    static String containerName(String runId) {
        return "dvine-run-" + runId.replaceAll("[^a-zA-Z0-9-]", "");
    }

    @Override
    public RunResult run(RunSpec s) throws IOException, InterruptedException {
        long t0 = System.currentTimeMillis();
        Process p = new ProcessBuilder(argv(s)).start();
        Capture out = new Capture(p.getInputStream(), maxOutput), err = new Capture(p.getErrorStream(), maxOutput);
        out.start();
        err.start();
        try (OutputStream in = p.getOutputStream()) {
            in.write(s.code().getBytes(StandardCharsets.UTF_8));
        } catch (IOException closedEarly) {
            // the program exited before reading all of its input
        }
        boolean finished = p.waitFor(s.timeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            new ProcessBuilder(docker, "kill", containerName(s.runId())).start().waitFor(10, TimeUnit.SECONDS);
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
        }
        out.join(5000);
        err.join(5000);
        return new RunResult(finished ? p.exitValue() : 137, out.text(), err.text(), !finished,
                System.currentTimeMillis() - t0, out.truncated || err.truncated);
    }

    /** Reads a stream to the end, keeping at most {@code max} bytes. */
    static final class Capture extends Thread {
        private final InputStream in;
        private final int max;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        volatile boolean truncated;

        Capture(InputStream in, int max) {
            this.in = in;
            this.max = max;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    int room = max - kept.size();
                    if (room > 0) {
                        kept.write(buf, 0, Math.min(room, n));
                    }
                    if (n > room) {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
                // the process went away
            }
        }

        String text() {
            return kept.toString(StandardCharsets.UTF_8);
        }
    }
}
