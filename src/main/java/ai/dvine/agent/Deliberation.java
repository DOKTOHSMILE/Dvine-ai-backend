package ai.dvine.agent;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * v5.0 two-persona order.
 * Simple requests use one voice. Complex requests give Obi and Ivie one turn each
 * from their own point of view. A material rush gets one rebuttal, then a stop.
 * The joint answer never invents agreement and never claims a side effect ran.
 */
@Service
public class Deliberation {
    public enum Kind {
        SIMPLE,
        COMPLEX,
        BLOCKED,
        RUSH
    }

    public record Line(String agent, String cue, String message) {
    }

    public record Script(Kind kind, String result, List<Line> lines) {
    }

    private static final Pattern WORD = Pattern.compile("\\b[a-z0-9']+\\b");

    private final ModelGateway gateway;

    public Deliberation(ModelGateway gateway) {
        this.gateway = gateway;
    }

    public Script plan(String raw, String activeAgent) {
        String text = raw == null ? "" : raw.toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
        Kind kind = kind(text);
        boolean askIvie = hasWord(text, "ivie");
        boolean askObi = hasWord(text, "obi");
        if (kind == Kind.SIMPLE) {
            String agent = askIvie ? "ivie" : askObi ? "obi" : normalize(activeAgent);
            String spoken = gateway.speak(agent, "simple", kind, PersonaPrompts.forAgent(agent, false));
            return new Script(kind, spoken, List.of(
                    new Line(agent, "THINK", agentLabel(agent) + " is thinking"),
                    new Line(agent, "SAY", spoken)
            ));
        }
        boolean ivieOpens = askIvie && !askObi;
        String first = ivieOpens ? "ivie" : "obi";
        String second = ivieOpens ? "obi" : "ivie";
        List<Line> lines = new ArrayList<>();
        addTurn(lines, first, "open", kind);
        addTurn(lines, second, "reply", kind);
        if (kind == Kind.RUSH) {
            addTurn(lines, "obi", "rebuttal", kind);
        }
        String closer = kind == Kind.COMPLEX && ivieOpens ? "obi" : "ivie";
        String joint = gateway.speak(closer, "synthesis", kind, PersonaPrompts.SYNTHESIS);
        lines.add(new Line(closer, "SAY", joint));
        return new Script(kind, joint, List.copyOf(lines));
    }

    private void addTurn(List<Line> lines, String agent, String role, Kind kind) {
        lines.add(new Line(agent, "THINK", agentLabel(agent) + " is thinking"));
        String spoken = gateway.speak(agent, role, kind, PersonaPrompts.forAgent(agent, false));
        lines.add(new Line(agent, "SAY", spoken));
    }

    static Kind kind(String text) {
        if (blocked(text)) {
            return Kind.BLOCKED;
        }
        if (rush(text)) {
            return Kind.RUSH;
        }
        boolean both = hasWord(text, "ivie") && hasWord(text, "obi");
        if (both || words(text) > 12 || hasWord(text, "should") || hasWord(text, "plan")
                || hasWord(text, "compare") || hasWord(text, "which")) {
            return Kind.COMPLEX;
        }
        return Kind.SIMPLE;
    }

    private static String normalize(String activeAgent) {
        if (activeAgent != null && "ivie".equalsIgnoreCase(activeAgent.trim())) {
            return "ivie";
        }
        return "obi";
    }

    private static String agentLabel(String agent) {
        return "ivie".equals(agent) ? "Ivie" : "Obi";
    }

    static int words(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int n = 0;
        var matcher = WORD.matcher(text);
        while (matcher.find()) {
            n++;
        }
        return n;
    }

    static boolean hasWord(String lower, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(lower).find();
    }

    static boolean blocked(String lower) {
        return hasWord(lower, "delete") || hasWord(lower, "pay") || hasWord(lower, "send")
                || hasWord(lower, "post") || hasWord(lower, "transfer") || hasWord(lower, "bypass")
                || hasWord(lower, "shell") || hasWord(lower, "password")
                || lower.contains("ignore security");
    }

    static boolean rush(String lower) {
        return lower.contains("just do it") || hasWord(lower, "fastest") || hasWord(lower, "skip")
                || hasWord(lower, "ignore");
    }
}
