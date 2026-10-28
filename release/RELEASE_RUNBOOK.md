# Vibes Quantum Phase 19–20 Release Runbook

## Release status

This is a **release candidate workflow**, not a production-ready declaration. Promotion is denied unless every critical gate is `passed`, baseline measurements are populated, staging smoke is green, and a rollback drill succeeds. `skipped`, `unavailable`, missing, or `null` measurements are not passes.

## Versioned artifacts

`release/release-manifest.json` versions gateway/engine API contracts, error envelope, database and knowledge schemas, prompt catalog/safety policy, provider config schema, and evaluation dataset. Generate `release/artifact-manifest.json` at the final source commit to record a Git commit SHA (or a canonical source-tree SHA-256 when the checkout has no Git metadata), timestamp, and per-file SHA-256 hashes. Do not edit source after generating it; regenerate on any change. Store the ZIP, manifest, candidate report and test logs together.

## Secrets and environment

Copy `.env.example` to a deployment-managed secret store or local untracked `.env` only after reviewing required variables. Never commit real API keys, Google credentials, gateway admin keys, signing keys, or database credentials. Use separate staging and production credentials with minimum scopes. Rotate any credential accidentally committed. The release scripts intentionally avoid printing environment values.

## Local preflight

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r ../Vibes_Quantum_Voice_Studio/research-service/requirements.txt  # if this file exists in your checkout
python -m pip install pytest
python scripts/release_pipeline.py --output release/candidate-test-report.json
python scripts/release_tool.py manifest --output release/artifact-manifest.json
python scripts/release_tool.py verify-manifest
```

If a dependency file is absent, install only the dependencies listed in the individual service README files. The current pipeline deliberately records unavailable checks rather than inferring success.

## Staging deployment sequence

1. Build from a clean checkout at a tagged commit. Record commit SHA and dependency lockfiles.
2. Provision an isolated staging deployment with separate credentials and copied/synthetic data only.
3. Take snapshots of each persistent store. For SQLite, use `scripts/release_tool.py migrate <db> --backup-dir <backup-dir> --direction up` only after reviewing migrations. This tool does not automatically migrate JSON or Android Room stores.
4. Apply migrations to a staging copy; validate row counts, schema versions, referential integrity and read/write smoke tests.
5. Run Python tests and the Kotlin/JVM suite where a working Gradle wrapper/toolchain is available. Run Android build/instrumentation tests on supported devices separately.
6. Set `VIBES_STAGING_HEALTH_URL` to the actual staging health URL and run the pipeline again. Copy `release/baseline-measurements.example.json` to `release/baseline-measurements.json` and fill measured candidate-vs-stable values using the same dataset, traffic mix and hardware. Never leave null values for promotion.
7. Perform a rollback drill on staging: deploy candidate, inject a safe test failure, switch traffic/config back to last-known-good, restore compatible data backup if required, and verify health, login, chat, data integrity and background-job state.
8. Human release owner reviews all results, privacy/security checks and the release manifest. Begin at 1% only after explicit approval; progress through 5%, 10%, 25%, 50%, 100%, with observation windows and automatic/manual rollback triggers.

## Rollback procedure

1. Stop further rollout and set affected feature/provider flags off.
2. Route traffic to the last-known-good application artifact and provider configuration snapshot.
3. If the candidate changed data schema, use a tested down migration only when it is safe; otherwise restore a verified pre-migration backup and replay compatible writes according to the deployment's documented recovery-point objective. Never overwrite production data blindly.
4. Verify backup checksums before restore. `verify-snapshot` must pass before `restore`; restore writes files atomically one by one and verifies hashes.
5. Verify health endpoint, auth, app registration, representative chat, key scopes, store integrity, queue state and error/latency metrics. Keep the candidate artifact and logs for investigation.
6. Record incident, data-loss window, migration version and exact rollback result. Do not mark rollback successful until service and data checks pass.

## Migration caveats

SQL migrations are ordered and tracked in `schema_migrations`; each migration requires a paired `.down.sql` for rollback. The tool backs up an existing SQLite database before applying or rolling back. A rollback applies one migration per invocation. Review migration SQL for destructive changes, test both directions on a copy, and confirm backup restore before staging. JSON stores, Android Room, and remote DBs require dedicated adapters/migration tests before use.

## Phase 20 optional research decisions

See `phase20-feature-review.json`. All eight optional features are disabled. No embeddings, model fine-tuning, offline packs, canary records, adaptive routing, multimodal inputs, or autonomous research execution are enabled by this release. Each entry records the problem, threat model, cost uncertainty, benchmark and rollback. Knowledge graph visualization is also deferred until a measurable operator need exists. A flag being false is the safe baseline; it is not a claim the feature is implemented.

## Troubleshooting

- **Promotion denied:** inspect candidate report; skipped/unavailable critical gates are blockers by design.
- **Manifest mismatch:** regenerate after source changes; verify all artifacts come from the same commit.
- **Migration fails:** keep the backup, inspect schema ledger, restore on a disposable copy first, and do not retry against production until root cause is understood.
- **Rollback does not restore service:** keep traffic on known-good build, disable candidate features, verify secrets/config compatibility and persistent-store versions, then escalate with redacted logs.
- **Secret scan warning:** inspect the source line without copying secret values into tickets; rotate credentials if exposure is real.
- **Baseline unavailable:** do not substitute zero. Measure quality, p95 latency and cost per 1,000 requests under matched conditions.
