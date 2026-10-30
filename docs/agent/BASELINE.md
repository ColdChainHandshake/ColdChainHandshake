# Baseline (2026-10-30)

## Repository

| Item | Value |
|---|---|
| Local `HEAD` at start | `f858a87` |
| `origin/main` after `git fetch` | `f858a87` |
| Ahead / behind | 0 / 0 |
| Working branch | `rebuild/trust-core`, from `main` |
| Uncommitted at start | Tool setup only (`.claude/`, `.mcp.json`, kit zip), kept on `local-snapshot-20261030` |
| Stashes | none |
| Tracked files | 244 |
| Main Kotlin | 63 files, 8,378 lines |
| Unit-test Kotlin | 26 files, 6,109 lines (before the audit test was added) |
| Instrumented tests | none |
| CI | none |

## Tests

Command (the init script is kept outside the repo, see `DECISIONS.md`):

```bash
./gradlew testDebugUnitTest --no-daemon --console=plain --continue \
  -Dorg.gradle.java.home='D:/cch-tools/jdk-17.0.20.1+1' \
  --init-script baseline-guard.init.gradle
```

Last lines: `BUILD SUCCESSFUL in 3m 50s`, `25 actionable tasks: 25 executed`.

| Measure | Count |
|---|---|
| Tests run | 164 |
| Failed / errors / skipped | 0 / 0 / 0 |
| Existing tests | 159 |
| `AuditFindingsTest` (added) | 5, all passing, which means the bugs are present |
| `LiveSupabaseIntegrationTest` | 8 tests, deliberately not run (no result file exists for it) |

Counts come from the XML in `app/build/test-results/testDebugUnitTest/`, because the init script sets
`ignoreFailures`. 159 + 8 = the 167 `@Test` methods in the review.

The build could not run as committed. `org.gradle.java.home` is a dead path on this machine, so the command
overrides it with `-D`.

## Findings 1 to 16 against the local code

`HEAD` is the reviewed commit, and each finding was also confirmed by reading the code. All 16 stand.

| # | Status | Evidence |
|---|---|---|
| 1 | Confirmed | `supabase_shipment_custody.sql:20`, `supabase_shipment_locations.sql:22` `GRANT ALL` to anon; `SupabaseRemoteDataSource.kt:37-82` uses upsert everywhere |
| 2 | Confirmed | `HashChainService.kt:20` `GENESIS`, `:33` pipe-joined canonical form; proved by `finding1` and `finding2` in the audit test |
| 3 | Confirmed | `MIN_SAFE_TEMPERATURE` is defined in `SafetyModels.kt:16` and never read in `BreachDetector.kt`; proved by `finding3` |
| 4 | Confirmed | `HandoverScreen.kt:200-201` default-checked booleans; `SupabaseRemoteDataSource.transferCustodyWithConfirmation` upserts with last write winning |
| 5 | Confirmed | `TemperatureSimulator.kt`; `DispatchScreen.kt:157,172` pairing is `startsWith("LOG-")` |
| 6 | Confirmed | `BreachDetector.kt:53-70` only counts time after a hot reading; proved by `finding4` |
| 7 | Confirmed | `SafetyEngine.kt:78-85` creates an alert on every call with a breach, `AlertEscalationEngine.kt:25` random ID; escalation only from a button at `AlertsScreen.kt:263`; no notification code exists; proved by `finding5` |
| 8 | Confirmed | `HandoverScreen.kt:882` `enabled = !isProcessing && preliminaryPass`; `ARRIVED`, `INSPECTED`, `REPLACEMENT_REQUESTED` are never set |
| 9 | Confirmed | `TransitScreen.kt:331-336` sync in `LaunchedEffect`; no service or WorkManager in the code or manifest |
| 10 | Confirmed | `DatabaseProvider.kt:32`, `ColdChainDatabase.kt:31` |
| 11 | Confirmed | `TransitScreen.kt:411` polls every 3 s; `getLatestShipmentLocation` downloads every row then uses `maxByOrNull` |
| 12 | Confirmed | `TransitScreen.kt:437-447` builds an `IN_TRANSIT` shipment from QR text |
| 13 | Confirmed | `HandoverOrchestrator.kt:57` takes `temperaturePassed` from the caller |
| 14 | Confirmed | `HandoverEntity.kt:16` unique index on `shipmentId` |
| 15 | Confirmed | No `ViewModel` under `ui/`; `RepositoryProvider.kt:31` global object; `:144` and `TemperatureSimulator.kt:45` uncancelled scopes; `TemperatureEventDao.kt:63` demo corruption method in production code |
| 16 | Confirmed | `gradle.properties:1`; `LiveSupabaseIntegrationTest`; `app/build.gradle:25` targetSdk 34; no `.github/`; `README.md` ends inside a code block |

## Differences from the brief

These don't change the findings, but they affect the plan:

1. **The live test is more dangerous than the brief says.** `RemoteDtoMappingTest.kt:157` asserts the demo URL, so
   `local.properties` points at the demo project and a plain `./gradlew testDebugUnitTest` would write to and
   delete from it. That same assertion fails on any clean clone.
2. **Four tables have no committed schema.** `shipments`, `temperature_events`, `alerts` and `handovers` were
   created by hand. `0000_legacy_schema` rebuilds them from `RemoteDtos.kt`, so local tests have real tables.
3. **`testDataSourceGracefulFailureWithPlaceholder` attempts a real connection** to a placeholder host, so the
   default suite is not fully offline. Phase 1 has to fix this too.
4. **The audit test uses its own numbers.** See the mapping in `DECISIONS.md`.
5. **The Supabase MCP server is read-only.** See `HANDOFF.md`, Q1.
6. **Dev database not reachable yet.** The security advisors returned an empty list, but `list_tables` and
   `list_migrations` timed out, so that empty list is not evidence that the project is clean.
