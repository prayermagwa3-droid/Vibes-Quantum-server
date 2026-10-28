# Changelog

## 2.6.0 — Phase 9 AI Council and Phase 10 task agents

- Added an optional multi-provider AI Council with Standard/Council/Deep Think/off modes, twelve selectable review roles, qualitative confidence, normalized claims, candidate disagreement detection, source de-duplication and source-provenance notes.
- Added configurable OpenAI-compatible and Cohere v2 adapters, local-first routing, cloud cost-price requirements, request budgets, deadlines, cancellation, bounded retries, concurrency and per-provider circuit breakers.
- Added approved-claim retrieval from the existing curriculum registry; pending source candidates and synthetic exercises are not used as evidence. Request-scoped evidence/context remains local-only unless Online Boost is explicitly enabled.
- Added admin review queue endpoints for disagreement, claim review and evaluation failures, with sanitized metadata and no prompt/source-body logging.
- Added six scoped agents (Research, Code, Write, Memory, Voice, Plan), typed task results, app-scoped task access, idempotency, progress/checkpoints, cancellation, audit metadata and approval gates for consequential tasks.
- Added a fail-closed, optional container sandbox for Python/Node with no network, limited resources, a read-only workspace/root filesystem and non-root execution. Build and external side-effect adapters remain unavailable until configured.
- Added dashboard panels for Council roles, evidence and provenance, provider status, agent tasks/approval/cancellation and future multimodal capability status; all provider examples are disabled by default and use no assumed live model IDs/prices.
- Added Kotlin tests for provider budgets/redaction/failure handling, candidate disagreement/source duplication, approved knowledge, task idempotency/approval/cancellation and app isolation. Full Gradle execution still requires the project-matched distribution and wrapper JAR.

## 2.5.0 — Phase 8: privacy-preserving learning and streaming

- Added app-scoped, admin-configurable learning policies with disabled-by-default collection, consent version checks, category allowlists, retention, daily quotas, idempotency and duplicate suppression.
- Added minimal feedback endpoints for ratings, task outcomes, structured error categories and explicit teaching examples; examples remain pending review.
- Added app-scoped consent, opt-out, subject export and deletion workflows. Raw subject references are not stored; tenant and subject identifiers are checked at request boundaries.
- Added an admin privacy-learning dashboard and scoped key permission for `learning:feedback`.
- Enabled OpenAI-compatible `stream: true` chat completions using bounded SSE forwarding and added a streaming playground.
- Improved reduced-motion-aware UI motion, live progress/status, keyboard focus and mobile layout.

## 2.4.3 — Phase 6 source connectors and Phase 7 curriculum lab (unreleased)

- Added manual-only, opt-in source research endpoints using a common connector interface. Wikipedia uses its public search API; Brave Search, YouTube Data API metadata and Reddit OAuth adapters require server-side credentials.
- Added HTTPS exact-host allowlists, redirects refused, bounded response streams, timeout, retry/backoff, conservative request spacing, per-run request/byte/candidate budgets and global admin request rate limiting.
- Added a bounded atomic JSON source-candidate store, URL/hash deduplication, retrieval timestamp and license/provenance fields. Every result remains `PENDING_REVIEW`; no automatic publication.
- Added the admin Source Learning dashboard for connector configuration/health, manual gap-focused runs, pending candidates and recent run budget usage. Provider keys are never returned to the browser.
- Added deterministic service and route tests. Build/test execution remains blocked in this source bundle unless a compatible Gradle installation is available because `gradle-wrapper.jar` is absent.
- Not included: user document upload, video/caption download, automatic scheduling, or connector approval actions. These remain out of scope until their auth/rights/review controls are implemented and verified.
- Added the Phase 7 Curriculum Lab with configurable curricula, prerequisite-cycle prevention, sort order and difficulty levels.
- Added an admin-only reviewed-claim registry requiring evidence references and language/domain metadata, plus deterministic synthetic question generation linked to approved claim IDs.
- Generated practice items are marked synthetic and not independent evidence, use a separate PRACTICE split, and are deduplicated across repeated generation runs.
- Added candidate accept/edit/discard, human review for Shona/Ndebele output, trainer quality ratings, and few-shot retrieval limited to accepted 4–5-rated examples.
- Added curriculum service tests for evidence boundaries, language review, deduplication, few-shot eligibility, and prerequisite cycle prevention. Full Gradle execution remains blocked in the current environment because Gradle 9.6.1 and the wrapper JAR are unavailable.

- Rate limits (registry mutations, Google sign-in) are keyed on the real client when the server sits behind a reverse proxy. `X-Forwarded-For` is honoured only from peers listed in the new `VIBES_TRUSTED_PROXIES` setting. Previously every client behind one proxy shared one bucket.
- Chat completions count each request once. Failures while sending the response no longer record a second failure, and the send is outside the upstream error handling.
- The API key list reads the store once, so the returned count matches the returned keys.
- Fixed an assertion in the AI status test that could never match pretty-printed JSON.
- Added tests for trusted-proxy address resolution.

## 2.4.1 — Shared AI inference and app integration

- Added an OpenAI-compatible text-only `POST /v1/chat/completions` endpoint and app-key-protected `GET /v1/models`. Provider credentials remain on the server.
- Added server-side model allowlisting, text/content validation, a route-scoped 65,536-byte body cap (including chunked bodies), a 4,096-token output cap, 2 MiB upstream response cap, 60-second timeout, and 20 requests/minute per app key.
- Added request IDs and in-memory, aggregate per-app usage totals without storing prompt/completion text.
- Hardened Google Sign-In by requiring same-origin POSTs from `https://vibesquantum.tech`, in addition to signature, audience, issuer and expiry validation.
- Connected Vibes Only AI's actual provider path to Quantum's `X-Vibes-API-Key` scheme and added a one-tap provider preset; the dedicated app key is stored separately in the encrypted Android vault. Quantum mode disables streaming and image payloads for compatibility with the text-only proxy.
- Updated Vibes Vyron X and Voice Studio gateway integration guidance, app-scoped key permission requirements and deployment instructions.
- Added a server regression test for oversized chat request bodies.

## 2.3.0 — Drawer console and app API credentials

- Replaced the long landing page with a responsive drawer-based gateway console and separate Overview, Connected Apps, API Explorer, API Keys, Google Account, and Settings views.
- Added Google Identity Services frontend integration, configuration messaging and session controls.
- Added per-app API key issuance, app-ID binding, narrow registration/heartbeat scopes, SHA-256 digest persistence and admin revocation. Plaintext app keys are returned only once.
- Added production domain metadata for `https://vibesquantum.tech`. DNS, hosting and TLS must be configured independently.
- Updated server documentation, environment examples, CSP allowlists and ignore rules.

## 2.2.0

- Persistent app registry, gateway health and statistics, safer request validation and server-side Google ID-token verification.
