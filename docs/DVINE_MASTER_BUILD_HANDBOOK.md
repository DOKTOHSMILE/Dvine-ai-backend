# DVINE — Master Build Handbook

Version 1.1 · the playbook we build from. It merges the Master Specification v1.0, the Implementation-Ready Engineering Plan v5.0, the v5.1 face note, and every decision from the planning conversation. When two sources disagree, this file names the winner and why.

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
| Ivie's hair | **Ivie has light swept-back wireframe hair** (approved art: Obi angular flat-top bust, Ivie profile with hair streaming behind). Remove it if it gets too busy. | The owner's preferred image is newer than v5.0's "hairless". Obi stays hairless. |
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

### Stage M3 — Zero-screen stage and states · gate: device check
- **There is no screen UI.** Pure black, the face, the hands, the drawing canvas and sparkles. No buttons, no input row, no cards, no menus. See §11.
- A single faint subtitle line shows what is being said (accessibility; can be turned off by voice: "hide subtitles").
- A tiny dot at the top edge shows online (persona colour) or offline (grey); no text badge.
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


---

# Part II — v1.1 additions

## 11. Zero-screen interaction (no UI, only voice)

The phone shows only the persona. Everything else is spoken.

| Gesture | Effect |
|---|---|
| Swipe anywhere | microphone on / off |
| Tap the face | the persona on screen speaks to you |
| Drag the face | turn the head |
| Long-press (accessibility only) | system keyboard for silent typing |

Rules:
1. **The model asks, the user confirms.** Any action that leaves the app or touches data is first described out loud as a question ("Shall I open WhatsApp with this post…? Say yes to continue."). The mic opens by itself for the answer.
2. **Yes** (yes, yeah, yep, go ahead, do it, send it, confirm, okay) runs it. **No** (no, cancel, stop, don't) cancels. Anything else keeps it pending. A pending action expires after 2 minutes.
3. **Share / post** → Android `Intent.ACTION_SEND` with the text, aimed at the named app (WhatsApp, X, Facebook, Instagram, LinkedIn, Telegram) or the system chooser. The user lands on the post screen in that app and presses send **themselves**. DVINE reports "handed over", never "posted" (v5.0: a share-sheet handoff is not proof of publication).
4. **Copy** → content is put on the clipboard, then the persona says "Copied. It's on your clipboard."
5. Payments, transfers, deletion, shell, passwords: still refused out loud.

Code: `brain/ActionPlanner.java` (pure, tested), executed by `act/Actions.java`.

## 12. First run: the name

On the first open, before anything else:
1. Obi appears: "Welcome. I'm Obi, and with me is Ivie. Before we begin, what's your name?" The mic opens.
2. The answer is parsed ("my name is Valentine", "I'm Val", "Valentine") by `brain/Onboarding.java`.
3. Ivie confirms: "Welcome, Valentine. We'll remember that." The name is stored on the phone (`profile` prefs) and in memory as "my name".
4. From then on greetings use the name. "Call me …" changes it.
5. If the name isn't caught twice, they move on kindly and ask again next launch.

## 13. Hands and gestures

Both personas have wireframe hands (`face/WireHand.java`, procedural like the head: palm and three-segment fingers with per-finger curl, spread and wrist pose). Hands rise into view under the chin for a gesture, hold, then lower.

Supported gestures (emoji key → pose): 👍 👎 👋 🤚 🖐 ✋ 🖖 👌 🤌 🤏 ✌ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 👇 ☝ 🫵 ✊ 👊 🤛 🤜 🫳 🫴 🫱 🫲 🫸 🫷 🙏 🙌 👐 🤲 🫶 🤝 ✍ 🤳 💅. 🖕 is deliberately not implemented.

When they gesture:
- greetings → 👋, thanks → 🙏 or 👍, agreement between Obi and Ivie → 🤝 / 🫶, comfort on sad news → 🤲, explanations → ☝ / 👉, a joke → 🤙, encouragement → 🙌.
- The user can ask: "show me a thumbs up", or say/type the emoji.

## 14. Explanation canvas (illustration mode)

The black space around the face is a drawing canvas. When a drawing helps, the face moves up and small, and lines draw themselves in the persona colour while they speak, like a whiteboard video.

- `face/Sketch.java` holds strokes (polylines in a −1..1 box), circles, arrows and short labels, each with a start time; `StageView` draws them progressively.
- v1 drawings: basic shapes (circle, square, triangle, star, heart, arrow), addition as dot groups ("explain 3 plus 4"), fractions as a pie ("explain a half / a quarter"), the solar system, and a simple plan timeline ("draw steps").
- Later the server can send sketch scripts with an answer (same JSON shape: `{"strokes":[{"kind":"line|circle|arrow|label","pts":[...],"text":"","t":0.0}]}`).

## 15. Offline knowledge up to 1 GB

| Tier | Size | How it arrives | Storage |
|---|---|---|---|
| Core pack | 1–5 MB | inside the APK (first install) | JSON asset |
| Update pack | 1–5 MB | replaces core when newer, sha256-checked | `files/knowledge/base_knowledge.json` |
| Shards | up to ~1 GB total | user agrees by voice ("download the full library"); Wi-Fi + charging by default; resumable | `files/knowledge/shards/*.json` now, SQLite FTS5 databases for shards > 20 MB |

- Shard subjects: dictionary (English + Pidgin, Yoruba, Igbo, Hausa, French), Bible (public-domain KJV/WEB), encyclopedia summaries, maths/science explanations with sketches, health first aid, business basics, Nigeria/Africa civic and geography.
- Before download: check free space ≥ 2× shard size, tell the user the size out loud, ask to confirm.
- The brain searches core first, then shards; answers carry the shard name as their source.
- Manifest grows to `{"version", "sha256", "url", "shards":[{"id","version","size","sha256","url"}]}`.
- Today: `KnowledgeStore` merges any `shards/*.json` with the core pack. SQLite shards are Stage M10.

## 16. Voices and ambience

- **Voices:** prefer Nigerian English (`en-NG`) voices from the system TTS (Google TTS ships them), else African/British English. Obi takes the deeper voice, Ivie the warmer one. Speech is slow and gentle: Obi rate 0.86 / pitch 0.86, Ivie rate 0.90 / pitch 1.02. A custom neural Nigerian voice (server TTS) is a later upgrade behind the same `TalkBridge`.
- **Background instrumental:** a soft, slow, low pad synthesised on the phone (`voice/AmbientPad.java`, no audio files), rising gently while they speak and fading in silence. Obi's chord sits lower and darker, Ivie's warmer and brighter. Volume stays far under the voice. "Music off" / "music on" by voice.

## 16b. Skin, styles and self-redraw

- Default: solid deep-brown skin (Obi a shade deeper than Ivie) on faces and hands, lit and depth-sorted, with the persona's glowing wires over it.
- Three styles: **skin**, **wireframe**, **nano-dots** (≈5,000 tiny lit points per head; hair as dotted strands; features stay as lines).
- **The AI redraws itself from the user's preferences** (`face/Look.java`): "make your skin darker/lighter", "dot mode", "wireframe mode", "skin mode", "bigger eyes", "smaller nose", "fuller lips", "stronger jaw", "slimmer face", "longer face", "remove your hair", "longer hair", "make Ivie's hair longer", "reset your look". Each persona has its own look, saved on the phone.
- No lag: the mesh is rebuilt once when the look changes (milliseconds), never per frame.
- Illustrations: on request the face moves up and the canvas draws and describes a picture: house, tree, sun, flower, person, car, fish, mountains, boat, moon, eye, plus the maths/science drawings in §14. Server-sent sketch scripts later extend this to any topic.

## 17. Magic ✨

- Sparkles drift up around the persona while thinking, burst softly when an answer is ready or a copy/share succeeds.
- The face assembles from scattered points on wake and dissolves to points on switch.
- Hands leave a faint light trail.
- Everything stays restrained: the face is always the main thing on screen.

## 18. Updated build order (v1.1)

1. Playbook update (this part).
2. ActionPlanner + Onboarding + gesture/sketch hints in the brain, with tests.
3. WireHand + gesture table, with tests.
4. Sketch engine + drawings.
5. Face refinement: shaded triangle fill for 3D depth, stronger jaw and neck.
6. New zero-screen `StageView` replacing `MatrixStageView`.
7. Voices (en-NG preference) + AmbientPad.
8. Shard merging in KnowledgeStore.
9. Build, tests, previews, BUILD_STATUS.


## 19. Basic knowledge, self-learning and inner thoughts (v1.2, built)

**Basic knowledge (offline):**
- Core pack v2 (`assets/knowledge/base_knowledge.json`, ~43 KB): 21 intents (including Pidgin greetings), 142 facts (Nigeria and Africa, world, science, health and first aid, maths formulas, money basics, Bible), 74 core words (with Pidgin, Yoruba, Igbo and Hausa words), 45 scripture and wisdom lines, each with its reference.
- English dictionary shard (`assets/knowledge/shards/dictionary_en.json`, ~3.2 MB): 20,775 common words from WordNet 3.1 with part of speech, meaning, example and synonyms. Built by `tools/make_dictionary.py`; licence in `WORDNET_LICENSE.txt`. The core pack loads instantly; the dictionary loads in the background.
- The rest of WordNet (~150k lemmas) and other libraries go in the downloadable shards (§15).

**Fluid search (nearest meaning):** if the exact words find nothing, the brain stems the words (biggest → big) and tries their dictionary synonyms both ways (big → large → "largest planet"), saying so softly first.

**Self-learning (on the phone, reviewed before sharing):**
- "Remember that my sister is Ada" (personal) and "remember that the speed limit in town is 50" (general).
- When it can't answer, it saves the question; "the answer is …" right after teaches it.
- "No, it's …" corrects any answer, and learned answers win over the pack next time.
- "What have you learned" lists the latest; "forget that" and "forget everything" delete.
- Secrets are never stored; upload only after "share what you learned" (§5 S-L).

**Inner thoughts:** before answering, the persona thinks aloud in a softer, lower, slower voice ("Let me check my dictionary.", "Let me look through what I know.", "That exact wording isn't in my notes. Let me try words that mean the same."). The face shows THINKING and the subtitle starts with "…". "Stop thinking aloud" and "think aloud" toggle it.


## 20. Facing the user and voice tuning (v1.2.1)

- Both faces rest looking straight at the user; only small living motion (breathing, blinks, a slight sway while thinking) moves the head.
- Voices: `voice/VoiceTuning.java` ranks offline English voices (Nigerian English first, then other African English, then British, then any) and gives Obi and Ivie **different** voices, matching gender when the engine reports it. Defaults stay close to natural (Obi pitch 0.93, rate 0.90; Ivie pitch 1.04, rate 0.94), because big pitch shifts make TTS sound robotic.
- Inner-thought voice: same voice as the persona, at 72% volume, about 4% lower and 8% slower.
- Tune by voice, per persona, saved on the phone: "try a different voice", "speak slower", "speak faster", "deeper voice", "higher voice", "reset your voice" (add "Ivie" or "Obi" to choose who).
- If the phone has no Nigerian English voice, install it: Settings → System → Languages → Text-to-speech → Google → Install voice data → English (Nigeria).


## 21. Automatic backup of offline learning (v1.2.2)

- What the phone learns offline (taught facts, corrections, unanswered questions) uploads to the user's DVINE account **automatically** whenever it is online: on reconnect, right after something new is learned, and on "upload what you learned". Sharing is on by default and announced at first run; "stop sharing" keeps everything on the phone, "share what you learned" turns it back on. Secrets are never stored or sent.
- Sign-in without any secret in the app: the phone registers once (`POST /api/v1/devices/register`), keeps its device secret encrypted with an Android Keystore key (`net/DeviceVault`), and trades it for 12-hour session tokens (`POST /api/v1/sessions/device`) in `net/SessionClient`.
- Server address: the build's `api_base`, or by voice "connect to server https://…". HTTPS only.
- "Sync status" says how many items are waiting; a faint "Backed up N things I learned." subtitle confirms each upload.
- Server side keeps every upload owner-scoped and PENDING review (§5 S-L).


## 22. Nano-dot rendering (v1.4)

**Everything on screen is nano dots on curved 3D surfaces.** Lines only ever show edges. Dots can sit anywhere on a surface, so cheeks, lips, skulls, limbs, spheres and tori are true curves. There is no suit, no armour and no HUD rings. The persona is a glowing being of dots.

- **Swarm** (`face/nano/Swarm.java`): up to 10,000 particles, each springing to a target point from the current *form*. Changing the form makes the dots fly apart and reassemble: switching persona, face ↔ full body, poses, "redraw yourself", new looks.
- **Sculpt** (`face/nano/Sculpt.java`): a small signed-distance sculpting kit. Ellipsoids, tapered capsules, rounded boxes and tori are melted together with a smooth union, and ellipsoids can be carved out. `sample()` spreads dots evenly over the melted surface: it throws candidates on each shape, pulls them onto the surface with Newton steps, then applies Poisson-disc thinning. It also returns normals for shading and curvature, so creases glow.
- **Head** (`face/nano/DotHead.java`): sculpted from cranium, face, jaw, chin, cheekbones, brow ridge, eye sockets and eyeballs, nose bridge, tip and wings, lips, cupped ears and neck. Styled on the two reference heads.
  - **Obi**: angular squared crown, square jaw and chin, and a neck ending in a V cut whose edge glows.
  - **Ivie**: soft oval, a hair cap, and about 40 strands that sweep back from the hairline over the skull and stream out behind, rippling in a breeze.
  - Shading: a soft key light and a bright silhouette, plus extra glow where the surface bends sharply (nose, lips, ears, brow). This gives the glowing-outline hologram look.
  - Lids, brows, nostrils and lip lines are rows of dots seated on the skin by ray casting. Speech, blinks, smiles, brows and glances move the dots each frame; nothing is rebuilt.
- **Body** (`face/nano/DotBody.java`): a holographic anatomy figure, like a 3D studio reference model.
  - **Skin**: a smooth sculpted surface with a bright silhouette, including hands with fingers and thumbs, and feet. It rests in an A-pose.
  - **Inside**: a faint skeleton of spine, ribs, breastbone, collarbones, pelvis, long bones and kneecaps, plus a heart glow.
  - **Rig**: 17 bones with linear-blend skinning at the joints.
  - **Poses** blend in half a second: stand, T-pose, arms up, wave, point, squat, sit cross-legged, kneel, run, walk, bow, dance and handstand.
  - **Motion**: the lowest point always rests on the floor. The body slowly turns, and "turn around" orbits it a full circle.
  - **Head**: the persona's living head rides the head bone.
  - **Scale**: the body is handed to the camera at 0.4 scale so its perspective matches the face view.
- **Drawings** (`Forms.Drawing`): every sketch from §14 is built in 3D. Lines become round tubes of dots, and closed outlines (heart, star, house, fish, moon, polygons) become cushioned solids with a curved front and back. Circles become rings, and balls (tree canopy, sun, planets, flower centre, clouds) become shaded spheres. The drawing sways gently so the depth shows; labels stay as glowing text.
- **Objects** (`Forms.Object3D`): solid, shaded and slowly turning: globe, rounded cube, pyramid with filled faces, torus, cone, cylinder, a 3D heart, DNA (tube strands and base pairs) and an atom (nucleus of spheres, orbits, electrons).
- **Painter** (`face/nano/NanoPainter.java`): every dot gets a soft glow pass and a bright core with a faint shimmer, bucketed by brightness and dimmed with depth. The scene is a Blender-like dotted grid floor with red X and green depth axes.
- **Performance**: heads and bodies are sculpted once per look on a background thread (about 0.1–0.2 s each on a desktop). Each frame only moves dots. Budget: about 5–8.5k dots for a head, about 8k for a body, and up to 3.9k for a drawing. Frame rate on a mid-range phone is still to be measured (Stage M2 gate).
- **Voice**:
  - Views and redrawing: "show your full body", "show your face", "redraw yourself", "turn around".
  - Poses: "do a T-pose", "arms up", "squat", "sit cross-legged", "kneel", "run", "walk", "bow", "dance", "do a handstand", "stand up". In body mode, 👋 and 👉 become wave and point poses.
  - Objects: "draw the globe / a cube / a pyramid / a torus / a cone / a cylinder / DNA / an atom / a sphere / a 3D heart".
  - Drawings: every drawing from §14. Shape requests (eyes, nose, lips, jaw, face width and height, hair) re-sculpt the head.

## 23. The living body: zoom, exercise, narration, scenes and touch (v1.5, built)

- **Head first.** The app always opens on the face. The full body appears only when the user asks for it ("show your full body", any pose, exercise or scene). The camera then **zooms out** from the face to the whole body, keeping the head in place while it pulls back, and the dots fly from the head into the body (`StageView` zoom spring, `Swarm.remap`). "Show your face" zooms back in. A drawing or 3D object always returns to the face view.
- **Exercises and stunts** (`DotBody`, looping until another pose is asked for):
  - Exercises: push-ups, plank, sit-ups, jumping jacks, squats, lunges, stretch, boxing, kick.
  - Stunts: cartwheel (side to side), backflip (crouch, tucked flip, soft landing) and spin, plus the earlier poses.
  - Root movement: two extra channels, travel and lift, give hops, jumps and travelling stunts. The floor rule lifts the body instantly but settles it gently, so nothing sinks through the floor.
- **Narrating body.** While the persona speaks in body mode, a standing body explains with its hands. Arms open and turn, there are shrugs, nods and small weight shifts (the "talk" layer, `DotBody.setTalking`). A body already in a pose or exercise carries on.
- **Scenes** (`brain/Scenes`): each beat pairs a narrated line with a pose, and each pose holds about as long as its line takes to say slowly.
  - Scenes: "The hare and the tortoise", a morning workout, "The warrior" (drama), and a stunt show.
  - Voice: "act a scene" (plays the next scene in turn), "act out the tortoise and the hare", "let's do a workout", "show me some stunts", "tell me a story with your body".
  - The scene ends standing.
- **Touch on the body.** In body view, a tap finds the dot nearest the finger, preferring the nearer surface. It names the body part: hips, belly, chest, neck, head or face, and left/right arm, forearm, hand, thigh, knee and foot. The persona answers ("You found my left knee."), and a tapped hand waves back. A tap on the face works as before.
- **Joint limits.** Every joint is clamped to anatomical limits before posing (`DotBody.LIMITS`, `limit()`), so a request can never bend a knee backwards or turn a neck round. The hips are the root and turn freely, so the body can lie down, flip and cartwheel. `MotionTest` checks every pose at many moments in time against the limits.
- Voice examples: "do 10 push-ups", "do a plank", "jumping jacks", "do squats", "lunges", "sit-ups", "stretch", "box", "kick", "do a cartwheel", "do a backflip", "spin".

---

# Part III — Blueprints for the embodied DVINE (v1.5)

The project lead supplied three blueprints: the artificial brain, the skeletal system, and self-vision plus touch. They are kept here in condensed form, with every part represented, and mapped onto what DVINE already has. Each chapter ends with its build order. **Rule for all three:** these systems give DVINE functional self-representation, coordinated behaviour and simulated reactions. They do not prove consciousness, real feelings or felt touch, and DVINE must never claim them.

## 24. ANBA — the Artificial Neural Body Architecture

**Vision.** One integrated artificial human whose cognition, memory, perception, face and body share one consistent state and coordinate continuously. It is not a chatbot with an avatar attached. Example: when someone calls its name, DVINE should be able to:
1. Hear the name and recognise that it is being addressed.
2. Shift its attention to the speaker.
3. Recall the person, within its memory permissions.
4. Read the speaker's tone.
5. Decide how to respond.
6. Turn its head and eyes towards the speaker.
7. Show a fitting expression.
8. Speak, with its lips in sync.
9. Remember the interaction if it is useful, and learn from it.

**Principle.** Reproduce the brain's *capabilities and coordination*, not a literal copy of each structure. The mappings below are functional approximations, and the regions overlap.

### 24.1 Brain regions → engineering functions

| Biological part | Function | DVINE equivalent | DVINE today |
|---|---|---|---|
| Cerebral cortex | perception, reasoning, planning | cognitive network | `OfflineBrain` rules + retrieval; server LLM later (M7) |
| Prefrontal cortex | planning, working memory, inhibition | executive intelligence (goal manager) | `ActionPlanner` (confirm-to-act), `PendingAction` |
| Motor / premotor cortex | movement commands, sequencing | motor command generator, action planner | `DotBody` poses, `Scenes` beats |
| Somatosensory cortex | touch, body position | tactile + body-state processor | body-tap part lookup (§23) |
| Visual cortex | vision | visual perception network | not yet (camera is out of scope for now) |
| Auditory cortex, temporal lobe | sound, language, recognition | auditory + language networks | Android speech recognizer, `TalkBridge` |
| Hippocampus | episodic memory, space | episodic + spatial memory | `LearningJournal` (JSONL), sync to server |
| Amygdala | emotional salience | appraisal | feelings intents in the brain |
| Basal ganglia | action selection, habits | action-selection network | brain routing order (`beforeGate`), action gate |
| Cerebellum | coordination, timing, motor learning | movement coordination + correction | `Swarm` springs, pose blending, floor rule |
| Thalamus | routing, attention | attention router | stage modes (listening/thinking/speaking) |
| Hypothalamus, brainstem, autonomic | drives, homeostasis, automatic functions | internal-state + background controller | idle breathing, blinking, `FaceRig` micro-motion |
| Spinal cord, peripheral nerves | fast relay, reflexes | digital spinal cord (low-latency control) | per-frame `StageView` loop |

### 24.2 Cortex networks (Part 2)

- **Frontal: executive intelligence.** It understands the objective, breaks it into steps, weighs actions, resolves conflicts, monitors progress, replans, and asks permission where needed. It delegates execution to movement controllers.
- **Working memory** has limited capacity, explicit expiry and priorities. It is not an archive.
- **Planning and prediction** consider positions, joint limits, smoothness, and whether the torso should help.
- **Inhibitory control** knows when *not* to act: stop inappropriate or obsolete actions, prevent contradictory commands, respect restricted actions, and interrupt on a safety violation. Build it as a goal service with priorities, deadlines, cancellation tokens, permissions and task states.
- **Parietal: body, space and sensorimotor integration.** It knows where every body part is, keeps a map of objects, people and paths, and closes the see → act → check → adjust loop.
- **Occipital: visual intelligence.** Detect objects, faces (when authorised), pose, hands and gestures; segment the scene; estimate depth; track motion. A beautiful face is not the same as visual intelligence: they are separate capabilities.
- **Temporal: hearing and language.** Speech and voice-activity detection, sound classification, prosody, speech-to-text, intent, context, ambiguity, response planning, text-to-speech and speaking style, all connected to the rest of the brain ("I'm cold" means empathy, not just text).

### 24.3 Memory (Part 3)

Use separate stores by purpose, never one giant chat history:

| Memory | Holds | Suggested store |
|---|---|---|
| Sensory | the last frames, audio and body readings | short ring buffers |
| Working | the current task, topic, targets, next step | in-memory state |
| Episodic | events (id, time, context, action, outcome, confidence, importance, retention) | relational/document DB |
| Semantic | facts and concepts, with calibrated confidence and evidence | knowledge graph / documents (the knowledge pack today) |
| Procedural | skills as parameterised routines with preconditions and completion checks | versioned skill registry (`DotBody` poses and scenes today) |
| Emotional/social | preferences and context, with access control and deletion | profile with permissions |
| Spatial | where things were last seen versus where they are now | scene graph |
| Prospective | future intentions, with expiry and cancellation | durable scheduler |

- **Consolidation pipeline:** experience → encoding → evaluation (useful? reliable? duplicate? contradicted? sensitive? expiring?) → consolidation → retrieval → revision.
- Repetition does not make a claim true.
- Storage, retrieval, evaluation, updating and skill improvement are separate mechanisms.
- Plausible answers are not memory, and memory is not learning.

### 24.4 Limbic system: emotion, motivation, social (Part 4)

- **Appraisal:** what an event means in context. Criticism is not hostility by default.
- **Internal affect state** (engineering variables, not feelings): valence, arousal, confidence, engagement, frustration, curiosity and urgency. These drive the expression: a smile, a subdued face, a head tilt with a clarifying question, more direct speech, a pause to replan, or attention.
- Never infer a person's emotion from one cue.
- **Goals** carry priority, a completion condition, a deadline, resources, preconditions, failure conditions and cancellation. Safety goals come first.
- **Regulation** sits between appraisal and expression so reactions stay proportionate. Emotion may colour expression and voice. It never overrides facts, permissions or safety.

### 24.5 Basal ganglia: action selection (Part 5)

One action manager scores candidates as `S(a) = w_g·G + w_c·C + w_r·R − w_k·K` (goal progress, context, benefit, cost/risk), after filtering out prohibited and impossible actions. This stops modules from fighting over the body (head left vs head right, smile vs speech, a gesture cut in half).

### 24.6 Cerebellum: natural movement (Part 6)

- **Motor planning** sets targets, chooses a limb, checks reachability and sets duration. Inverse kinematics (IK) turns a hand or foot target into joint angles within limits, and handles unreachable targets gracefully. Forward kinematics (FK) turns joint angles into body-part positions.
- **Proprioception:** the body state is read from the rig, never invented by the language model.
- **Error correction** uses `e = target − actual` with smoothing and tolerances, so the body does not jitter.
- **Motor learning** comes from recorded outcomes and is evaluated offline before deployment.
- **Hierarchy:** strategic → tactical → trajectory → coordination → joints → feedback → safety.

### 24.7 Locomotion (Part 7)

- Gait generation covers the legs, hips, knees, ankles, feet, arm swing, torso and head stabilisation.
- Locomotion states: IDLE, STANDING, STARTING_WALK, WALKING, TURNING, STOPPING, RUNNING, SITTING, STANDING_UP, REACHING, RECOVERING_BALANCE, FALLING and RECOVERY.
- Balance uses contact, centre of mass, support polygon, root motion and IK.
- Navigation uses a nav mesh or A*, producing goals for the gait controller.
- Reflexes run near the body: stop on collision, cancel a reach, cancel a step. High-level reasoning sets the objective; a real-time controller manages the movement.

### 24.8 Nervous system, attention, background control (Parts 8–10)

- **Central network:** reasoning, memory, goals, appraisal, selection. It is not responsible for every frame.
- **Digital spinal cord:** low-latency command distribution, transitions, constraints, interruption and feedback. Commands look like `{action: turn_head_toward, target_id, priority, speed, interruptible}`.
- **Peripheral layer:** cameras, microphone, joint feedback, collisions, completion events, all normalised into one representation.
- **Reflex/safety controller:** stop on collision, cancel invalid targets, reject joint-limit breaches, recover from inconsistent states, obey high-priority stops.
- **Attention (thalamus):** priority `A = w1·relevance + w2·novelty + w3·urgency + w4·safety`, with explicit safety overrides. Attention is shared state that moves gaze, head, torso and listening.
- **Background (brainstem/autonomic):** idle breathing, blinks, posture shifts, gaze drift and a return to neutral, all subordinate to active tasks. Runtime health (frame rate, latency, memory, failures) can lower animation detail under load. Simulated states are never claimed as real tiredness.

### 24.9 Face and body connection (Parts 11–12)

- **Face rig:** eyes, lids, brows, mouth, lips, jaw, cheeks, nose, and head pitch, yaw and roll. Blendshapes follow `M = M0 + Σ wᵢ·ΔMᵢ`.
- **Expressions** are recipes: attentive, happy, confused. They blend with `w_t = w_{t-1} + α(w* − w_{t-1})`.
- **Lip sync** follows audio, phoneme/viseme timing, mouth shapes and expression compositing. Speech articulation and expression must be composited by priority and never overwrite each other.
- **Shared embodied state model:** body (root, locomotion state, hands, head), attention, interaction (listening/speaking), expression and task. Every module reads the same state.
- In DVINE, `FaceRig` and `WireHead.Pose` drive `DotHead`, and `DotBody` carries the same pose.

### 24.10 Nine networks, one loop (Parts 13–14)

- **The nine networks:** cognitive core, perception core, memory core, executive/behaviour core, affect/social core, motor core, face and voice core, world model, and safety/runtime core. The safety core may reject commands from any of the others.
- **Loop:** sensory input → perception → world model → attention → cognition → decision → action planning → motor execution → feedback → learning and memory. Safety checks run throughout.
- The "someone says hello" walk-through (perceive → model → attend → recall → interpret → select → orient → express → speak → sync → verify → remember) must feel like one behaviour.

### 24.11 Stack, control speeds, learning, protocol (Parts 15–18)

- **Stack.** The 3D engine handles rendering, skeleton, blendshapes and IK. Java handles orchestration: sessions, memory, goals, API, validation, events and monitoring. ML inference runs in a suitable runtime.
  - DVINE today is pure Java on Android Canvas with a Spring Boot server. Keep it that way until a native engine is needed.
- **Three control speeds:**
  - A: body control at 30–120 Hz (the DVINE frame loop).
  - B: behaviour coordination at 5–30 Hz (gaze, gestures, scene beats).
  - C: cognition, event-driven, from hundreds of milliseconds to seconds (brain and server).
  - Never let a slow model drive frames.
- **Learning.** Collect experience (states, action, expected vs actual outcome, feedback, model version), with permission. Evaluate success, quality and safety separately.
  - Optimise skills with supervised, imitation or simulated reinforcement learning, or with tuning.
  - Deploy only after simulation tests, safety checks, comparison and a rollback path.
  - Memory learning, skill learning and reasoning improvement are different things.
- **Typed command protocol:** `{command_id, source, target, action, target_object, priority, deadline_ms, interruptible, preconditions}` → `{command_id, status, result}`. It needs versioning, timeouts, retries, de-duplication, cancellation, error codes, telemetry, and stale-command rejection using timestamps.

### 24.12 World simulator, self-monitoring, reliability (Parts 19–21)

- **World simulator:** predict `ŝ(t+1) = f(s_t, a_t)` and compare candidate actions before committing. Start from the engine's scene graph and collisions.
- **Self-monitoring** (operational, not subjective): the active task, evidence, progress, which action failed, service health, and whether to ask for clarification. Report only what can actually be inspected.
- **Reliability:**
  - One owner per joint group, with priorities and interruptible states.
  - Deterministic blending and safe cancellation.
  - Timeouts, watchdogs and dependency tracking against deadlocks.
  - Observations carry timestamps and freshness. Keep current observation, last known value, inference, stored knowledge and assumption separate.
  - Every action has an id, start time, expected result, status, outcome and error code.

### 24.13 Roadmap (Part 22) mapped to DVINE

| ANBA phase | Goal | DVINE status |
|---|---|---|
| 1 Digital body foundation | controllable head, face and body rig, neutral and idle | **done in dots**: `DotHead`, `DotBody`, 17 bones, idle |
| 2 Digital nervous system | typed commands, motor controller, state machine, IK, cancel, feedback, safety | partly: brain commands → stage; joint limits; **next** typed command objects with status, and IK |
| 3 Facial intelligence | expressions, gaze, blinking, smoothing, lip sync, head gestures, compositing | partly: `FaceRig` springs, blink, smile, jaw from speech; **next** viseme timing |
| 4 Perception | audio, speech recognition, vision, attention, confidence | audio and speech-to-text done; vision not started |
| 5 Cognitive integration | understanding, planning, goals, selection, replanning | offline brain and planner; server LLM in M7 |
| 6 Persistent memory | working, episodic, semantic, procedural, privacy, deletion | journal + pack + sync; **next** episodic records and deletion by voice |
| 7 Locomotion | gait, reach, balance, navigation | walk, run and stunts in place; **next** foot planting, travel paths |
| 8 Learning | experience, evaluation, simulation, rollback | journal sync; skill learning not started |
| 9 Integrated intelligence | everything coordinated | ongoing |

### 24.14 The final blueprint, advanced features, reality check (Parts 23–25)

- **Layers** (a functional diagram, not a list of separate programs): sensory network and cognitive core → perception and attention → goal manager → world model → memory and learning → action selection → behaviour planner → motor command network → body and face controllers → 3D body → feedback → updated world model → learning loop. Safety spans every layer.
- **Later additions:** predictive perception, shared attention, goal-conditioned skills, adaptive animation, body schema, a skill library, counterfactual planning, memory-guided behaviour, multimodal context, self-diagnostics, natural micro-behaviours, and controlled skill improvement.
- **Three levels of achievement:** humanlike *appearance* (graphics) ≠ humanlike *behaviour* (this architecture) ≠ human-level *general intelligence* (research). Measure, test and improve.
- **First three builds:**
  1. A controllable face and body with gaze, IK and state feedback.
  2. A digital nervous system with validated, interruptible commands.
  3. A cognitive and memory core linked to action selection.

## 25. ACMS — the Anatomically Constrained Motion System

**Governing principle:** *the AI may request any movement; the biomechanical controller decides what is anatomically plausible, feasible, safe for the rig and smooth enough to execute.* Without it you get backwards elbows and knees, wrists over-twisted, a jaw through the skull, a head through the torso, fingers bent wrong, sliding feet, detached shoulders and snapping transitions.

### 25.1 Skeleton foundations (Parts 1–2)

- About 206 bones, but the *relationships* matter more than the count.
- **Bones** are rigid segments, **joints** define how neighbours move, and **constraints** define what is permitted. A rig hierarchy alone does not know that a knee bent backwards is wrong.
- **Hierarchy:** root → pelvis → spine (lower, middle) → chest → neck → head (jaw, eyes). The chest also carries the clavicle → shoulder → upper arm → elbow → forearm → wrist → hand → fingers. The pelvis carries hip → thigh → knee → shin → ankle → foot.
- Every joint has a parent, a local position and orientation, axes, a rest pose, limits, state and a control policy. Limits live in the joint's *local* frame; world positions come from FK. Keep world position, root orientation, local joint orientation and world bone orientation separate.
- **DVINE:** `DotBody` has 17 bones (pelvis, spine, chest, neck, head; arm, forearm and hand ×2; thigh, shin and foot ×2) with local Euler angles, FK and linear-blend skinning.

### 25.2 Joint rule engine and joint types (Parts 3–4)

- **Per-joint profile:** id, type, parent/child, rest angle, permitted range, velocity, acceleration and jerk limits, coupling rules, collision rules, solver priority, failure behaviour and calibration. Units must be consistent: degrees in config, radians inside.
- **Hinge-like** (elbow, finger joints, knee as a first approximation): one axis with `θmin ≤ θ ≤ θmax`. The real knee also rotates and slides with flexion.
- **Ball-and-socket** (shoulder, hip): several axes, limited by cones or calibrated ellipses. The shoulder's range depends on the scapula and clavicle.
- **Pivot** (forearm pronation/supination): separate from elbow flexion, spread along a twist chain.
- **Gliding and compound** (wrist, foot, TMJ, vertebrae): small translations as well as rotation.

### 25.3 Region rules (Parts 5–12)

- **Jaw (TMJ):** elevation, depression, protrusion, retrusion and lateral excursion. State is `[θopen, d_forward, d_lateral]`.
  - Limits: opening, backward travel (never through the skull), protrusion and lateral movement.
  - Couple rotation with translation, keep the jaw attached, keep the teeth from passing through each other, and coordinate with lips and cheeks.
  - Follow speech within the movement envelope, and blend competing smile and speech commands by priority.
  - First build: a calibrated hinge with limited translation. Later: a coupled two-joint TMJ.
  - DVINE today: jaw opening clamped 0..1 in `DotHead.pose`, with a lower lip and chin that follow.
- **Neck and head:** a chain of segments. Limit flexion, extension, rotation and tilt. Prevent big sudden changes and keep the head out of the chest. For large turns, use the eyes first, then the neck, then the torso. "Look behind you" turns the body; it never forces the neck. Recover from interrupted poses.
- **Spine and torso:** distribute flexion, extension, lateral bend and axial twist across segments, with per-region limits plus a *global* posture limit. Keep the pelvis–chest relationship plausible, re-balance when leaning, and avoid over-twisting with planted feet. Breathing is a small expressive layer, not permission to break limits.
- **Shoulder:** flexion, extension, abduction, adduction and internal/external rotation, combined. Never through the chest or head, never detached, never an instant flip. Solve reaches across the whole chain: torso → clavicle → upper arm → elbow → forearm → wrist → hand.
- **Elbow and forearm:** a local-frame flexion axis with a preferred bending direction, plus separate pronation/supination that keeps the wrist attached and avoids extreme twist.
- **Wrist, hand and fingers:**
  - The wrist has flexion, extension and radial/ulnar deviation. It is not a free ball joint.
  - Each finger has a bending axis, limits and a collision envelope. The thumb is special.
  - Choose hand shapes (open, relaxed, point, fist, pinch, power or precision grasp) rather than inventing individual finger angles.
  - Prevent fingers passing through each other or the palm.
- **Hip, knee and ankle:**
  - Hip: multiaxial, never through the pelvis.
  - Knee: never backwards, limited side movement and twist, no leg-on-leg collision.
  - Ankle and foot: dorsiflexion and plantarflexion, inversion and eversion, plus foot roll.
  - A planted foot stays put (no skating). Track contact point, stance and swing.
- **Walking and turning:**
  - Gait covers speed, step length and frequency, clearance, pelvis, sway, arm swing, head stabilisation and contact.
  - Turns shift weight and reposition the feet.
  - "Stop" is a transition, not a velocity set to zero.

### 25.4 Four constraint layers, solver, motion rates (Parts 13–15)

1. **Local joint limits.**
2. **Coupled-joint constraints:** shoulder with scapula, jaw rotation with translation, knee with foot, bend spread along the spine.
3. **Whole-body constraints:** hand not through the chest, head not through the shoulder, thigh not through the pelvis, fingers not through the face.
4. **Temporal constraints:** velocity, acceleration and jerk limits, no snapping, graceful interruption.

- **Solver:** `q* = argmin ‖q − q_AI‖²_W + λ·E_smooth`, subject to `q ∈ Q_valid`. A valid pose is not automatically a natural one, so motion quality needs separate evaluation.
- **Rates:** `ω ≈ Δθ/Δt`, `α ≈ Δω/Δt`, `j ≈ Δα/Δt`, using quaternion distance for 3D joints. Limits are set per skill: a quick glance, a slow reach and a step each have their own.
- **Trajectory:** validate → permit → smooth trajectory → execute → read back → correct → confirm.

### 25.5 Intention vs execution, face, muscles, registry (Parts 16–19)

- **Pipeline:** AI intention (`{action: look_at, target, style, priority, interruptible}`) → action interpreter → movement planner → anatomical constraint solver → collision and posture check → trajectory generator → motor controller → skeleton → actual pose → validation and correction. The AI cannot bypass it, and the safety layer can interrupt.
- **Face:** limits on the jaw, eyes (range, neutral, convergence, both eyes together, head follow), lids, brows and lips. No tooth collision, and smooth transitions. Composite speech with expression, gaze and blinking. Validate the final facial pose. A controller cannot repair a poorly built facial mesh.
- **Muscles:** option A is a kinematic character (predictable and cheap; this is DVINE now). Option B is a physics-driven humanoid (forces, contacts, harder). Recommendation: a hybrid kinematic system first, with physics only where it pays off.
- **Biomechanical Constraint Registry:** one source of truth per rig. It holds joint type, local frame, rest pose, constraint profiles, motion limits per skill, collision groups and a failure policy ("reject_and_replan"). It separates the **anatomy profile** (what the body can do) from the **behaviour profile** (how it chooses to move). It is versioned and tested.
- **DVINE today:** `DotBody.LIMITS` is the first registry (local limits per bone), and `MotionTest` proves every built-in pose respects it.

### 25.6 Solver stack, failure recovery, tests, governance (Parts 20–24)

- **Solvers:** local joint → kinematic (IK) → whole-body pose → collision → temporal → balance → feedback. They share joint definitions, coordinate conventions and state.
- **Priority order:** hard limits, then safety and collision, then contacts, then targets, then preferred posture, then style.
- **Impossible requests** ("hand behind your head" when unreachable): parse → target → reachability test → try a valid configuration → collision check → adjust the shoulder, elbow, wrist and torso → nearest alternative → execute. Report `{status: partially_completed, reason, recovery, final_state_valid: true}`. *Never an uncontrolled deformation.*
- **Test suite:**
  - A, joint range: neutral, min, max, near the limits, outside them.
  - B, parent–child integrity.
  - C, collisions: hand–chest, arm–head, knee–knee, jaw–skull, finger–finger.
  - D, temporal continuity across frame rates.
  - E, combined movements: overhead reach, bend and pick up, turning while walking, sit and stand, cross-body reach, look behind, speak while turning, stop near an obstacle.
  - F, failure recovery: invalid targets, missing objects, interruptions, delays, solver failure, lost tracking.
- **Java hinge example:** `HingeConstraint(min, max)` with `clamp()` and `isValid()`, rejecting non-finite values. It only clamps. IK, collision, trajectories, compound joints and balance belong to the wider controller.
- **Governance rules:**
  1. Anatomical authority: no override from ordinary requests.
  2. Connected hierarchy.
  3. Local-frame correctness.
  4. Coupled movement.
  5. Global posture validity.
  6. Collision prevention.
  7. Smooth transitions.
  8. Contact awareness.
  9. Feedback-driven control.
  10. Controlled failure.
  11. Coordinated face.
  12. Learning only inside fixed limits.
  13. Versioned anatomy.
  14. No unvalidated online widening of limits.
  15. Deterministic safety checks, never left to a probabilistic model alone.

### 25.7 Subsystems and build order (Parts 25–26)

- **Subsystems:** skeleton registry, constraint registry, joint solver, whole-body kinematics, IK engine, trajectory planner, collision monitor, balance controller, facial constraint engine, body-state estimator, movement supervisor, and biomechanical test harness. All share one skeleton definition.
- **Build order:**
  1. Hierarchy (**done**).
  2. Constraint database (**first version done**).
  3. Local limits (**done**).
  4. IK and whole-body solving (**next**).
  5. Trajectories and rate limits (pose blending done; jerk limits next).
  6. Collision and foot contact (floor rule done; foot planting and self-collision next).
  7. Facial constraint engine, jaw and eyes (jaw clamp done; TMJ translation and eye limits next).
  8. Body-state feedback.
  9. Structured AI commands.
  10. Controlled movement learning, after the tests pass.

## 26. SVRE + WDTAS — self-vision, self-redraw and whole-body touch

### 26.1 Self-Visual Reconstruction Engine (SVRE)

- **Self-image awareness:** an internal body model of the head and face parts, skin, every body part, skeleton and joints, proportions, current expression and pose, clothing and customisations. It can answer "what does my face look like / where is my left hand / what expression am I showing / what is moving / has my appearance changed / is my pose consistent".
  - DVINE today: `Look` (shape parameters), the sculpt recipe, `DotBody` joint state and `FaceRig` expression.
- **Reconstruction pipeline:** body model → renderer → rendered image → visual analyser → difference detector → reconstruction planner → updated geometry and materials → validation → renderer. The approved original stays as the trusted reference, and one odd frame never overwrites it.
- **Regeneration modules:** face mesh, eyes, mouth, body mesh, skeleton, materials, expression system and render system. Three modes: **normal** (pose and expression only), **repair** (one component), **reconstruction** (a larger rebuild after validation). Never regenerate everything every frame. Preserve the approved face as the master identity.
  - DVINE today: "redraw yourself" scatters and reassembles the dots, and a new look re-sculpts once in the background.
- **Appearance evolution:** identity-preserving improvements; user-approved customisation (hair, shape: `Look.apply`); experimental changes shown as a preview first. Visual generation can never change movement constraints, security or the hierarchy without independent validation.

### 26.2 Whole-Body Digital Touch and Awareness System (WDTAS)

- **Interaction identity per body part:**
  - Head and face: `head.forehead`, `face.cheek.left/right`, `face.nose`, `face.lips`, `face.chin`.
  - Shoulders and arms: `body.shoulder.left/right`, `arm.upper.*`, `arm.forearm.*`.
  - Hands: `hand.palm.*`, `hand.finger.*`.
  - Torso: `body.chest`, `body.back`.
  - Legs: `leg.thigh.*`, `leg.knee.*`, `foot.*`.
  - Every interactive region must be registered.
  - DVINE today: 17 parts plus the face. **Next:** split the face (forehead, cheeks, nose, lips, chin) and hands (palm, fingers).
- **Tap detection:** screen point → camera ray → surface hit → part id, contact point, normal, screen point, timestamp, duration, intensity (only where the device really reports it) and the current pose. Pipeline: tap → ray → intersection → part → classification → body-state update → AI response → animation.
  - A normal screen cannot measure force; never pretend it can.
  - DVINE today: nearest projected dot with a depth preference, which works like a ray hit on a dot cloud.
- **Digital somatosensory map:** per region, `{bodyPartId, interactionEnabled, touchSensitivity, responseProfile, visualReactionEnabled, audioReactionEnabled, hapticOutputEnabled, cooldownMs, minimumEventConfidence}`. Input sensitivity is separate from reaction intensity, and each part has its own behaviour.
- **Body-state awareness:** joint state, pose, part locations, contact, gaze, face, animation, balance, confidence and visual consistency, fed by the skeleton, movement, face, renderer and touch. For example, a tap on a waving hand knows the hand was moving.
- **Anatomically consistent reactions:** detect → interpret → select response → motion plan (through the constraint solver) → animate (blended with the current action) → feedback. A touch never overrides the skeleton directly. Priority rules decide whether to blend, delay or ignore.
  - DVINE today: a tapped hand waves through the normal pose system, and the reply is spoken.
- **Visual body inspection mode:** compare the reference model, skeleton, skinned surface, render and touch map. Detect hand/skeleton misalignment, finger–palm clipping, jaw clipping, misaligned eyes, bad shoulder deformation, wrong touch mapping or render/pose mismatch. Then repair and validate, e.g. jaw clipping → inspect the jaw and mesh → verify limits → correct → re-render → validate. Never widen limits to hide a defect.

### 26.3 Architecture, touch kinds, loop, roadmap

- **Proposed Java packages and their DVINE homes:**
  - brain → `ai.dvine.brain`
  - body, anatomy → `ai.dvine.face.nano` (`DotBody`, `LIMITS`)
  - vision → future `ai.dvine.vision`
  - touch → `StageView.partAt` and `StageListener.onBodyTap`
  - reaction → `MainActivity.onBodyTap` and `Scenes`
  - face → `FaceRig`, `DotHead`
  - rendering → `NanoPainter`
  - validation → the unit tests
  - The renderer stays separate from the touch logic. The `TouchEvent` model is `{bodyPartId, x, y, z, timestampMs, confidence, durationMs}` with validation.
- **Two kinds of touch:** A, the user taps the on-screen character (now). B, a future physical robot with pressure, force, joint, motion, proximity and temperature sensors on the same part registry. A screen avatar does not feel pressure, heat or pain.
- **Body awareness loop:** brain → body-awareness engine (touch, self-vision, body-state estimator) → action supervisor → anatomical constraints → movement/IK → face → renderer → displayed body → visual validation → feedback to the brain. Touch is handled promptly, animation runs at frame rate, and reconstruction is asynchronous. Higher-priority events can cancel safely.
- **Roadmap:**

| Phase | Feature | DVINE status |
|---|---|---|
| 1 | Body-part registry | **done** (17 + face) |
| 2 | 3D touch detection | **done** (nearest dot, depth-preferred) |
| 3 | Spatial contact mapping (contact point in body space) | next |
| 4 | Basic reaction engine | **done** (spoken reply, hand waves back) |
| 5 | Body-state awareness to the brain | next (send part, pose and mode with each event) |
| 6 | Reaction blending with current motion | partly (pose blend) |
| 7 | Self-visual inspection | not started |
| 8 | Controlled reconstruction | partly (re-sculpt from `Look`, redraw) |
| 9 | Adaptive interaction | not started |
| 10 | Advanced embodiment (sensors) | out of scope for the phone |

- **Final rule:** *the brain decides what an interaction means; body awareness determines what happened; the anatomical controller decides what movement is valid; the visual engine maintains and renders the body; validation confirms the result.* A repair is only "done" once it has been validated against the mesh, skeleton, reference and touch map.

## 27. The on-device engine, and what the server does instead (v1.6, built)

**Rule:** the brain-and-body control engine lives on the phone. Everything DVINE needs to listen, think simply, move, react and stay safe works offline and in real time. The server takes only **secondary** jobs: ones that need the whole history, heavy compute or the internet. Their results flow back as hints the phone may ignore. If the server is gone, DVINE loses nothing it needs in the moment.

| Function (ANBA network) | Where | Code |
|---|---|---|
| Hearing, speech-to-text, speaking | phone | `ListenBridge`, `TalkBridge` |
| Routing and simple reasoning (rules + knowledge pack) | phone | `OfflineBrain` |
| Executive: validated commands, action selection, one owner per channel, priorities, queues, stale-command expiry, stop | phone | `engine/Supervisor`, `Command`, `Skills` |
| Motor control, joint limits, floor contact, face rig | phone | `DotBody`, `DotHead`, `FaceRig`, `StageView` (60 Hz) |
| Behaviour loop | phone, 10 Hz | `Engine.tick` (timed commands, mood drift, expression) |
| Appraisal and affect (regulated expression bias) | phone | `engine/Affect` → `FaceRig.setMood` |
| Working memory (7 items, priorities, expiry) | phone | `engine/WorkingMemory` |
| Embodied state model and self-monitoring ("what are you doing?") | phone | `Engine.state`, `Engine.selfReport` |
| Touch on the body and reactions | phone | `StageView.partAt` → `Engine.onTouch` |
| Short episodic buffer (≤300 kept, uploaded ones dropped after 3 days, never the user's words) | phone | `engine/Episodes` |
| Learning journal (taught facts, corrections, unanswered) | phone, then server | `LearningJournal` → S-L |
| **Long-term episodic archive** | **server** | `POST /api/v1/episodes/sync` (owner-scoped, idempotent, secrets rejected) |
| **Memory consolidation into favourites** (poses, scenes, objects, touches, persona; 90 days) | **server** | `GET /api/v1/episodes/profile` → `Engine.applyProfile` ("what do I like?") |
| **Knowledge pack builds and updates** | **server** | S-K |
| **Review of shared learning** | **server** | S-L review queue |
| **Open-ended reasoning with a large model** | **server** (M7) | later |
| Skill-usage analytics and controlled skill learning | server (later) | from the episode archive |

- **Command path:** brain answer → `Engine.perform` → typed `Command` (action, value, priority, deadline) → `Supervisor` (validate against the skill registry → expire if stale → preempt, queue or reject) → `BodyPort` (the stage) → result reported back (COMPLETED, PREEMPTED, REJECTED with a reason, EXPIRED, CANCELLED). Failures the user asked for become FAILURE episodes and show up in the self-report.
- **Priorities:** stop (100) > user request (60) > touch reaction (30) > idle (10). A scene finishes unless the user asks for something else. A touch never interrupts what the user asked for. Switching view, redrawing and turning round are momentary overlays: they don't take over the body.
- **Privacy:** episodes carry generated summaries and tags ("pose:pushups", "touch:left hand"), never what the user said. They upload only while sharing is on ("stop sharing" turns it off). The server keeps them per owner.
- **Voice:** "stop" / "that's enough" (cancel everything and stand), "what are you doing" / "status report" (self-report from real state), "what do I like" (favourites consolidated by the server, or an honest "not yet").

## 28. The anatomical 3D wireframe human (v1.7, built)

Obi and Ivie are now real 3D meshes: closed, skinned, rigged triangle surfaces drawn as a glowing wireframe with vertex dots on black. Obi is cyan, Ivie amber-gold. It replaces the nano-dot swarm as the default look ("dot mode" still brings the swarm back; "wireframe" returns).

**Architecture**

| Layer | Package | What it does |
|---|---|---|
| Mesh core | `human/mesh` | `SurfaceNets` (signed-distance field → surface), `MeshClean` (weld), `Decimator` (quadric simplification, importance-weighted: detail on face and hands, no flips, link condition), `MeshValidator` (closed 2-manifold, winding, positive volume, skin, regions), `TriMesh` (edges sorted by importance for LOD) |
| Anatomy | `human/build/Anatomy` | sculpted head (cranium, brow ridge, eye sockets, cheekbones, nose bridge/tip/wings, lips, jaw, chin, ears, neck; male squared jaw and larynx, female finer jaw and hair shell), body (rib cage, trapezius, pecs or breasts, collarbones, abdomen, pelvis, glutes, deltoids, biceps/triceps, forearms, quadriceps/hamstrings, kneecaps, calves, ankles, heels, toes), hands with five separate two-segment fingers and a thumb |
| Assets | `human/build/AssetBuilder`, `AssetTool`; `human/HumanAsset` | builds each variant, adds eyeballs with iris rings, two rows of teeth, feature lines (eyelids, brows, nostrils, lip line), 46 swaying hair strands for Ivie, 15 blend shapes, 18 landmarks; binary `.dvhm` with a CRC32. Shipped: `assets/human/obi.dvhm` (8,080 triangles), `ivie.dvhm` (8,380 triangles) |
| Skeleton and rig | `human/rig` | 37 bones (17 body + 2 segments × 5 fingers × 2 hands), A-pose bind; the existing pose library (`DotBody.POSES`) drives it; joint limits and a 600°/s rate limit; finger shapes per pose (fist, point, open); analytic two-bone IK for reaching and foot planting; gaze shared by eyes (±12°), neck and head; capsule collision keeps hands out of the body; `validate()` checks bone lengths, limits, finite values and collisions |
| Skinning | `human/HumanInstance` | two-bone linear blend skinning; weights diffused over the surface (8 passes body, 4 hands) so joints bend over a band; blend shapes, eye turn and hair sway applied before skinning; feet settle on the floor (never sink) |
| Face | `human/face` | `FaceShapes` (jawOpen, smile/frown L/R, brows, blink, wide eyes, lip round/wide/press), `FaceDrive` (expressions smile, frown, surprise, attention, sad, angry; spring-driven blinks and glances; speech wins on the mouth), `Visemes` (per-word mouth shapes from TTS word timing). Identity is fixed: shapes only move features, never the proportions |
| Touch | `human/touch`; `HumanInstance.pick` | screen ray → front-facing triangle → one of 45 named body parts, contact point in world and bone-local space, timestamp, duration; `TouchClassifier` turns presses into TAP, REPEAT (with count) or HOLD and drops duplicates. No pressure is claimed (a phone screen has none) |
| Self-inspection | `human/inspect/Inspector` | NORMAL (check), LOCAL_REPAIR (blend shapes off, stand, re-check), RECONSTRUCTION (rebuild from the recipe off the UI thread; accepted only if valid and the face keeps its landmark ratios within 4%, unless the user asked for a new look). The approved asset is never overwritten |
| Renderer | `render/` | OpenGL ES 3 (`HumanView`, a `GLSurfaceView` under the transparent stage): dark fill with polygon offset (hidden lines removed), additive glow + core line passes (lines are screen-space quads fetched from float textures), rim light, vertex dots, dotted floor grid, LOD by edge importance in body view, both personas in one view for the demo. Context loss rebuilds every GL object; detach frees them; `FrameStats` keeps average and 95th-percentile frame time |

**Rules the AI cannot bypass:** every movement goes `Engine` → `Supervisor` (validated command) → `StageView` → `HumanStage` → rig. The rig clamps joints to anatomical limits, rate-limits them, resolves collisions and keeps feet on the floor whatever the pose asks for.

**Voice:** "show both of you" / "demo" (both personas side by side), "smile", "look surprised", "frown"…, "self check" or "status report" (the self-report includes the inspector), "wireframe" / "dot mode".

**Build and regenerate**

```
gradle testDebugUnitTest assembleDebug     # tests + APK
gradle :app:generateHumanAssets            # rebuild obi.dvhm / ivie.dvhm (~6 s each, deterministic)
```

Inspection sheets (software render of the shipped assets): `docs/brand/human_{obi,ivie}_{views,face,poses}.png` (front, 3/4, side, rear; face expressions; poses).

**Tests (§10 of the spec):** `HumanTest` (assets valid and reject corruption; the head is genuinely 3D; landmarks aligned from every view; the mouth opens without flipping triangles; the head joins the neck; every pose of both variants deforms without tears, everyday poses stretch skin < 2.6×; touch finds nose, chin, knee and chest and misses empty space; touch classification; reconstruction keeps identity and refuses unapproved changes; IK reach, planted feet, hands out of the body; expressions and visemes); `WireRendererTest` (fake GL: no leaks after dispose, recovery after context loss, no GL errors over 120 frames; camera rays pass through projected points).

**Known limits**
- Not yet run on a phone: frame rate, the GL shaders on real drivers and touch feel are unverified.
- Linear skinning: at the deepest hip bends (sit, squat, kneel) a few crotch edges stretch up to ~5.7×; corrective shapes would fix it.
- Visemes come from word text and timing, not from the audio, so lip sync is approximate.
- The mouth has teeth and a groove but no tongue or inner mouth surface.
- Touch knows where and how long, not how hard.
