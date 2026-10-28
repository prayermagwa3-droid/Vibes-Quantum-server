# Fixes applied (static review pass)

## Kotlin source (imports only, no logic changes)
- `QualityMetadataRoutes.kt`: added `io.ktor.server.application.call`
- `ai/CouncilRoutes.kt`: added `io.ktor.server.application.call` and `io.ktor.server.application.install`
- `learning/LearningRoutes.kt`: added `io.ktor.server.application.call` and `io.ktor.server.application.install`
- `agents/WorkspaceEngineeringRoutes.kt`: added `io.ktor.server.application.install`
- `curriculum/CurriculumRoutes.kt`: added `io.ktor.server.application.install`
- `sources/SourceConnectorRoutes.kt`: added `io.ktor.server.application.install`

## Python contract tests (stale paths and assertions, no product code changed)
- `scripts/test_memory_benchmark_contract.py`: project root was resolved to a
  non-existent `Vibes_Quantum_Server` folder. Now resolves to this project.
  Android client check is skipped when the sibling `Vyron_User_App` is absent.
- `scripts/test_workspace_brain_contract.py`:
  - The admin-token gate (`VIBES_WORKSPACE_ADMIN_TOKEN_PROVIDED`) lives in
    `scripts/workspace_control.py`, as the runbook describes, not in the Kotlin
    control service. The assertion now checks that file.
  - Baseline snapshot assertion now matches the implementation, which snapshots
    the isolated baseline copy that was actually tested.
  - Android engine checks are skipped when the sibling `Vibes_Quantum_Engine` is absent.
  - `unittest.main()` guard moved to the end of the file.

## Not verified
- No Kotlin compiler, Gradle, or dependency cache was available in the build
  environment, so Kotlin was not compiled. Checks were static: project import
  resolution, constructor and route signature matching, private-type exposure,
  and Ktor DSL imports.
- Dependency versions in `build.gradle.kts` (Kotlin 2.4.21, Ktor 3.5.2,
  logback 1.6.5) could not be checked against Maven Central offline.
- Run `gradle clean compileKotlin` on a machine with network access to confirm.

## Compile errors from Gradle output (round 2)
- `Routing.kt` (intercept): `call.request.httpMethod` is unresolved on `PipelineRequest`; now uses `call.request.local.method.value`.
- `ai/LanguageCatalog.kt`: package-qualified `io.ktor.server.routing.get(...)` calls replaced with `get(...)`, which resolves through the existing import and gives the handlers their receiver (this also clears the cascading `call`/`respond` errors).
