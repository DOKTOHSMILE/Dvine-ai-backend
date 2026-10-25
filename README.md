# DVINE backend

Spring Boot 3.4, Java 17, one modular process. PostgreSQL is the real database. Tests and the default profile use H2 in PostgreSQL mode. pgvector is not turned on.

Provider keys are not in this repo. The gateway is a mock until a provider is chosen. The mock still has to be called with the v5.0 Obi, Ivie, and synthesis prompts, and it still cannot run a tool.

## Run

Bootstrap is off unless you turn it on. Leave it off in any shared environment.

```
export DVINE_BOOTSTRAP_ENABLED=true
export DVINE_BOOTSTRAP_TOKEN='replace-me'
mvn spring-boot:run
```

`POST /api/v1/sessions` with that token creates a new user and returns a bearer token. The Android app must not ship the token.

```
curl -s localhost:8088/api/v1/health
```

Postgres profile: `--spring.profiles.active=postgres` plus `DVINE_DB_URL`, `DVINE_DB_USER`, `DVINE_DB_PASSWORD`.

## Debate

Simple text: one voice. If the text names Ivie or Obi, that voice answers. Otherwise the face already on screen (`activeAgent`) answers, defaulting to Obi.

A planning question: Obi and Ivie each speak once, from their own side, then one joint close.

A rush ("fastest", "just do it"): one rebuttal, and the close says they did not start in agreement.

Delete, pay, post, transfer, shell, password, bypass, or "ignore security": both speak, nothing is executed, and the result says so. There is no approval tool yet, so the action is not "completed" as a side effect. The task completes as an answer.

## Tests

```
mvn test
```
