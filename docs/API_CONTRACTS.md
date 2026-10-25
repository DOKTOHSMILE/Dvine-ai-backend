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
