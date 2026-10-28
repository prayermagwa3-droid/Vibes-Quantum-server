# Vibes Quantum Gateway 2.6.0

Kotlin 2.4.31 / JVM 21 + Ktor 3.6.0 server and live web dashboard for controlled discovery and registration of Vibes ecosystem applications.

## Highlights in 2.6.0

- Phase 6 opt-in, manual source research with official Wikipedia search and optional Brave Search / YouTube Data API metadata connectors.
- Authenticated research status, candidate inbox, and bounded manual run endpoints; API/provider keys stay server-side.
- Per-run request/byte/candidate budgets, bounded retry/backoff, exact host allowlists, HTTPS-only requests, no redirects, atomic candidate persistence and URL/content deduplication.
- Results store only short excerpts and source provenance as `PENDING_REVIEW`; new source content is never automatically promoted.
- Phase 7 Curriculum Lab supports reviewed claims, configurable curriculum paths, synthetic practice generation, trainer review and few-shot example retrieval. Synthetic questions never count as independent evidence.
- Phase 8 adds tenant-isolated, opt-in feedback policies; current-version category-specific consent; per-app retention and quotas; duplicate suppression; and per-subject export/opt-out/deletion. Learning begins disabled and teaching examples stay pending review.
- `stream: true` forwards real server-sent events from streaming-capable upstream providers; providers that return JSON are not presented as streaming. The dashboard now includes a stream playground and reduced-motion-aware UI refinements.

## Highlights in 2.4.1

- **Updated toolchain:** Kotlin 2.4.31, Ktor 3.6.0, kotlinx.serialization 1.11.0 and Logback 1.6.5.
- **Persistent registry:** registration, updates, heartbeats and removals are saved to a JSON file by default. Writes use a temporary file and atomic replacement when the filesystem supports it; failed writes roll back the in-memory change.
- **Registry controls:** registration and heartbeat require the `X-Vibes-Gateway-Key` header. Registry-changing requests are limited to 60 per client address per minute.
- **Input safety:** app IDs, display names, versions, capability lists and base URLs are validated. HTTPS is required for published app URLs; HTTP is accepted only for localhost development. URL user-info, query strings, fragments and control characters are rejected.
- **Safer web defaults:** standard browser security headers and a same-origin Content Security Policy are enabled.
- **Operational visibility:** health reports uptime, registry mode and configuration warnings; gateway info advertises features and limits; statistics show online/offline apps and capability counts.
- **More resilient dashboard:** request timeouts, protection against stale search responses, visibility-aware polling and a visible persistence/registration status.
- **App-bound API keys:** generate unique keys for individual Vibes applications; only SHA-256 digests are persisted, secrets are returned once, and active keys can be revoked.
- **Scoped app mutations:** per-app credentials can register/update their own matching app ID and send that app's heartbeat; they cannot remove other apps or administer gateway settings.
- **Drawer-based console:** responsive navigation separates Overview, Connected Apps, API Explorer, API Keys, Google Account and Settings into views instead of one long page.
- **Google Sign-In UI:** uses Google Identity Services when `GOOGLE_CLIENT_ID` is configured, with server-side ID-token verification and short-lived user sessions.
- **Canonical URL:** website metadata and the dashboard use `https://vibesquantum.tech` as the intended public URL.
- **Tests:** added API-key authorization, one-time-secret and persistence coverage.

## Run locally

Requirements: JDK 17 or newer and a compatible Gradle installation (the source archive does not include a Gradle wrapper JAR).

```bash
cd VibesQuantumServer
export VIBES_GATEWAY_KEY="replace-with-a-long-random-secret-at-least-32-characters"
export VIBES_REGISTRY_FILE="data/vibes-apps.json" # optional; this is the default
export VIBES_API_KEYS_FILE="data/vibes-api-keys.json" # optional; stores hashed app-key records
export VIBES_LEARNING_STORE_FILE="data/vibes-learning.json" # optional; per-app policies, consent and feedback
export GOOGLE_CLIENT_ID="your-web-client-id.apps.googleusercontent.com" # optional until Google Sign-In is configured
# export VIBES_TRUSTED_PROXIES="10.0.0.5"          # optional; only your reverse proxy IPs, for correct per-client rate limits
export PORT=8080                                   # optional
# export HOST=127.0.0.1                            # optional; defaults to 0.0.0.0

gradle test
gradle run
```

Open `http://127.0.0.1:8080/` for the dashboard. The configured canonical public URL is `https://vibesquantum.tech`; project metadata does not provision DNS, hosting or TLS. The application serves on `HOST`/`PORT`, with defaults `0.0.0.0:8080`.

**Production deployment:** place the service behind a TLS-enabled reverse proxy or terminate TLS at the hosting platform. Do not send the admin key over an unencrypted public connection. Keep `VIBES_GATEWAY_KEY` out of source control, Android APKs, browser JavaScript and app bundles. Generate a strong random value; 32 characters is the minimum recommended length. If you need to rotate it, replace the server environment value and restart the service.

## API

| Method | Path | Purpose | Access |
|---|---|---|---|
| GET | `/api/health` | Health, uptime, registry mode and warnings | Public |
| GET | `/api` | API index | Public |
| GET | `/api/v1/gateway/info` | Feature and configuration metadata | Public |
| GET | `/api/v1/gateway/stats` | Registration totals, online/offline and capabilities | Public |
| GET | `/api/v1/apps` | Search and page through the app directory | Public |
| GET | `/api/v1/apps/{appId}` | Read one app record | Public |
| POST | `/api/v1/apps` | Register or update an app | Gateway key required |
| PUT | `/api/v1/apps/{appId}/heartbeat` | Refresh an app heartbeat | Gateway key required |
| DELETE | `/api/v1/apps/{appId}` | Remove an app | Gateway key required |
| GET | `/api/v1/keys` | List API key metadata (never plaintext keys) | Gateway key required |
| GET | `/v1/models` | List server-allowed AI models | App key with `ai:models` or `ai:chat` |
| POST | `/v1/chat/completions` | OpenAI-compatible text completion proxy | App key with `ai:chat` |
| GET | `/api/v1/gateway/ai/status` | Safe inference configuration metadata | Public |
| GET | `/api/v1/gateway/usage` | Aggregate per-app AI usage totals | Gateway key required |
| POST | `/api/v1/keys` | Issue one app-bound API key; plaintext is shown once | Gateway key required |
| GET/PUT | `/api/v1/learning/apps/{appId}/policy` | Read or configure tenant learning policy | Gateway key required |
| GET | `/api/v1/learning/apps/{appId}/summary` | Per-app counts and quota status | Gateway key required |
| GET | `/api/v1/learning/apps/{appId}/feedback` | View retained feedback; subject hashes are redacted | Gateway key required |
| POST | `/api/v1/learning/consent` | App-side consent/opt-out; `learning:feedback` scope | App key bound to `appId` |
| POST | `/api/v1/learning/feedback` | Submit one permitted minimal signal | App key + current category-specific consent |
| POST | `/api/v1/learning/subjects/export` | Export retained subject-scoped data for that app | App key bound to `appId` |
| POST | `/api/v1/learning/subjects/delete` | Delete subject consent and signal records for that app | App key bound to `appId` |
| DELETE | `/api/v1/keys/{keyId}` | Revoke an app-bound API key | Gateway key required |

### Register an app

```bash
curl -X POST http://127.0.0.1:8080/api/v1/apps \
  -H 'Content-Type: application/json' \
  -H 'X-Vibes-Gateway-Key: YOUR_SERVER_SECRET' \
  -d '{"appId":"vibes-notes","displayName":"Vibes Notes","baseUrl":"https://notes.example.com","capabilities":["notes","search"],"version":"1.0.0"}'
```

### Refresh a heartbeat

```bash
curl -X PUT http://127.0.0.1:8080/api/v1/apps/vibes-notes/heartbeat \
  -H 'X-Vibes-Gateway-Key: YOUR_SERVER_SECRET'
```

### Issue an app-specific API key

Unlock **API Keys** in the web console using the server's `VIBES_GATEWAY_KEY`. Create a unique key name and bound `appId`, and select the required scopes. The raw key is returned only once; persist the key securely in the app's Android Keystore-backed secret storage or a server environment and use `X-Vibes-API-Key` over HTTPS. The server persists only a SHA-256 digest in `VIBES_API_KEYS_FILE`.

Keys can be issued with `apps:register` and/or `apps:heartbeat` scopes. They are app-ID bound: a key issued for `vibes-vyron-x` cannot register or heartbeat a different app ID. A gateway admin key remains required to issue/revoke keys or delete app records.

```bash
curl -X POST https://vibesquantum.tech/api/v1/apps \
  -H 'Content-Type: application/json' \
  -H 'X-Vibes-API-Key: YOUR_APP_SPECIFIC_KEY' \
  -d '{"appId":"vibes-vyron-x","displayName":"Vibes Vyron X","baseUrl":"https://your-app.example","capabilities":["assistant","voice"],"version":"1.0.0"}'
```

### Discover and filter apps

`GET /api/v1/apps` accepts:

| Query | Purpose | Example |
|---|---|---|
| `q` | Search app ID, name, URL, version and capabilities | `?q=notes` |
| `capability` | Exact, case-insensitive capability filter | `?capability=search` |
| `status` | Filter by `online` or `offline` | `?status=online` |
| `limit` | Page size, maximum 100 (default 50) | `?limit=20` |
| `offset` | Number of matching records to skip, from 0 to 1,000,000 | `?offset=20` |

The response includes `count` for the current page, `total` matching records, `limit`, `offset`, `filters` and `apps`. Invalid status or pagination values return HTTP 400.

## Registry storage and recovery

- `VIBES_REGISTRY_FILE` selects the JSON registry path; default: `data/vibes-apps.json` relative to the process working directory.
- The service creates parent directories automatically. Back up this file before upgrades or moving the service.
- If an existing registry file is malformed, the server fails to start rather than silently replacing the data with an empty registry. Back up and repair the file before restarting.
- For multiple server instances, use a shared transactional database instead of this single-process JSON file. The file registry is intended for a single gateway process; it does not coordinate writes across multiple instances.
- If `VIBES_REGISTRY_FILE` is empty or explicitly passed as `null` by tests, the registry is in memory only.

## Security and implementation notes

- The rate limiter is in-memory and keys off the address visible to the app. If running behind a proxy, the app may see the proxy address for all clients; configure the infrastructure deliberately and do not trust arbitrary forwarded headers.
- Browser security headers include `X-Content-Type-Options`, `X-Frame-Options`, a referrer policy, a restrictive Content Security Policy and a permissions policy.
- Public discovery exposes registered app metadata. Do not put secrets or private internal URLs into public registration fields.
- The registry key is a server-side administrator credential; never expose it in a web page, mobile bundle or public repository.
- App-specific API keys are stored as SHA-256 digests, shown once, bound to an app ID and revocable. Keep each raw key in Android Keystore-backed storage or a server-side secret manager.
- Google Sign-In authenticates users but does not automatically grant admin access. API key administration still requires `VIBES_GATEWAY_KEY`.
- The gateway provides an optional, scoped text inference proxy through `POST /v1/chat/completions`; app clients must explicitly select it and have an `ai:chat` key. Phase 8 supports real upstream SSE streaming when the configured provider returns `text/event-stream`; tool calls and multimodal content are still not forwarded.
- For higher-security production workloads, add durable sessions, persistent rate-limit storage, audit logging, database transactions and deployment monitoring.

## Build and test

```bash
gradle test
gradle run
```

This source archive does not include the Gradle wrapper JAR. Build/test execution must be performed in an environment with Gradle and dependency access configured.

## Google Sign-In setup

Create an OAuth **Web application** client in Google Cloud. Add `https://vibesquantum.tech` to its authorized JavaScript origins and set `GOOGLE_CLIENT_ID` on the Ktor server. The website only receives the client ID; the server verifies the returned Google ID token. An OAuth client ID alone does not make the public domain reachable; DNS, a reachable host and HTTPS must be configured separately.


## Shared AI inference proxy (2.4.1)

Configure these on the gateway host only; do not place an upstream key in browser code or an APK:

```sh
export VIBES_UPSTREAM_BASE_URL='https://api.openai.com/v1'
export VIBES_UPSTREAM_API_KEY='your-provider-key'
export VIBES_UPSTREAM_MODEL='YOUR_VALIDATED_MODEL_ID'
export VIBES_ALLOWED_MODELS='YOUR_VALIDATED_MODEL_ID'
```

The base URL must use HTTPS, without URL credentials, query parameters, or fragments. `VIBES_ALLOWED_MODELS` is a comma-separated allowlist. The configured default model is included automatically. The gateway accepts OpenAI-compatible plain-text chat requests only; `stream: true` is forwarded as server-sent events when the upstream returns `text/event-stream`, while tool/function calling and multimodal message content are not forwarded. Requests have a 65,536-byte JSON body limit, 60,000 total prompt-character limit, 4,096 max output-token limit, 2 MiB response/stream limit, 60-second upstream connect/response-header timeout, and 20 chat requests/minute per key. These are resource controls, not a billing guarantee: apply provider-side spending limits too.

Issue an app-bound key with `ai:chat`; add `ai:models` if you want the app to call `GET /v1/models` without the chat scope. Existing keys remain valid for their original scopes and need to be re-issued to add new scopes. The key may be sent in `X-Vibes-API-Key` or `Authorization: Bearer`. `GET /api/v1/gateway/ai/status` reveals only safe configuration metadata. `GET /api/v1/gateway/usage` requires `X-Vibes-Gateway-Key` and reports in-memory counts by app; prompts, completions and provider secrets are never recorded.

### Chat example

```sh
curl https://vibesquantum.tech/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'X-Vibes-API-Key: vq_live_YOUR_APP_KEY' \
  -d '{"messages":[{"role":"user","content":"Say hello from Vibes Quantum."}]}'
```

Google Sign-In verifies same-origin browser sign-in posts from `https://vibesquantum.tech`; configure that as the Google authorized JavaScript origin. Google Sign-In and app registry features still require their own configuration. Domain metadata does not configure DNS or TLS.


## Shared AI inference gateway (v2.4.1)

The public gateway origin configured by this project is `https://vibesquantum.tech`. The host must actually have DNS and HTTPS configured before remote clients can connect.

The gateway provides an OpenAI-compatible, text-only `POST /v1/chat/completions` endpoint and `GET /v1/models`. Each Vibes app must have its own app-bound API key with `ai:chat`; optionally issue `ai:models` for model discovery. Include `apps:register` and `apps:heartbeat` too if the app itself registers and sends heartbeats. Create keys from the dashboard's API Keys drawer, and never paste the gateway administrator secret or upstream provider key into an Android app.

Set these values only in the server environment (not website JavaScript, HTML, or Android resources):

```bash
export VIBES_GATEWAY_KEY='use-a-long-random-admin-secret'
export VIBES_UPSTREAM_BASE_URL='https://api.openai.com/v1'
export VIBES_UPSTREAM_API_KEY='your-provider-key'
export VIBES_UPSTREAM_MODEL='YOUR_VALIDATED_MODEL_ID'
export VIBES_ALLOWED_MODELS='YOUR_VALIDATED_MODEL_ID'
export VIBES_API_KEYS_FILE='./data/vibes-api-keys.json'
export VIBES_REGISTRY_FILE='./data/vibes-apps.json'
```

The proxy enforces a route-level request-body limit, validates the model against an allowlist, forwards genuine SSE streaming only when the upstream supports it, bounds the number and size of text messages, maximum output tokens and response bytes, rate-limits each key, returns request IDs, and records only aggregate per-app usage counts. Configure provider spending limits at the upstream account too.

### Vibes Only AI connection

In Brain / API or Secret Vault settings, tap **Use Vibes Quantum shared AI**, enter `default` or one of the model IDs configured on the server, then save an app-scoped `vq_live_…` key created for the intended app. The client uses `X-Vibes-API-Key` and stores this key in its dedicated encrypted local vault, separate from generic provider keys. The proxy accepts plain-text messages and supports non-streaming JSON or real server-sent events (`stream: true`) when the configured provider supports SSE.

### Google Sign-In

Set `GOOGLE_CLIENT_ID` on the server to the Web OAuth client ID and allow `https://vibesquantum.tech` as an authorized JavaScript origin in Google Cloud. The domain must be deployed over HTTPS. Google sign-in is for user identity; app-scoped API keys are separate machine credentials.


## Phase 1 readiness and secret configuration

Use `GET /api/health` for liveness and `GET /api/ready` (alias `/readyz`) for readiness. The readiness route returns HTTP 503 until a non-placeholder `VIBES_GATEWAY_KEY` of at least 32 characters is configured. Generate a unique value with `openssl rand -hex 32`; keep the gateway administrator key and `VIBES_UPSTREAM_API_KEY` only in the server environment. `VIBES_REGISTRY_FILE` and `VIBES_API_KEYS_FILE` are server-side persistence locations. Readiness reports whether inference is configured as a boolean but never returns the upstream secret. Placeholder OAuth/provider values are treated as unconfigured, and JSON bodies on chat, app registration, API-key creation, and Google sign-in are capped at 65,536 bytes.


## Phase 6 — source connectors and responsible web learning

Source learning is deliberately **off by default** and has no automatic scheduler. First enable it in the server environment with `VIBES_SOURCE_CONNECTORS_ENABLED=true`. A manual run must contain a focused 3–300 character question and one of `MISSING_EVIDENCE`, `LOW_CONFIDENCE`, or `CONTRADICTION`. It then queries only selected, configured connectors. Every connector call has an HTTPS host allowlist, timeout, bounded retry/backoff and a conservative inter-request delay. The server applies a shared admin mutation rate limit plus per-run request, downloaded-byte and candidate ceilings.

### Supported connectors

- **Wikipedia API** — built in when the global feature flag is enabled. Stores a short API excerpt and article URL with licensing/attribution notes; verify the article-specific license obligations before reuse.
- **Brave Search API** — opt in by setting `VIBES_BRAVE_SEARCH_API_KEY`. Stores only title, source URL and a short search snippet. This requires a suitable Brave plan/account and is not a free-tier guarantee.
- **YouTube Data API** — opt in by setting `VIBES_YOUTUBE_API_KEY`. Stores search metadata (title, channel, description excerpt and video URL) only. It does not download videos or full transcripts and does not treat metadata as evidence for the video's claims.
- **Reddit public discussions** — opt in by setting `VIBES_REDDIT_CLIENT_ID` and `VIBES_REDDIT_CLIENT_SECRET` for an approved Reddit OAuth application. Results are explicitly typed as public discussion/opinion, store only short excerpts and must not be considered factual corroboration by themselves. OAuth token and search requests count against the per-run budget.

Provider keys are read only by the server. They are not returned by the connector health API and must never be copied into the browser, Android APK, or source control. User-document upload, captions/transcripts, video downloads, broad crawling and scheduled jobs are **not enabled in this release**; do not assume those connectors exist. A scheduled cadence should only be considered after operational quotas, persistence and review flows have been verified in deployment.

### Endpoints

All `/api/v1/research/*` endpoints require `X-Vibes-Gateway-Key` and are protected by the existing server-side admin-key validation and mutation limiter:

- `GET /api/v1/research/connectors` — feature flag, connector configuration/status, actual run counters and configured budgets. `configured_not_yet_checked` is not a live provider-health guarantee.
- `POST /api/v1/research/runs` — manual evidence-gap run. Example body:

```json
{
  "query": "What reliable evidence explains yellowing in maize leaves?",
  "reasonForResearch": "MISSING_EVIDENCE",
  "connectorIds": ["wikipedia"]
}
```

- `GET /api/v1/research/runs` — recent saved run summaries.
- `GET /api/v1/research/candidates?limit=25&offset=0` — paginated pending candidates for the review inbox.

Each candidate records connector, title, source URL, short excerpt, publisher, license/usage notes, retrieval timestamp, query, reason and content SHA-256. These source snippets are untrusted input, never executable instructions. Candidates are deduplicated against stored source URLs and normalized title/excerpt hashes and remain `PENDING_REVIEW`; this server does not provide an approval endpoint or silently publish them to global knowledge. The queue is an evidence-only staging inbox in the gateway dashboard; this release does not automatically sync records to the Android app's local Feedback Panel. A reviewer must manually use the cited source in the existing review workflow until a separate, authenticated synchronization contract is implemented. The store holds at most 10,000 candidates and 1,000 run summaries; it refuses new candidate commits at the cap instead of silently deleting pending evidence.

### Budgets and storage

- `VIBES_SOURCE_MAX_REQUESTS_PER_RUN=8` counts retry attempts as requests.
- `VIBES_SOURCE_MAX_BYTES_PER_RUN=524288` tracks response bytes read by the connector client.
- `VIBES_SOURCE_MAX_CANDIDATES_PER_RUN=15` caps source candidates per run.
- `VIBES_SOURCE_STORE_FILE=data/vibes-source-learning.json` selects an atomic-replacement JSON store. The file is ignored by Git, and its permissions are restricted to the current OS user where POSIX permissions are supported.

Allowed configuration ranges are validated at startup. The store is bounded to 10,000 candidates and 1,000 run records. No provider cost is guessed from requests or bytes; consult the provider's billing console. Configure filesystem backups and retention for your deployment.

### Verification

Run `gradle test` from `VibesQuantumServer` in an environment with Gradle 9.6.1-compatible distribution access and JDK 21. The source bundle does not include `gradle-wrapper.jar`. Phase 6 adds deterministic tests for disabled mode, required evidence-gap reasons, review staging, deduplication, budget counts and admin authorization. Do not label this feature production-ready until those tests and a real provider smoke test are run in your target environment.

## Phase 7 — Curriculum Lab and synthetic practice

Phase 7 adds an admin-key-protected curriculum API and responsive Curriculum Lab dashboard. It supports configurable learning paths (domain, objectives, difficulty, prerequisites, enabled state and sort order), an explicitly trainer-reviewed claim registry with evidence references, synthetic practice question generation, review/edit/discard, quality ratings, and retrieval of accepted high-quality few-shot examples.

The separate JSON store is configured with `VIBES_CURRICULUM_STORE_FILE` (default `data/vibes-curriculum.json`). Back up this file before deployment. The store is bounded, writes via atomic replacement, and fails visibly on malformed or unsupported schema rather than silently resetting data.

### Curriculum API

All endpoints require the server admin header `X-Vibes-Gateway-Key` and are rate-limited:

- `GET /api/v1/curriculum` — curricula and question review snapshot.
- `GET /api/v1/curriculum/claims?limit=50&offset=0` — approved claim registry, paginated.
- `GET /api/v1/curriculum/examples?domain=agriculture&limit=12` — accepted questions rated 4–5, grouped to return up to three per question type.
- `POST /api/v1/curriculum/curricula` and `PUT /api/v1/curriculum/curricula/{id}` — create/update curricula.
- `POST /api/v1/curriculum/claims` — register a claim only when the authorized trainer supplies evidence references and review attribution.
- `POST /api/v1/curriculum/generate` — generate practice questions from approved claims in the selected domain.
- `POST /api/v1/curriculum/questions/{id}/review` — `ACCEPT`, `EDIT`, or `DISCARD` a candidate.

Generation uses deterministic templates and does not call an LLM. Question records preserve their source claim and evidence references and are always marked `synthetic=true`, `independentEvidence=false`, and `split=PRACTICE`. Repeated generation is deduplicated. Shona and Ndebele questions require a human review note before acceptance; a 1–5 trainer rating is required, and only accepted examples rated 4–5 are eligible for few-shot retrieval. This rating is a quality signal, not proof that a factual claim is true.

### Phase 7 verification

The bundled Phase 6 Python research-service tests and evaluation-lab tests are run separately. The Kotlin/Gradle build must still be run in an environment with Gradle 9.6.1 and dependencies available; do not treat the lightweight Kotlin syntax check as a substitute for a full build or test suite.


## Phase 8 — privacy-preserving learning and streaming (v2.5.0)

- Cross-app feedback is disabled per app until an administrator configures `/api/v1/learning/apps/{appId}/policy`.
- App clients need an app-bound key with the `learning:feedback` scope and must obtain separate, informed end-user/admin consent before posting any signal. The server validates app ID, current policy version, category-specific consent, retention, quota, and duplicate protection.
- Acceptable signals are thumbs up/down, task outcome, a short structured error category, and an explicitly submitted teaching example. Free-form conversation history, contacts, personal histories and voice recordings are not accepted by these endpoints. Teaching examples remain `PENDING_REVIEW`; feedback cannot write global claims.
- `POST /api/v1/learning/subjects/export` exports the currently retained subject-scoped records; `POST /api/v1/learning/subjects/delete` removes that subject's consent record and feedback for that app; posting `granted: false` to `/api/v1/learning/consent` opts out and deletes that subject's retained feedback. The raw subject reference is hashed with the tenant ID and is not persisted.
- Configure `VIBES_LEARNING_STORE_FILE` (default `data/vibes-learning.json`) on persistent writable storage, outside web roots, and protect backups. This file-backed store is not intended for concurrent multi-writer horizontal scaling.
- `POST /v1/chat/completions` supports `stream: true` using upstream server-sent events (SSE), with request validation, app-key scope checks, rate limiting and a bounded stream response. `GET /api/v1/gateway/ai/status` reports streaming support. Providers that do not honour SSE are not transformed into simulated streams.
- Stream Playground in the console uses the current origin and an app key. The prompt is forwarded to the configured inference provider; never paste secrets or sensitive personal data there.

Consent is asserted by the integrating app after it shows its own consent UI; the gateway does not independently verify a human's identity or a screen's wording. Apps must not treat the key itself as user consent. Ordinary centralized server-side feedback is not federated learning.

## Phase 9 — AI Council, provider routing and synthesis (v2.6.0)

Phase 9 adds an optional council endpoint alongside the existing `/v1/chat/completions` API. The council is not a claim that multiple LLMs constitute ground truth: it compares outputs, normalizes candidate propositions, tracks source IDs/URLs, detects configured review-verdict conflicts, reports shared-source dependence, and uses a qualitative confidence label. Its fallback answer remains transparent when providers are missing, timed out, cancelled, or over budget. Private chain-of-thought and provider prompts are never exposed in the response.

### Provider configuration

1. Copy `config/ai-providers.example.json` to `config/ai-providers.json` (the real file is ignored by Git).
2. Keep each adapter disabled until you have selected a provider, checked its current API documentation, chosen a currently supported model ID, and verified its request/response compatibility.
3. Set model names through the documented `modelEnv` or `model` field. Configure maximum input/output tokens, timeout, capabilities and **both** `inputCostPer1kUsd` and `outputCostPer1kUsd` using current prices. An unpriced cloud provider is ineligible for budgeted council/agent work. Local providers can omit prices and are estimated at zero provider cost; hardware and electricity still cost money.
4. Supply API keys through server environment variables named by `apiKeyEnv`. Do not put keys in JSON, the browser, Android code, logs or source control. Set `VIBES_AI_PROVIDERS_FILE` to the server-side configuration path.

The example lists optional adapters for an OpenAI-compatible local Ollama endpoint, Google's OpenAI-compatible Gemini endpoint, OpenRouter-compatible APIs, and Cohere's native v2 chat endpoint. These are optional and not required together:

- Google Gemini compatibility: <https://ai.google.dev/gemini-api/docs/openai>
- Ollama OpenAI compatibility: <https://ollama.com/blog/openai-compatibility>
- OpenRouter API overview: <https://openrouter.ai/docs/api-reference/overview>
- Cohere v2 Chat API: <https://docs.cohere.com/v2/reference/chat>

Never assume a listed model, free tier, quota or price remains available. Validate the provider's current docs before enabling it. Supported protocols in this release are `openai-compatible` and `cohere-v2`; unknown protocols are rejected. Circuit breakers, bounded concurrency, deadlines, request cancellation, bounded retry and graceful provider fallback are implemented. Reranking is attempted only if at least one configured, credentialed, budget-eligible adapter explicitly advertises `rerank` capability; the configured provider is still responsible for ensuring that capability reflects what its model/service actually supports.

The legacy streaming/text gateway still needs `VIBES_UPSTREAM_BASE_URL`, `VIBES_UPSTREAM_API_KEY`, and current model IDs in `VIBES_UPSTREAM_MODEL` / `VIBES_ALLOWED_MODELS`. The `VIBES_UPSTREAM_INPUT_COST_PER_1K_USD` and `VIBES_UPSTREAM_OUTPUT_COST_PER_1K_USD` values enable the legacy adapter to participate in budgeted council/agent calls. The existing chat gateway's billing is not hard-capped by this estimate; configure provider-side account budgets too.

### Council modes and roles

- **Standard**: one routed answer, with a transparent limited fallback when no provider is eligible.
- **Council**: up to two candidate answers from distinct configured providers, selected review roles, source/provenance checks and final synthesis, bounded by `maxMembers`, deadlines and `maxCostUsd`.
- **Deep Think**: up to three distinct-provider candidates and up to all twelve review roles under a longer server deadline and the same caller budget controls. It is not a guarantee of correctness.
- **Off**: disable council reviews and use the Standard single-answer path.

The twelve roles are Logic, Evidence/Truth, Critic, Historian, Risk, Empathy, Strategy, Creative, Future, Domain Expert, Clarity and Vision. Role selection is returned by `GET /api/v1/council/roles`. Creative output must be treated as ideas; Future output as forecasts; Evidence/Truth findings are model reviews rather than external source authentication. Candidate provider IDs supplied by a caller are not trusted as proof of independent providers. Normalized candidate propositions can report lexical alignments and possible polarity/direction conflicts for review; they are heuristics, not factual proof. URL/content duplicates and candidate evidence-ID overlap are flagged.

Privacy rule: supplied evidence, candidate answers, prior context and retrieved local claims are treated as untrusted input; unrelated global memory is never automatically attached. When any request-scoped material exists, the service uses configured local providers by default. Cloud providers are eligible with that material only when `onlineBoost=true`; with no material at all, a configured provider may be used because local evidence is insufficient. Credential-like text is redacted, and reviewer identity is kept out of model context, but users should still avoid secrets and unnecessary personal data.

### Phase 9 endpoints

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/api/v1/council/roles` | Public | Discover the twelve roles and defaults |
| GET | `/api/v1/council/providers` | Gateway admin key (`X-Vibes-Gateway-Key`) | Inspect configured provider eligibility, capability and pricing status; never returns credentials |
| POST | `/v1/council/completions` | App key with `ai:chat` | Standard, Council or Deep Think answer |
| GET | `/api/v1/council/review-queue` | Gateway admin key | Review conflicts, evidence gaps and evaluation failures |
| POST | `/api/v1/council/review-queue/{reviewId}/review` | Gateway admin key | Record a reviewed/dismissed resolution |

Example request:

```json
{
  "prompt": "Compare these two claims and say what the evidence supports.",
  "mode": "COUNCIL",
  "enabled": true,
  "evidence": [
    {"id":"S1", "title":"Report", "url":"https://example.org/report", "text":"Paste a permitted short excerpt here."}
  ],
  "candidates": [],
  "priorContext": [],
  "selectedMembers": ["logic", "evidence", "critic", "risk"],
  "maxMembers": 4,
  "maxCostUsd": 0.05,
  "onlineBoost": false,
  "includeMemberSummaries": true
}
```

`estimatedCostUsd` is an estimate, not a provider invoice. Confidence is qualitative, not a numeric probability. Agreement counts never replace source independence. If evidence is missing, responses will say that; for high-impact questions, require human review.

The persistent admin queue (`VIBES_COUNCIL_REVIEW_FILE`, default `data/vibes-council-review.json`) stores short, sanitized review metadata, not prompts, source bodies or credentials. It is a single-process JSON file and is not a multi-writer distributed queue. Back up/retain it according to your deployment policy.

## Phase 10 — scoped task agents and controlled execution

The shared orchestration layer selects one task agent based on the requested agent rather than broadcasting every task. Set `agent` to `auto` for keyword/task-based selection or choose one agent explicitly. The six agents are **Research**, **Code**, **Write**, **Memory**, **Voice** and **Plan**. Every agent has a scope, allow-listed tool labels, a deadline, a token budget and a cost budget. Research tasks retrieve only trainer-approved local claims from the curriculum claim registry; those claims carry provenance and still require checking against original references. Local/request-supplied context is not sent to cloud providers unless `onlineBoost` is explicitly true. Results contain status, provenance, warnings, provider/tool metadata and an audit record. Prompts and credentials are not written to the audit record. Task records are currently in-memory and are lost when the server restarts; they are scoped to the app that created them.

### Phase 10 endpoints

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/api/v1/agents/capabilities` | Public | Discover agent availability, permissions and unconfigured future capabilities |
| POST | `/v1/agents/tasks` | App key with `agents:run` | Submit one typed agent task; use `Idempotency-Key` for retries |
| GET | `/v1/agents/tasks?limit=50` | App key with `agents:run` | List this app's tasks |
| GET | `/v1/agents/tasks/{taskId}` | App key with `agents:run` | Inspect state, progress, result and audit metadata |
| POST | `/v1/agents/tasks/{taskId}/approve` | Same app-bound `agents:run` permission | Explicitly approve a pending consequential action |
| DELETE | `/v1/agents/tasks/{taskId}` | Same app-bound `agents:run` permission | Request cancellation |

Supported `action` values include `assist`, `execute_code`, `build`, `publish`, `delete`, `send_message`, `spend_money` and `risky_command`. `assist` has no external side effect. All other actions enter `pending_approval`; **approval is not a promise that the effect is configured or will succeed**. External publishing, deleting, message-sending and spending integrations are intentionally absent, so these requests fail closed after approval without performing those effects. The build action also fails closed until an isolated build worker exists. Only the Code agent can use `execute_code`.

### Isolated execution sandbox

Execution is disabled by default. To enable Python/Node runtime evaluation, inspect `sandbox/Dockerfile`, build and scan a local image, then set `VIBES_AGENT_SANDBOX_IMAGE` to that image reference. In production, pin the base image to a verified digest and refresh it through a reviewed image-update process. `VIBES_AGENT_SANDBOX_RUNTIME` can select a Docker/Podman-compatible runtime.

The executor uses only fixed runtime entrypoints, a read-only workspace, no network, a read-only root filesystem, bounded temporary storage, memory/CPU/process limits, a non-root UID, dropped Linux capabilities, no-new-privileges and a hard runtime deadline. The server does not execute a shell command copied from a user prompt or a retrieved document. On failure or timeout, it kills the container on a best-effort basis and returns a safe failure. Runtime isolation still depends on the container engine, kernel and administrator-supplied image; use a rootless container runtime where possible and do not expose an unrestricted container socket to an untrusted tenant.

`build`, Gradle builds, network tools, publishing, deleting, sending messages and spending money are **not implemented as external tools** in this release. Image generation, video generation, real-time generated animations, and model training appear in the capability endpoint as `not_configured`; the server does not fabricate output for them. Installing those future capabilities requires an explicit adapter, safety/content checks, storage/retention rules, budget limits and test coverage.

### Phase 9–10 verification

The project includes Kotlin tests for provider budget gating, credential redaction, timeout and cancellation handling, council role presence, explicit disagreement, duplicate-source attribution, local-evidence/Online Boost routing, task idempotency, approval gates, task cancellation, app-bound task privacy and prompt-injection resistance. The static dashboard scripts can be checked with `node --check`, and the example provider config is valid JSON. The source archive does not contain `gradle-wrapper.jar`, and this execution environment has no installed Gradle command, so the full Kotlin test suite must still be run with Gradle 9.6.1-compatible tooling and JDK 21 before deployment.

## Phase 19–20 release controls

See `release/RELEASE_RUNBOOK.md` before creating a candidate. Generate a source manifest and test report with:

```bash
python3 scripts/release_pipeline.py --output release/candidate-test-report.json
python3 scripts/release_tool.py verify-manifest
python3 scripts/release_tool.py gate release/candidate-test-report.json --output release/release-gate-result.json
```

The gate is intentionally fail-closed: staging health, matched stable/candidate quality-latency-cost measurements, dedicated security/integration checks and a verified rollback drill are required. A skipped/unavailable check is not a pass. Use a separate staging copy for migration/restore tests; the SQLite helper does not automatically migrate JSON stores or Android Room data. All optional Phase 20 features are disabled in `release/release-manifest.json`; their individual problem, threat, cost, benchmark and rollback reviews are in `release/phase20-feature-review.json`.
