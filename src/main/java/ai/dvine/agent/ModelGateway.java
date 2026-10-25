package ai.dvine.agent;

public interface ModelGateway {
    String speak(String agent, String role, Deliberation.Kind kind, String prompt);
}
