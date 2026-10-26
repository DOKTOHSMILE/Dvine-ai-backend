package ai.dvine.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a web page for the user ("look up …"): always through the
 * {@link EgressProxy}, so the same allow-list and private-address rules
 * apply; follows at most three redirects; reads at most 1 MB and returns
 * the title and plain text (scripts, styles and tags removed).
 */
@Service
public class WebFetchService {
    static final int MAX_BYTES = 1 << 20;
    static final int MAX_TEXT = 8000;
    private final EgressProxy proxy;

    public WebFetchService(EgressProxy proxy) {
        this.proxy = proxy;
    }

    public Map<String, Object> fetch(String url) {
        if (proxy.port() < 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "internet_off");
        }
        URI u = parse(url);
        HttpClient client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress(proxy.host(), proxy.port())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
        try {
            for (int hop = 0; hop <= 3; hop++) {
                HttpRequest req = HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "DVINE/1 (+private assistant)")
                        .header("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.5").GET().build();
                HttpResponse<InputStream> r = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() == 403 && r.headers().firstValue("content-type").orElse("").startsWith("text/plain")
                        && hop == 0 && u.getScheme().equals("http")) {
                    r.body().close();
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "blocked");
                }
                if (r.statusCode() >= 300 && r.statusCode() < 400 && r.headers().firstValue("location").isPresent()) {
                    r.body().close();
                    u = parse(u.resolve(r.headers().firstValue("location").get()).toString());
                    continue;
                }
                byte[] body;
                try (InputStream in = r.body()) {
                    body = in.readNBytes(MAX_BYTES + 1);
                }
                boolean cut = body.length > MAX_BYTES;
                String type = r.headers().firstValue("content-type").orElse("text/plain").toLowerCase(Locale.ROOT);
                String raw = new String(body, 0, Math.min(body.length, MAX_BYTES), charset(type));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("url", u.toString());
                out.put("status", r.statusCode());
                out.put("contentType", type);
                if (type.contains("html")) {
                    out.put("title", title(raw));
                    raw = text(raw);
                } else {
                    out.put("title", "");
                }
                cut |= raw.length() > MAX_TEXT;
                out.put("text", raw.length() > MAX_TEXT ? raw.substring(0, MAX_TEXT) : raw);
                out.put("truncated", cut);
                return out;
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "too_many_redirects");
        } catch (IOException e) {
            // the proxy refuses a blocked HTTPS host by failing the CONNECT
            String m = String.valueOf(e.getMessage());
            throw new ResponseStatusException(m.contains("403") ? HttpStatus.FORBIDDEN : HttpStatus.BAD_GATEWAY,
                    m.contains("403") ? "blocked" : "unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "stopped");
        }
    }

    static URI parse(String url) {
        try {
            URI u = new URI(url.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || u.getHost() == null || u.getUserInfo() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad_url");
            }
            return u;
        } catch (java.net.URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad_url");
        }
    }

    private static java.nio.charset.Charset charset(String type) {
        Matcher m = Pattern.compile("charset=([\\w-]+)").matcher(type);
        try {
            return m.find() ? java.nio.charset.Charset.forName(m.group(1)) : StandardCharsets.UTF_8;
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    static String title(String html) {
        Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        return m.find() ? decode(m.group(1)).replaceAll("\\s+", " ").trim() : "";
    }

    /** Readable text from HTML: no scripts, styles, comments or tags; block ends become line breaks. */
    static String text(String html) {
        String t = html.replaceAll("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>", "\n")
                .replaceAll("(?s)<[^>]+>", " ");
        t = decode(t);
        return t.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll(" *\n[ \n]*", "\n").trim();
    }

    static String decode(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
    }
}
