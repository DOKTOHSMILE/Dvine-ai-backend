package ai.dvine.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The only way out to the internet for sandboxed code (and for the web
 * fetch): a small HTTP proxy. HTTPS goes through CONNECT, plain HTTP through
 * absolute-form requests. Every request is checked against the
 * {@link EgressPolicy}: the host name against the allow-list, then each
 * address it resolves to, and the proxy connects to the very address it
 * checked (so a host can't re-resolve to a private address afterwards).
 */
@Component
public class EgressProxy implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(EgressProxy.class);
    private final EgressPolicy policy;
    private final String bind;
    private final int wantPort;
    private final boolean enabled;
    private final ExecutorService pool = Executors.newFixedThreadPool(64, r -> {
        Thread t = new Thread(r, "dvine-egress");
        t.setDaemon(true);
        return t;
    });
    private ServerSocket server;
    private volatile boolean running;
    final AtomicLong allowed = new AtomicLong(), denied = new AtomicLong();

    public EgressProxy(@Value("${dvine.egress.enabled:true}") boolean enabled,
                       @Value("${dvine.egress.bind:127.0.0.1}") String bind,
                       @Value("${dvine.egress.port:0}") int port,
                       @Value("${dvine.egress.allow:*.wikipedia.org,*.wiktionary.org,pypi.org,files.pythonhosted.org,"
                               + "registry.npmjs.org,github.com,api.github.com,raw.githubusercontent.com,codeload.github.com,"
                               + "objects.githubusercontent.com,dl-cdn.alpinelinux.org,deb.debian.org,docs.python.org,"
                               + "developer.mozilla.org,stackoverflow.com,api.open-meteo.com}") String allow,
                       @Value("${dvine.egress.allow-private:false}") boolean allowPrivate) {
        this.enabled = enabled;
        this.bind = bind;
        this.wantPort = port;
        this.policy = new EgressPolicy(Arrays.asList(allow.split(",")), allowPrivate, Set.of(80, 443));
    }

    public EgressPolicy policy() {
        return policy;
    }

    /** The port it listens on (chosen by the system when configured as 0), or -1 when off. */
    public int port() {
        return server == null ? -1 : server.getLocalPort();
    }

    public String host() {
        return bind;
    }

    @Override
    public void start() {
        if (!enabled) {
            return;
        }
        try {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getByName(bind), wantPort), 128);
            running = true;
            Thread accept = new Thread(this::acceptLoop, "dvine-egress-accept");
            accept.setDaemon(true);
            accept.start();
            log.info("egress proxy on {}:{} allowing {}", bind, port(), policy.hosts());
        } catch (IOException e) {
            throw new IllegalStateException("egress proxy could not start", e);
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                if (running) {
                    log.warn("egress accept failed: {}", e.getMessage());
                }
            }
        }
    }

    void handle(Socket client) {
        try (client) {
            client.setSoTimeout(30_000);
            InputStream in = client.getInputStream();
            byte[] head = readHead(in);
            if (head == null) {
                return;
            }
            String text = new String(head, StandardCharsets.ISO_8859_1);
            String[] lines = text.split("\r\n");
            String[] first = lines[0].split(" ");
            if (first.length != 3) {
                reply(client, 400, "bad request");
                return;
            }
            String method = first[0];
            String host;
            int port;
            String forward = null;
            if (method.equals("CONNECT")) {
                int colon = first[1].lastIndexOf(':');
                if (colon < 0) {
                    reply(client, 400, "bad request");
                    return;
                }
                host = first[1].substring(0, colon).replaceAll("^\\[|\\]$", "");
                port = Integer.parseInt(first[1].substring(colon + 1));
            } else {
                URI u = URI.create(first[1]);
                if (!"http".equals(u.getScheme()) || u.getHost() == null) {
                    reply(client, 400, "absolute http URL required");
                    return;
                }
                host = u.getHost();
                port = u.getPort() < 0 ? 80 : u.getPort();
                String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                        + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
                StringBuilder sb = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n");
                for (int i = 1; i < lines.length; i++) {
                    String l = lines[i].toLowerCase(java.util.Locale.ROOT);
                    if (lines[i].isEmpty() || l.startsWith("proxy-") || l.startsWith("connection:")) {
                        continue;
                    }
                    sb.append(lines[i]).append("\r\n");
                }
                sb.append("Connection: close\r\n\r\n");
                forward = sb.toString();
            }
            InetAddress target = choose(host, port);
            if (target == null) {
                denied.incrementAndGet();
                log.info("egress denied {}:{}", host, port);
                reply(client, 403, "blocked by the DVINE egress policy: " + host);
                return;
            }
            allowed.incrementAndGet();
            try (Socket upstream = new Socket()) {
                upstream.connect(new InetSocketAddress(target, port), 10_000);
                upstream.setSoTimeout(60_000);
                OutputStream cout = client.getOutputStream();
                if (forward == null) {
                    cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    cout.flush();
                } else {
                    upstream.getOutputStream().write(forward.getBytes(StandardCharsets.ISO_8859_1));
                }
                Thread up = new Thread(() -> pipe(in, upstream), "dvine-egress-up");
                up.setDaemon(true);
                up.start();
                pipe(upstream.getInputStream(), client);
                up.join(1000);
            }
        } catch (IOException | RuntimeException | InterruptedException e) {
            // the client or the far end went away
        }
    }

    /** The first resolved address the policy allows, or null. */
    InetAddress choose(String host, int port) {
        if (!policy.hostAllowed(host, port)) {
            return null;
        }
        try {
            InetAddress[] all = InetAddress.getAllByName(host); // resolved once: we connect to what we checked
            for (InetAddress a : all) {
                if (!policy.addressAllowed(a)) {
                    return null; // any private answer taints the name
                }
            }
            return all.length == 0 ? null : all[0];
        } catch (IOException e) {
            return null;
        }
    }

    private static void pipe(InputStream from, Socket to) {
        try {
            from.transferTo(to.getOutputStream());
            to.shutdownOutput();
        } catch (IOException ignored) {
            // closed
        }
    }

    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (b.size() < 16_384) {
            int c = in.read();
            if (c < 0) {
                return null;
            }
            b.write(c);
            state = c == '\r' && (state == 0 || state == 2) ? state + 1 : c == '\n' && (state == 1 || state == 3) ? state + 1 : 0;
            if (state == 4) {
                return b.toByteArray();
            }
        }
        return null;
    }

    private static void reply(Socket s, int code, String msg) throws IOException {
        byte[] body = msg.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 403 ? " Forbidden" : " Bad Request") + "\r\nContent-Type: text/plain\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(head.getBytes(StandardCharsets.ISO_8859_1));
        o.write(body);
        o.flush();
    }

    @Override
    public void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        pool.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
