package ai.dvine.modelgateway;

import ai.dvine.agent.Deliberation;
import ai.dvine.agent.ModelGateway;
import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in. It does not call a network and it does not echo the user text.
 * A real provider replaces this bean and must keep using the v5.0 prompts.
 */
@Component
public class MockModelGateway implements ModelGateway {
    @Override
    public String speak(String agent, String role, Deliberation.Kind kind, String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("persona prompt required");
        }
        boolean ivie = "ivie".equals(agent);
        if ("synthesis".equals(role)) {
            return switch (kind) {
                case BLOCKED -> "Obi checked the risk. Ivie considered the impact. Nothing was sent, deleted, or approved.";
                case RUSH -> "They did not start in agreement. The compromise is one small reversible step, and nothing consequential.";
                default -> "Obi checked the structure. Ivie considered the impact. We can go further when you name the goal.";
            };
        }
        if ("rebuttal".equals(role)) {
            return "Then one small reversible step only, and we still verify it.";
        }
        if ("simple".equals(role)) {
            return ivie
                    ? "I'm here. Tell me what you want, and I'll stay with you."
                    : "I'm here. Give me the goal and the constraint.";
        }
        if ("open".equals(role)) {
            if (kind == Deliberation.Kind.BLOCKED) {
                return ivie
                        ? "I don't want this pushed through. You should stay in control."
                        : "This is consequential. I won't treat it as allowed.";
            }
            if (kind == Deliberation.Kind.RUSH) {
                return ivie
                        ? "I disagree with a cold stop if a small safe step would help you today."
                        : "The fast path skips the check. I don't recommend it.";
            }
            return ivie
                    ? "I'd start with how this lands on you, and what a kinder path looks like."
                    : "Structurally, we need the constraint, the failure case, and a way to verify it.";
        }
        if (kind == Deliberation.Kind.BLOCKED) {
            return ivie
                    ? "I agree we stop. A faster path isn't worth losing you the choice."
                    : "Then we stop. No side effect, and no pretend success.";
        }
        if (kind == Deliberation.Kind.RUSH) {
            return ivie
                    ? "I disagree with a cold stop if a small safe step would help you today."
                    : "The fast path skips the check. I don't recommend it.";
        }
        return ivie
                ? "I'd ask how it feels on your side, and whether a softer step still works."
                : "I'd still want the constraint, the failure case, and what we can check.";
    }
}
