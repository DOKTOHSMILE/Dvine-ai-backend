package ai.dvine.semantics;

import java.util.Locale;

/** Structured reading for the desk. A question is not turned into a command. */
public class SemanticInterpretationService {
    public record Reading(String status, String intent, String operation, boolean clarification, String note) {
    }

    public Reading interpret(String text) {
        String n = text == null ? "" : text.toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
        if (n.isEmpty()) {
            return new Reading("insufficient", "clarify", "none", true, "Nothing was said.");
        }
        if (n.contains("what would happen") || n.startsWith("explain ")) {
            return new Reading("understood", "explain", "explain", false, "This asks for an explanation. Nothing will be changed.");
        }
        if (n.contains("do not") || n.contains("don't") || n.contains("never ")) {
            if (n.contains("delete") || n.contains("remove")) {
                return new Reading("understood", "constrain", "preserve", false, "The restriction is kept. The forbidden change will not run.");
            }
        }
        if (n.matches("^(delete|remove|wipe) .+")) {
            return new Reading("understood", "command", "delete", true, "Deletion needs a separate confirmation. It has not run.");
        }
        return new Reading("understood", "chat", "answer", false, "No side effect.");
    }
}
