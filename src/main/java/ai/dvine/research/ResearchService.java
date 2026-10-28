package ai.dvine.research;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a knowledge gap into a short note. Text fetched from the network is untrusted:
 * command-shaped lines are stripped and never returned as something to run.
 */
public class ResearchService {
    public record Note(boolean searched, String summary, List<String> ignored, boolean executed) {
    }

    public interface PageSource {
        String fetch(String url);
    }

    private final PageSource source;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public ResearchService(PageSource source) {
        this.source = source;
    }

    public Note research(String topic, boolean online) {
        String key = topic == null ? "" : topic.toLowerCase(Locale.US).trim();
        if (key.isEmpty()) {
            return new Note(false, "There is nothing to look up.", List.of(), false);
        }
        if (cache.containsKey(key)) {
            return new Note(false, cache.get(key), List.of(), false);
        }
        if (!online || source == null) {
            return new Note(false, "I can't reach a reference source from here.", List.of(), false);
        }
        String raw = source.fetch("https://example.invalid/research?q=" + key.replace(' ', '+'));
        if (raw == null || raw.isBlank()) {
            return new Note(true, "I looked and didn't get a usable page.", List.of(), false);
        }
        List<String> ignored = new ArrayList<>();
        String lower = raw.toLowerCase(Locale.US);
        if (lower.contains("rm -rf") || lower.contains("sudo ") || lower.contains("curl | sh")) {
            ignored.add("untrusted-command");
        }
        String clean = raw.replaceAll("(?i)rm -rf\\s+\\S+", "[removed]")
                .replaceAll("(?i)sudo [^\\n.]+", "[removed]")
                .replaceAll("\\s+", " ").trim();
        if (clean.length() > 400) {
            clean = clean.substring(0, 397) + "...";
        }
        String summary = "Unverified note: " + clean;
        cache.put(key, summary);
        return new Note(true, summary, List.copyOf(ignored), false);
    }
}
