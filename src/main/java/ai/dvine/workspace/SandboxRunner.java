package ai.dvine.workspace;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Runs one piece of code in an isolated Linux sandbox with the user's
 * workspace mounted at /work. Implementations must enforce the time limit,
 * cap the output, and give the code no network unless {@code network} is set
 * (and then only through the egress proxy).
 */
public interface SandboxRunner {
    /** What to run. {@code language} is bash, python or node; the code arrives on stdin. */
    record RunSpec(String runId, Path workspace, String language, String code, boolean network, Duration timeout) {
    }

    record RunResult(int exitCode, String stdout, String stderr, boolean timedOut, long durationMs, boolean truncated) {
    }

    /** False when the sandbox can't run here (no container engine); runs are then refused with 503. */
    boolean available();

    RunResult run(RunSpec spec) throws java.io.IOException, InterruptedException;
}
