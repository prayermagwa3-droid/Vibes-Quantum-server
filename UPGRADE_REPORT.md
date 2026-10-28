# Vibes Quantum Ecosystem 2.4.1 — Upgrade Report

## Implemented

### Vibes Quantum gateway

- Added the OpenAI-compatible `POST /v1/chat/completions` and key-scoped `GET /v1/models` routes using `https://vibesquantum.tech/v1` as the configured public API base.
- Keeps the upstream provider key in server environment variables; only one-time, app-bound `vq_live_…` keys are stored by digest and metadata.
- Enforces a Ktor route-scoped request body limit of 65,536 bytes, a 60,000-character combined text limit, a 32,000-character per-message limit, a 4,096-token output limit, a 2 MiB upstream response ceiling and a 60-second upstream timeout.
- Restricts models to an environment-defined allowlist, rejects streaming and non-text message content, caps each app-key to 20 chat requests per minute, returns request IDs, and records aggregate usage counts in memory without persisting prompts or answers.
- Revalidates the upstream URL as HTTPS before use and avoids redirects.
- Requires Google sign-in credential POSTs to originate from `https://vibesquantum.tech`, while continuing to verify Google ID-token signature, audience, issuer, expiry and verified email. Google identity remains distinct from app-key permissions.
- Preserves the responsive drawer-based website and updates cache-buster versions, API docs, scoped-key workflow and domain metadata.

### Vibes Only AI / Voice Studio

- Connected the actual OpenAI-compatible provider path to the Vibes Quantum endpoint. It sends its app key only in `X-Vibes-API-Key`, uses a dedicated encrypted secret slot separate from regular providers and the Vibes Engine key, and selects that secret based on the exact gateway base URL.
- Added the one-tap **Use Vibes Quantum shared AI** preset to provider settings. It selects `https://vibesquantum.tech/v1`, defaults model selection to `default`, disables streaming, validates the `vq_live_…` key shape and displays separate vault status.
- Prevents image payloads from being sent to the current text-only gateway and limits Quantum-mode maximum output tokens to 4,096 to match gateway policy.

### Vibes Vyron X

- Retains the app-specific encrypted-key flow, connection test and opt-in Quantum inference mode. Non-tool responses can use the shared gateway when that setting is enabled; the app does not silently route the user's prompts to another provider if the opted-in gateway fails.
- Documented that self-registration and heartbeat need `apps:register` and `apps:heartbeat` in addition to `ai:chat`; `ai:models` is optional for model discovery.

## Configure before use

1. Set `VIBES_GATEWAY_KEY`, `VIBES_UPSTREAM_BASE_URL`, `VIBES_UPSTREAM_API_KEY`, `VIBES_UPSTREAM_MODEL`, `VIBES_ALLOWED_MODELS`, `VIBES_API_KEYS_FILE` and `VIBES_REGISTRY_FILE` in the server environment. Do not put the upstream provider key in the Android apps or browser code.
2. Set `GOOGLE_CLIENT_ID` to the web OAuth client ID and authorize `https://vibesquantum.tech` in Google Cloud. The deployed server origin must match the Google sign-in origin check.
3. Ensure DNS, hosting/reverse proxy and HTTPS are configured so the domain actually reaches Ktor. Source-code URL references alone do not deploy or connect a domain.
4. Create one key for each app in the dashboard. Select `ai:chat`; include `apps:register` / `apps:heartbeat` if the app is expected to register or refresh its directory record; add `ai:models` only for model-list access. Keep the gateway admin key out of Android apps.
5. The proxy is currently **text-only and non-streaming**. It does not provide tool/function calling, multimodal image inputs, or a web socket stream. Provider-side spending limits should also be set because rate limits are not a billing guarantee.

## Verification performed

- `node --check` passed for the website JavaScript.
- The source regex used to extract a newly issued key was smoke-tested with Kotlin 1.9.0.
- The existing Python evaluation-lab tests passed: 5 tests.
- The existing Python research-pipeline tests passed: 6 tests.
- The existing Python training-service tests passed: 7 tests.
- Added a Kotlin regression test for an oversized `/v1/chat/completions` body, and corrected the JSON key-extraction regexes in the gateway tests.

## Not verified in this environment

No `gradle` executable or Gradle wrapper is present in the supplied bundle, and the Android archives are source/app-only bundles rather than full standalone Android roots. Therefore I could not run the Kotlin server test task or build the Android APKs here. Run the server's Gradle test/build task and compile each app inside its matching full original Android project before deployment. Google credentials, upstream provider credentials and live domain/DNS/TLS were not available for an end-to-end sign-in or inference test.

## Research references

- OWASP REST Security Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html
- OWASP API4:2023 Unrestricted Resource Consumption: https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/
- Android Keystore system: https://developer.android.com/privacy-and-security/keystore
- Google backend ID-token verification: https://developers.google.com/identity/gsi/web/guides/verify-google-id-token
- Ktor 3.6.0 RequestBodyLimit: https://api.ktor.io/ktor-server-body-limit/io.ktor.server.plugins.bodylimit/-request-body-limit.html
- OpenAI Chat Completions: https://platform.openai.com/docs/api-reference/chat/create
