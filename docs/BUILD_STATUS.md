# Build status

| Phase | Status | Scope | Next |
|---|---|---|---|
| 0 Discovery | IN PROGRESS | v5.0 plan plus v5.1 face rules. Repos started empty. | Keep this file honest. |
| 1 Secure foundation | IN PROGRESS | Auth required, bootstrap off by default, owner-scoped rooms and tasks, audit on cross-reads. | Real user login. Rate limit. |
| 2 Model gateway | IN PROGRESS | Interface plus mock. Prompts required. No provider key. | One real adapter after the provider is named. |
| 3 Orchestrator | IN PROGRESS | Task lifecycle, idempotency, bounded Obi/Ivie debate, no tools. | Stream events while a live model is still talking. |
| 4 Android face | IN PROGRESS | Separate repo. One face, switch on a line or a call. | Run on a phone. |

Evidence, 2026-10-25: `mvn test` — TaskIsolationTest 9, BootstrapClosedTest 1, 0 failures. H2, not a device, not a live model.

Not in this commit: memory, pgvector, social, shell, wallet, background jobs.
