package ai.dvine;

import ai.dvine.web.EgressPolicy;
import ai.dvine.workspace.DockerRunner;
import ai.dvine.workspace.SandboxRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** The server Linux workspace: runs, isolation, limits, files, and the docker command line. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "dvine.bootstrap-enabled=true",
        "dvine.bootstrap-token=test-bootstrap-token",
        "dvine.workspace.runs-per-hour=4",
        "dvine.workspace.quota-bytes=2000"
})
class WorkspaceTest {
    /** Stands in for the container engine: echoes what it would run and writes a file into /work. */
    static final class FakeRunner implements SandboxRunner {
        volatile boolean up = true;

        public boolean available() {
            return up;
        }

        public RunResult run(RunSpec s) throws java.io.IOException {
            Files.writeString(s.workspace().resolve("last-run.txt"), s.language());
            if (s.code().contains("sleep forever")) {
                return new RunResult(137, "", "", true, s.timeout().toMillis(), false);
            }
            return new RunResult(0, "ran " + s.language() + " net=" + s.network() + ": " + s.code().length(), "", false, 12, false);
        }
    }

    @TestConfiguration
    static class Fake {
        @Bean
        @Primary
        FakeRunner fakeRunner() {
            return new FakeRunner();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    FakeRunner runner;

    @Test
    void runsAreOwnerScopedIdempotentAndLimited() throws Exception {
        String a = session("ws-phone-a");
        JsonNode run = run(a, "req-ws-a-1", "python", "print(2+2)", 202);
        String id = run.get("runId").asText();
        JsonNode done = await(a, id);
        assertEquals("DONE", done.get("state").asText());
        assertEquals(0, done.get("exitCode").asInt());
        assertTrue(done.get("stdout").asText().startsWith("ran python"));
        // a replay returns the same run
        assertEquals(id, run(a, "req-ws-a-1", "python", "print(2+2)", 202).get("runId").asText());
        // another user can't read it
        String b = session("ws-phone-b");
        assertEquals(404, mvc.perform(get("/api/v1/workspace/runs/" + id).header("Authorization", "Bearer " + b))
                .andReturn().getResponse().getStatus());
        // a time-out is reported as such
        JsonNode slow = await(a, run(a, "req-ws-a-2", "bash", "sleep forever", 202).get("runId").asText());
        assertTrue(slow.get("timedOut").asBoolean());
        assertTrue(slow.get("stderr").asText().contains("time limit"));
        // the hourly allowance
        run(a, "req-ws-a-3", "node", "console.log(1)", 202);
        run(a, "req-ws-a-4", "node", "console.log(1)", 202);
        run(a, "req-ws-a-5", "node", "console.log(1)", 429);
        // unknown languages are refused
        run(b, "req-ws-b-1", "ruby", "puts 1", 400);
    }

    @Test
    void noSandboxMeansAClear503() throws Exception {
        String c = session("ws-phone-c");
        runner.up = false;
        try {
            run(c, "req-ws-c-1", "bash", "echo hi", 503);
        } finally {
            runner.up = true;
        }
    }

    @Test
    void filesStayInsideTheWorkspaceAndUnderQuota() throws Exception {
        String d = session("ws-phone-d");
        assertEquals(204, write(d, "src/hello.py", "print('hello')"));
        assertEquals("print('hello')", mvc.perform(get("/api/v1/workspace/files/content").param("path", "src/hello.py")
                .header("Authorization", "Bearer " + d)).andReturn().getResponse().getContentAsString());
        MvcResult list = mvc.perform(get("/api/v1/workspace/files").header("Authorization", "Bearer " + d)).andReturn();
        assertTrue(list.getResponse().getContentAsString().contains("src/hello.py"));
        for (String bad : new String[]{"../escape.txt", "/etc/passwd", "a/../../b", "..", ""}) {
            assertEquals(400, write(d, bad, "x"), bad);
        }
        assertEquals(413, write(d, "big.txt", "x".repeat(3000)));
        // another user does not see the file
        String e = session("ws-phone-e");
        assertEquals(404, mvc.perform(get("/api/v1/workspace/files/content").param("path", "src/hello.py")
                .header("Authorization", "Bearer " + e)).andReturn().getResponse().getStatus());
    }

    @Test
    void dockerRunsAreLockedDown() {
        DockerRunner d = new DockerRunner("docker", "dvine-sandbox:1", "dvine-egress", "http://proxy:3128", "1000:1000",
                "512m", "1.0", 128, 65536);
        List<String> off = d.argv(new SandboxRunner.RunSpec("abc-123", Path.of("/srv/ws/u1"), "python", "print(1)", false,
                Duration.ofSeconds(30)));
        String line = String.join(" ", off);
        for (String must : new String[]{"--rm", "--read-only", "--cap-drop ALL", "--security-opt no-new-privileges",
                "--network none", "--memory 512m", "--memory-swap 512m", "--pids-limit 128", "--user 1000:1000",
                "/srv/ws/u1:/work:rw", "python3 -"}) {
            assertTrue(line.contains(must), must + " in " + line);
        }
        assertFalse(line.contains("HTTPS_PROXY"));
        String on = String.join(" ", d.argv(new SandboxRunner.RunSpec("abc-124", Path.of("/srv/ws/u1"), "bash", "ls", true,
                Duration.ofSeconds(30))));
        assertTrue(on.contains("--network dvine-egress") && on.contains("HTTPS_PROXY=http://proxy:3128"));
        // without an egress network configured, network requests run offline
        DockerRunner closed = new DockerRunner("docker", "img", "", "", "1000:1000", "512m", "1.0", 128, 65536);
        assertTrue(String.join(" ", closed.argv(new SandboxRunner.RunSpec("x-1", Path.of("/w"), "bash", "ls", true,
                Duration.ofSeconds(5)))).contains("--network none"));
    }

    @Test
    void egressPolicyRefusesPrivateAddressesAndUnlistedHosts() throws Exception {
        EgressPolicy p = new EgressPolicy(List.of("pypi.org", "*.wikipedia.org"), false, Set.of(80, 443));
        assertTrue(p.hostAllowed("pypi.org", 443));
        assertTrue(p.hostAllowed("en.wikipedia.org", 443));
        assertTrue(p.hostAllowed("wikipedia.org", 443));
        assertFalse(p.hostAllowed("evilwikipedia.org", 443));
        assertFalse(p.hostAllowed("pypi.org", 22));
        assertFalse(p.hostAllowed("example.com", 443));
        for (String ip : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1", "169.254.169.254", "100.64.0.1",
                "0.0.0.0", "::1", "fc00::1", "fe80::1", "::ffff:10.0.0.1", "224.0.0.1"}) {
            assertFalse(p.addressAllowed(InetAddress.getByName(ip)), ip);
        }
        assertTrue(p.addressAllowed(InetAddress.getByName("151.101.0.223")));
        assertTrue(new EgressPolicy(List.of("*"), false, Set.of(443)).hostAllowed("anything.example", 443));
    }

    private JsonNode await(String token, String id) throws Exception {
        for (int i = 0; i < 100; i++) {
            MvcResult r = mvc.perform(get("/api/v1/workspace/runs/" + id).header("Authorization", "Bearer " + token)).andReturn();
            JsonNode v = mapper.readTree(r.getResponse().getContentAsString());
            String s = v.get("state").asText();
            if (s.equals("DONE") || s.equals("FAILED")) {
                return v;
            }
            Thread.sleep(30);
        }
        throw new AssertionError("run never finished");
    }

    private JsonNode run(String token, String requestId, String language, String code, int status) throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of("requestId", requestId, "language", language, "code", code,
                "network", true));
        MvcResult r = mvc.perform(post("/api/v1/workspace/runs").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertEquals(status, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        String c = r.getResponse().getContentAsString();
        return c.isEmpty() ? mapper.createObjectNode() : mapper.readTree(c);
    }

    private int write(String token, String path, String text) throws Exception {
        return mvc.perform(put("/api/v1/workspace/files/content").param("path", path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.TEXT_PLAIN).content(text)).andReturn().getResponse().getStatus();
    }

    private String session(String device) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + device + "\",\"bootstrap\":\"test-bootstrap-token\"}")).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }
}
