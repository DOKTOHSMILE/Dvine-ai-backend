# API

Bearer token from `POST /api/v1/sessions`. The server decides the user. A body `userId` is not accepted.

`POST /api/v1/desk-rooms/{id}/commands`

```
{
  "requestId": "client-token-8-to-80",
  "text": "Should we plan this?",
  "activeAgent": "obi"
}
```

`activeAgent` is optional (`obi` or `ivie`). It is only the face already on screen. Naming someone in the text overrides it.

`GET /api/v1/tasks/{id}`

`GET /api/v1/tasks/{id}/events?after={eventId}`

Event:

```
{
  "eventId": 1,
  "taskId": "...",
  "state": "EXECUTING",
  "agent": "ivie",
  "cue": "SAY",
  "message": "short line",
  "createdAt": "2026-01-01T00:00:00Z"
}
```

`cue` is `STATUS`, `THINK`, or `SAY`. The phone switches to `agent` on `THINK` and `SAY` and speaks only `SAY`. `STATUS` is the task machine, not a second face.

WebSocket `/ws/tasks`: first text frame `{"op":"auth","token":"..."}`, then `{"op":"subscribe","taskId":"...","after":0}`. Unauthenticated sockets are closed. The token is not logged.

Task states, in order: RECEIVED, AUTHORIZED, UNDERSTOOD, PLAN_CREATED, POLICY_CHECK, QUEUED, EXECUTING, VERIFYING, COMPLETED.

## Offline knowledge and learning (Track M ↔ Track S)

Implemented (`ai.dvine.knowledge`, `ai.dvine.learning`, migration V2). The Android client calls these when `api_base` is set. The served pack is `src/main/resources/knowledge/base_knowledge.json`; copy it from the Android repo (`tools/pack_source.py` output) when the pack version changes.

`GET /api/v1/knowledge/manifest` (no user data, may be public):

```
{ "version": 2, "sha256": "<64 hex of the pack body>", "size": 25898, "url": "/api/v1/knowledge/pack/2" }
```

`GET /api/v1/knowledge/pack/{version}` returns the pack JSON (schema in handbook §7). The phone installs it only if `version` is newer and the sha256 matches.

`POST /api/v1/learning/sync` (authenticated; the user comes from the session, never the body):

```
{ "schemaVersion": 1, "deviceId": "uuid",
  "entries": [ { "id": "uuid", "kind": "TAUGHT_FACT|CORRECTION|UNANSWERED|FEEDBACK",
                 "text": "my city", "answer": "Sapele", "createdAt": 1793007000000 } ] }
```

Response `{ "accepted": ["id", ...], "rejected": [ { "id": "...", "reason": "..." } ] }`. Repeated ids are accepted again without duplicating rows. Entries land owner-scoped in a review queue (`PENDING`); nothing is promoted into a shared pack without review and de-identification. Personal facts stay in that user's private memory.

Offline wording the server may reuse when its own store is unreachable: "I'm unable to connect to my database to get more information. Please connect to the internet and ask me again."
