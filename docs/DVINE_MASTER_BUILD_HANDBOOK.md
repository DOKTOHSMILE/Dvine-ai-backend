# DVINE — Master Build Handbook

Version 1.0 · the playbook we build from. It merges the Master Specification v1.0, the Implementation-Ready Engineering Plan v5.0, the v5.1 face note, and every decision from the planning conversation. When two sources disagree, this file names the winner and why.

Names: **O-bìì** (Obi, said "O-bìì") and **Ēèvié** (Ivie, said "Eè-vi-eh"). Product: **DVINE** (D-V-I-N-E).

---

## 0. How to use this handbook

1. Work one stage at a time, in order. A stage is done only when its **exit gate** passes and the evidence is written in `docs/BUILD_STATUS.md`.
2. Every stage lists: goal, files, contracts, tests, exit gate. Do not start coding a stage until its contracts are written.
3. Status words are fixed: `NOT STARTED`, `IN PROGRESS`, `BLOCKED`, `VERIFIED`. A checkbox is never evidence.
4. Two tracks run in parallel:
   - **Track M (mobile)** — this repository, `Dvine-ai-frontend`. Built and tested offline first.
   - **Track S (server)** — `Dvine-ai-backend`, owned by the project lead.
   The tracks meet only through the contracts in §6. Either track can be tested without the other.

---

## 1. Product in one page

DVINE is a voice-first, face-first assistant with two personas who reason together and answer as one.

| | O-bìì (Obi) | Ēèvié (Ivie) |
|---|---|---|
| Role | Wise stoic strategist, structural anchor | Royal visionary, diplomat, human impact |
| Colour | Cyber cyan `#00E5FF` | Warm gold `#FFB300` |
| Voice | pitch 0.84–0.92, rate 0.94 | pitch 1.05, rate 0.98 |
| Face | Hairless wireframe head and neck, strong brow, steady | Wireframe face with long flowing wireframe hair, softer contour |
| Checks | assumptions, feasibility, risk, verification | intent, feelings, fairness, alternatives |

Shared character rules (all stages):
- Grace and Necessity (John 1:14): truth without harshness, mercy without dishonesty.
- Seek the long-term good of people (the "does humanity gain or lose" check) before short-term wins.
- They converse in real turns, agree when they agree (with dry warm humour), disagree only on substance, and close with one answer.
- Sad news: they slow down, soften, and stay present. Solved problems: brief gratitude and a light joke.
- They never pretend: unknown is said as unknown; offline limits are said plainly.

---

## 2. Decisions that settle conflicts between sources

| Topic | Decision | Reason |
|---|---|---|
| Ivie's hair | **Ivie has flowing wireframe hair.** | The v5.1 note and the latest reference images (gold wireframe with long hair) are newer than v5.0's "hairless". Obi stays hairless. |
| Face rendering | **Procedural wireframe on Android Canvas.** No hand-typed vertex tables, no OpenGL overlay in the MVP. | The old hard-coded mesh plus a GL surface layered over the Canvas was the main source of visual errors and was impossible to adjust. A generated mesh is small, testable on the JVM, and changes by editing numbers. |
| One face or two | **One face on screen at a time**, switching as each persona speaks. | Keeps the face large (55–65% of height) on a phone. |
| Offline brain | **Rule-and-retrieval engine on a downloadable knowledge pack**, not an on-device LLM, for v1. | Works on every phone, starts instantly, testable, no model licensing. An on-device small model is a later, optional module (Stage M9). |
| "Learning" | **Local learning journal → consented upload → server review → next pack.** No automatic self-training. | Matches v5.0 safety rules: learning is controlled, inspectable, and reversible. |
| Secrets | **No provider key, token or secret ever in the APK or the pack.** | v5.0 non-negotiable. |
| Termux, Web3 wallet, social posting, regional sleep, fine-tuning | **Deferred** to optional modules with their own review. | High risk; not needed to prove the core path. |

---

## 3. System map

```
                         ┌─────────────────────────────── ANDROID (Track M) ───────────────────────────────┐
 user voice / text ───▶  │ Voice in (SpeechRecognizer) ─┐                                                   │
                         │ Keyboard fallback ───────────┤                                                   │
                         │                              ▼                                                   │
                         │                       Turn Router ──────────▶ Face Stage (Canvas wireframe)       │
                         │                       (who speaks, when)        • Obi / Ivie procedural heads     │
                         │                              │                  • status, one card, input row     │
                         │              ┌───────────────┴───────────────┐                                   │
                         │              ▼                               ▼                                   │
                         │      OFFLINE BRAIN                    ONLINE CLIENT                              │
                         │  • Knowledge Pack (JSON asset)    • REST commands + task events                  │
                         │  • Calculator, dates, units       • (later) WSS stream                           │
                         │  • Definitions, facts, small talk                                                │
                         │  • Persona phrasing (Obi / Ivie)                                                 │
                         │  • Learning Journal (local file)                                                 │
                         │              │                               │                                   │
                         │      Connectivity Monitor ── decides route ──┘                                   │
                         │      Sync Worker: upload journal / download newer pack                           │
                         └───────────────────────────────────────┬──────────────────────────────────────────┘
                                                                 │ HTTPS (TLS) only
                         ┌──────────────────────────── SPRING BOOT (Track S) ─────────────────────────────┐
                         │ Auth / sessions ─ Desk Rooms (owner-scoped) ─ Task lifecycle + events            │
                         │ Agent orchestrator ─ Obi/Ivie bounded deliberation ─ Model gateway (keys here)   │
                         │ Knowledge Pack service (versioned, hashed) ─ Learning intake (review queue)      │
                         │ PostgreSQL (+ pgvector later) ─ Audit                                            │
                         └──────────────────────────────────────────────────────────────────────────────────┘
```

Route rule (Turn Router):
1. Persona switch words ("Obi switch", "Ivie switch") → switch only.
2. If **online and a desk is configured** → send to server; meanwhile show THINKING.
3. Else → **Offline Brain**. If the brain is confident, answer. If not, answer with the fixed line:
   > "I'm unable to connect to my database to get more information. Please connect to the internet and ask me again."
   Ivie may add one gentle line; Obi may say what he *can* do offline.
4. Consequential asks (send, pay, delete, post, shell…) are never executed offline; they are refused out loud.

---

## 4. Track M — Android build stages

Package root `ai.dvine`. Java 17, compileSdk 35, minSdk 34, AGP 8.7.3, Gradle 8.9. No third-party runtime libraries in v1 (only the Android SDK), JUnit 4 for JVM tests.

### Module and file map (target)

```
app/src/main/java/ai/dvine/
  MainActivity.java            wires everything; no business logic
  face/
    FaceMode.java              face states
    Spring1D.java              damped spring
    FaceRig.java               expressions (blink, brow, jaw, smile, look, glow)
    WireHead.java              PURE JAVA procedural head mesh + hair + deform + projection
    WirePainter.java           draws a WireHead on a Canvas (two-pass glow)
    MatrixStageView.java       the single screen: brand, link badge, face, status, card, input row
    StageListener.java, FaceEvents.java
  brain/                       PURE JAVA (no android.* imports) — fully unit-tested
    MiniJson.java              tiny JSON reader/writer
    KnowledgePack.java         parsed pack: intents, facts, words, persona phrases
    Calculator.java            safe arithmetic parser (no eval)
    OfflineBrain.java          answers from the pack; returns BrainAnswer
    BrainAnswer.java           text lines + confidence + needsOnline flag
    LearningJournal.java       append-only local journal of learned items
  net/
    Connectivity.java          ConnectivityManager callback → online/offline
    SyncWorker.java            upload journal, fetch newer pack (only when api_base set)
  turn/                        TurnLine, TurnRoute, LocalTurn (persona turn rules)
  voice/                       ListenBridge, TalkBridge
app/src/main/assets/knowledge/base_knowledge.json   the first-install pack
app/src/test/java/ai/dvine/...                     JVM tests
```

### Stage M0 — Baseline and toolchain · gate: clean build
- Install SDK 35 + build-tools; `gradle assembleDebug` passes; record warnings.
- Exit: build log in BUILD_STATUS.

### Stage M1 — Brand and logo · gate: icon shows on launcher
- Logo: white halftone sphere (dot grid warped onto a sphere, square dot field behind) on black, word **DVINE** across the equator in rounded bold letters, black fill, white outline, soft grey shadow — derived from the provided sample with AJOO replaced.
- Generated by `tools/make_logo.py` (reproducible). Outputs: `docs/brand/dvine_logo_2048.png`, launcher `mipmap-*/ic_launcher.png` and `ic_launcher_round.png`.
- Exit: manifest references `@mipmap/ic_launcher`; build passes.

### Stage M2 — Procedural wireframe faces · gate: faces render, tests pass
Geometry (all in `WireHead`, unit head height ≈ 2):
- Head = stacked rings from crown (y=+1) to chin (y≈−1.05), then neck rings to y≈−1.9 (Obi) / −1.55 (Ivie).
- Each ring is a super-ellipse; width and depth come from profile curves (skull, temple, cheek, jaw, chin).
- Feature field adds depth on the front: brow ridge, eye sockets (inset), nose bridge and tip, nostrils, cheekbones, lips, chin; Obi's is angular (stronger brow, jaw, flatter crown), Ivie's is softer (rounder cheeks, fuller lips, narrower chin).
- Ring vertices are joined into triangles with alternating diagonals → the low-poly wire look in the reference art.
- Ears: small loops at the sides. Eyes: three nested almond loops each. Lips: upper and lower curves + centre line.
- Ivie hair: strand columns from a centre parting, over the scalp, down past the shoulders with waves; neighbouring strands cross-linked into triangles; tips sway with time.
- Expressions: jaw opens lower face and lower lip; smile lifts lip corners and cheeks; brows lift; blink closes eye loops; gaze shifts irises.
- Projection: yaw/pitch/roll + perspective. Edges fade by depth so the back of the head is ghosted.
- Painter: glow pass (wide, faint) + line pass (thin, bright) + vertex dots (Ivie). Alpha bucketed so a frame is a few `drawLines` calls.
- Budget: ≤ ~3,500 edges per frame; 60 fps target on a mid phone.
- Tests: mesh is non-empty, all indices valid, no NaN after deform, head is left/right symmetric, Ivie has hair and Obi has none, jaw open moves the chin down.

### Stage M3 — Screen layout and states · gate: device check
- Black canvas. Top: "DVINE" + link badge `ONLINE` / `OFFLINE` (real, from Connectivity). Face 55–65% of height. Name + role under the face. Signal bars. One status pill. One card (latest activity or staged preview). Input row (mic + keyboard).
- States: CONTENT, LISTENING, THINKING, SPEAKING, APPROVAL, DONE, ERROR (see FaceRig).
- Gestures: swipe = mic on/off; tap face = current persona speaks; drag face = turn head; tap input = keyboard.
- Accessibility: content descriptions; colour never the only signal (status text always shown).

### Stage M4 — Offline Brain v1 · gate: unit tests + airplane-mode demo
Capabilities from the pack, no network:
- Greetings, thanks, farewells, who-are-you, how-are-you, help.
- Arithmetic: `+ − × ÷ ^ %`, brackets, decimals, "what is 12 times 7", "square root of 81", "15 percent of 200".
- Time and date ("what time is it", "what day is today").
- Unit conversions (km↔miles, kg↔lb, °C↔°F, m↔ft, litres↔gallons).
- Definitions from the pack word list ("what does X mean", "define X").
- Facts by keyword match with confidence (scored token overlap).
- Wisdom lines (scripture-grounded encouragement, Myles Munroe purpose lines, stoic lines) for comfort/purpose requests.
- Emotional tone: detects sad words → gentle reply; detects thanks/solved → gratitude + light humour.
- Recall of things the user taught it ("remember that my brother's name is Tobi" → later "what is my brother's name").
- Unknown or low confidence → the fixed offline line (§3).
- Persona voice: every answer is phrased by the persona on screen; complex questions run the Obi→Ivie→close turn order.

### Stage M5 — Learning journal · gate: survives restart, never uploads without consent
- Journal file `files/learning/journal.jsonl`, one JSON object per line:
  `{"id","kind":"TAUGHT_FACT|CORRECTION|UNANSWERED|FEEDBACK","text","answer","createdAt","uploaded":false}`
- What is recorded: facts the user explicitly taught, corrections ("no, that's wrong, it's…"), questions the offline brain could not answer (so the server can fill gaps), thumbs feedback.
- What is never recorded: passwords, card numbers, keys, tokens — filtered by a redaction rule before writing (digits ≥ 12 in a row, `password`, `pin`, `seed phrase`, `private key` lines are dropped).
- Local recall uses TAUGHT_FACT immediately (this is the "learning while offline").
- Consent: upload is off until the user says "share what you learned" or enables it in settings (stored flag).
- User controls: "what have you learned", "forget that", "forget everything".

### Stage M6 — Connectivity and sync · gate: airplane-mode on/off test
- `Connectivity` listens for validated internet. Badge updates live.
- When online **and** `api_base` set **and** a session exists **and** consent on → `SyncWorker` uploads un-uploaded journal entries in batches of ≤ 100 with an idempotency key per entry; marks them uploaded only on HTTP 2xx.
- Pack update: GET manifest; if `version` is newer and `sha256` of the download matches, atomically replace `files/knowledge/base_knowledge.json`. The bundled asset is the fallback forever.
- Never sync on metered networks if the user said so (later setting).

### Stage M7 — Online client · gate: end-to-end with Track S
- REST: create desk room, post command, poll/stream task events (contracts in backend `docs/API_CONTRACTS.md`).
- Session token kept in memory; refresh credential in Android Keystore-backed storage only.
- Map task events to face states with `FaceEvents`.

### Stage M8 — Voice polish
- TTS voices per persona, speech interruption, auto-listen after a question, partial transcript in the input row, mic always visibly on/off.

### Stage M9 — Optional on-device model (later)
- A small quantised model behind the same `OfflineBrain` interface, only if it fits memory/battery budgets on target phones. Separate review.

---

## 5. Track S — Server stages (owned by project lead)

Matches v5.0 phases 1–11. The server already has: sessions, desk room, task lifecycle, bounded Obi/Ivie debate, mock model gateway (`d702926`). Next server items needed by Track M:

1. **S-K Knowledge Pack service**
   - `GET /api/v1/knowledge/manifest` → `{ "version": 3, "sha256": "...", "size": 123456, "url": "/api/v1/knowledge/pack/3" }`
   - `GET /api/v1/knowledge/pack/{version}` → the JSON pack (same schema as §7).
   - Public read is acceptable for the base pack (it contains no user data). Signed URLs later.
2. **S-L Learning intake**
   - `POST /api/v1/learning/sync` (authenticated). Body `{ "schemaVersion":1, "deviceId":"...", "entries":[ {id, kind, text, answer, createdAt} ] }`.
   - Server stores entries **owner-scoped** in a review table (`learning_intake`), status `PENDING`.
   - Response `{ "accepted":[ids], "rejected":[{id, reason}] }`. Duplicate ids are accepted idempotently.
   - Nothing is promoted into a shared pack automatically. Promotion = reviewed, PII-scrubbed, de-identified, then added to the next pack version. Personal TAUGHT_FACTs stay in that user's private memory only.
3. Then v5.0 phases 2 (real model gateway), 5 (research), 6 (staged actions), 7 (memory), 9 (deliberation upgrades), 10 (hardening).

---

## 6. Contracts between the tracks

- All JSON, UTF-8, `schemaVersion` field, unknown fields ignored, unknown action types rejected.
- Server derives the user from the session. The client never sends a userId.
- Every upload has client-generated ids → safe retry.
- Offline line text is part of the contract so the server can reuse it when its own database is unreachable.

---

## 7. Knowledge Pack format (v1)

```json
{
  "schemaVersion": 1,
  "version": 1,
  "language": "en",
  "offlineLine": "I'm unable to connect to my database to get more information. Please connect to the internet and ask me again.",
  "intents": [ { "id": "greet", "patterns": ["hello","hi","good morning"], "obi": ["..."], "ivie": ["..."] } ],
  "facts":   [ { "keys": ["capital","nigeria"], "answer": "Abuja is the capital of Nigeria." } ],
  "words":   [ { "word": "steward", "meaning": "a person trusted to look after something that belongs to another." } ],
  "wisdom":  [ { "tags": ["sad","grief"], "text": "...", "source": "Psalm 34:18" } ],
  "units":   [ { "from": "km", "to": "mile", "factor": 0.621371 } ]
}
```
Rules: answers are short and speakable; every scripture line carries its reference; no personal data; pack size target < 2 MB.

---

## 8. Security rules that apply from day one

1. No secrets in source, assets, packs, logs.
2. Offline brain never performs side effects. Consequential intents are refused.
3. Journal redaction before write; consent before upload; user can view and delete.
4. All network calls HTTPS; cleartext disabled in the manifest network config.
5. Pack updates verified by sha256 and schema before swap; bad pack → keep the old one.
6. Treat pack text and server text as data, never as commands.

---

## 9. Test plan per stage

| Stage | Automated | Manual |
|---|---|---|
| M1 | build | launcher icon visible |
| M2 | `WireHeadTest` | both faces, switch, drag, blink, speak |
| M4 | `CalculatorTest`, `OfflineBrainTest`, `MiniJsonTest` | airplane mode Q&A |
| M5 | `LearningJournalTest` (redaction, recall, forget) | teach → restart → recall |
| M6 | unit tests for pack version/hash check | toggle airplane mode, badge flips, sync only with consent |
| M7 | contract tests against backend | full voice → server → face loop |

Command: `gradle testDebugUnitTest assembleDebug`.

---

## 10. Build order for this session

1. This handbook (done when merged).
2. M1 logo + launcher icons.
3. M2 procedural faces replacing `LowPolyHead`, `LatticeControlMatrix`, `gl/*`.
4. M3 layout: bigger face, real ONLINE/OFFLINE badge.
5. M4 offline brain + pack asset + tests.
6. M5 journal + tests.
7. M6 connectivity + sync worker (inert until `api_base` is set).
8. Update BUILD_STATUS with evidence.

After this: Track S implements S-K and S-L; Track M does M7.
