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


## S-K / S-L (Track S)

| Item | Status | Evidence |
|---|---|---|
| S-K knowledge pack manifest + download | VERIFIED (tests) | `KnowledgeAndLearningTest.manifestMatchesThePackItServes`: sha256 and size match the served bytes; unknown version 404; public GET |
| S-L learning intake | VERIFIED (tests) | `learningSyncIsAuthenticatedOwnerScopedAndIdempotent`: 401 without a session; secrets and unknown kinds rejected; replay adds no rows; one row per owner; all PENDING; audit event written |

`mvn test`: 13 tests pass.


## Interpret and research

| Item | Status | Evidence |
|---|---|---|
| Structured reading, questions are not deletes | IN TESTS (plain JVM, not Maven) | `SemanticInterpretationService` keeps negation; a question about deleting is an explanation, not a delete |
| Research notes do not become commands | IN TESTS (plain JVM, not Maven) | `ResearchService` strips `rm -rf`, `sudo`, and `curl|sh`; `executed` stays false; `OfflinePageSource` does not fetch the public web |

`POST /api/v1/interpret` and `POST /api/v1/research` require a session, like the other private routes. No provider key was added.

Not run this build: `mvn test` (Maven is not installed on the machine that prepared the commit).
