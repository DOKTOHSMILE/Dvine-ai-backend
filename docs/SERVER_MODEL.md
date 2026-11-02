# DVINE's own online model

A model too big for a phone runs on the server. Phones use it like any API model: signed in with their session, in the OpenAI chat-completions form, with replies streamed.

The server relays each request to [llama.cpp's `llama-server`](https://github.com/ggml-org/llama.cpp/tree/master/tools/server), which runs the model. Until `DVINE_LLM_URL` is set, `/api/v1/llm/*` answers `503 not_configured`, so nothing breaks before you deploy it.

## 1. Pick a model for the machine

All of these are 4-bit GGUF files (`Q4_K_M`). Memory means RAM, or VRAM when a GPU runs it.

| Model | File | Memory with a 16k context | Notes |
|---|---|---|---|
| Qwen3 14B | `unsloth/Qwen3-14B-GGUF` › `Qwen3-14B-Q4_K_M.gguf` (about 9 GB) | about 12–14 GB | The default name `qwen3-14b`. A big step above anything a phone runs. |
| Qwen3 32B | `unsloth/Qwen3-32B-GGUF` › `Qwen3-32B-Q4_K_M.gguf` (about 20 GB) | about 24–28 GB | Strongest of the family. Use a GPU for reasonable speed. |
| Gemma 3 12B | `unsloth/gemma-3-12b-it-GGUF` › `gemma-3-12b-it-Q4_K_M.gguf` (about 7 GB) | about 10–12 GB | Good writing in many languages. |

On a CPU-only machine, expect a few tokens a second for 14B. A GPU makes it many times faster.

## 2. Run llama-server next to the backend

With Docker (CPU image; use `server-cuda` and `--gpus all` on an NVIDIA machine):

```
mkdir -p /srv/models && cd /srv/models
curl -L -o Qwen3-14B-Q4_K_M.gguf https://huggingface.co/unsloth/Qwen3-14B-GGUF/resolve/main/Qwen3-14B-Q4_K_M.gguf

docker run -d --name dvine-llm --restart unless-stopped -p 127.0.0.1:8081:8080 \
  -v /srv/models:/models ghcr.io/ggml-org/llama.cpp:server \
  -m /models/Qwen3-14B-Q4_K_M.gguf -c 16384 --jinja --host 0.0.0.0 --port 8080
```

Keep it on `127.0.0.1`. Only the backend talks to it, and phones never reach it directly.

## 3. Point the backend at it

| Variable | Default | What it does |
|---|---|---|
| `DVINE_LLM_URL` | (empty: off) | The llama-server address, for example `http://127.0.0.1:8081`. |
| `DVINE_LLM_MODEL` | `qwen3-14b` | The model's name as phones see it. Every request is sent with this name. |
| `DVINE_LLM_KEY` | (empty) | Sent as a bearer token if llama-server was started with `--api-key`. |
| `DVINE_LLM_MAX_TOKENS` | `8192` | The longest reply. A phone asking for more gets this. |
| `DVINE_LLM_CONTEXT` | `16384` | The context window reported to phones. Keep it equal to `-c` above. |
| `DVINE_LLM_REQUESTS_PER_MINUTE` | `30` | Per signed-in user. Over it, the answer is `429`. |
| `DVINE_LLM_TIMEOUT` | `600` | Seconds to wait for a reply. |

Check it:

```
curl -s -H "Authorization: Bearer $TOKEN" https://your-server/api/v1/llm/models
```

## 4. On the phone

Settings › AI providers › **DVINE server**. It uses the server address and sign-in the app already has, with no key to type.
