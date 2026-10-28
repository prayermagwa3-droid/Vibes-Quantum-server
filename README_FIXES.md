# Vibes Quantum Server Kotlin compile fixes

This bundle patches the compile errors in the supplied Gradle output. It contains only changed Kotlin source files plus a safe apply script.

## Apply on Termux

1. Extract this ZIP somewhere convenient.
2. Run:

   ```sh
   bash apply_fixes.sh "$HOME/Vibes Projects/VibesQuantumServer"
   ```

   The script backs up every replaced file into a timestamped `.kotlin-fix-backup-*` directory before copying the patched file.
3. Compile first:

   ```sh
   cd "$HOME/Vibes Projects/VibesQuantumServer"
   gradle clean compileKotlin
   ```
4. If compilation passes, start the server:

   ```sh
   gradle run
   ```

## Fixes included

- Explicit `Throwable` type for exception-cause traversal.
- Use the pipeline context explicitly for request correlation logging.
- Resolve personal-memory auth token inside each request handler's `ApplicationCall`.
- Correct JSON element deserialization import.
- Remove invalid top-level `bodyLimit` imports and use the `RequestBodyLimitConfig` DSL lambda.
- Make route-exposed `MutationRateLimiter` and `StoredConsent` types public.
- Fix duplicate `now` declarations in learning feedback storage.
- Add missing return statements in quality/memory lifecycle functions.
- Fix route DSL usage and `call` imports in the language catalog.

The bundle was checked with Python tests and ZIP integrity validation, but a full Kotlin/Gradle compile was not run in this environment. Run `gradle clean compileKotlin` on the actual Termux project and share the next error output if any remain.
