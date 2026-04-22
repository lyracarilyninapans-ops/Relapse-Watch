# Risk Closure Mapping (R1-R13)

Date: 2026-04-14  
Scope: Relapse-Watch implementation evidence after phased remediation execution.

## Status Summary

- Closed in code and unit-tested: R1, R2, R3, R5, R7, R8, R9, R10, R11, R12, R13
- Partially complete (needs performance benchmark/soak evidence): R4
- Instrumented smoke status: connectedDebugAndroidTest passing on connected SM-L330 device.
- Pending final release-readiness evidence collection: all risks still require Phase 5 soak and scenario-level instrumented proof bundle.

## Risk-by-Risk Evidence

| Risk | Status | Evidence | Remaining for final closure |
|---|---|---|---|
| R1 Backup exposure | Implemented | app/src/main/AndroidManifest.xml backup disabled; encrypted sensitive prefs in app/src/main/java/com/example/relapse_watch/data/preferences/WatchPreferences.kt and app/src/main/java/com/example/relapse_watch/data/preferences/SensitiveValueCipher.kt | Device-level verification of no plaintext sensitive values |
| R2 Media URL hardening | Implemented + tested | app/src/main/java/com/example/relapse_watch/services/MediaUrlValidator.kt; hardened downloader in app/src/main/java/com/example/relapse_watch/services/MediaCacheManager.kt; tests in app/src/test/java/com/example/relapse_watch/services/MediaUrlValidatorTest.kt | Expand malicious URL matrix and instrumented network abuse checks |
| R3 Duplicate sync orchestration | Implemented + tested | Removed internal periodic loop in app/src/main/java/com/example/relapse_watch/services/MonitoringForegroundService.kt; single-flight guard in app/src/main/java/com/example/relapse_watch/services/SyncService.kt; concurrency tests in app/src/test/java/com/example/relapse_watch/services/SyncServiceTest.kt | Long-running soak concurrency evidence |
| R4 Daily summary performance | Partially complete | Incremental summary updates in app/src/main/java/com/example/relapse_watch/services/ActivityTrackingService.kt; tests in app/src/test/java/com/example/relapse_watch/services/ActivityTrackingServiceTest.kt | Baseline vs post-change CPU/battery benchmark report |
| R5 Destructive migration | Implemented | Removed fallbackToDestructiveMigration in app/src/main/java/com/example/relapse_watch/di/AppModule.kt; schema export enabled in app/src/main/java/com/example/relapse_watch/data/local/RelapseWatchDatabase.kt and app/build.gradle.kts | Add explicit migration chain tests for supported upgrade paths |
| R6 Boot receiver exposure | Mitigated with compatibility guard | Receiver remains exported for BOOT_COMPLETED compatibility in app/src/main/AndroidManifest.xml; action/permission checks in app/src/main/java/com/example/relapse_watch/services/BootCompletedReceiver.kt | Add instrumented boot recovery security tests |
| R7 Verbose trace logs | Implemented | Central logger in app/src/main/java/com/example/relapse_watch/services/AppLogger.kt; trace logs gated to debug in reminder/media/service flows | Production log review during soak |
| R8 Mutable geofence PendingIntent | Implemented | Immutable flag in app/src/main/java/com/example/relapse_watch/services/GeofenceService.kt | Device matrix verification of geofence behavior |
| R9 Reminder sync failure masking | Implemented + tested | Result-based propagation across app/src/main/java/com/example/relapse_watch/domain/repository/GeoReminderRepository.kt, app/src/main/java/com/example/relapse_watch/data/repository/GeoReminderRepositoryImpl.kt, app/src/main/java/com/example/relapse_watch/services/SyncService.kt; tests in app/src/test/java/com/example/relapse_watch/services/SyncServiceTest.kt | Add end-to-end retry/alert evidence |
| R10 Reminder full refresh churn | Implemented | Transactional diff sync in app/src/main/java/com/example/relapse_watch/data/repository/GeoReminderRepositoryImpl.kt with DAO batch operations in app/src/main/java/com/example/relapse_watch/data/local/dao/GeoReminderDao.kt | Add repository integration tests with fixture data |
| R11 Firestore overwrite semantics | Implemented | SetOptions.merge in app/src/main/java/com/example/relapse_watch/data/remote/FirestoreActivitySource.kt and app/src/main/java/com/example/relapse_watch/data/remote/FirestorePairingSource.kt | Add contract tests against non-target fields |
| R12 Minify disabled release | Implemented | release minify enabled in app/build.gradle.kts; keep rules in app/proguard-rules.pro | Release smoke evidence on minified build |
| R13 Metadata type fidelity | Implemented + tested | Typed JSON conversion in app/src/main/java/com/example/relapse_watch/data/repository/ActivityRepositoryImpl.kt; tests in app/src/test/java/com/example/relapse_watch/data/repository/ActivityRepositoryImplTest.kt | Verify compatibility on historical rows during soak |

## Test Gate Evidence

- Local unit gate: ./gradlew testDebugUnitTest (Java 21, Android Studio JBR) passed after remediation slices.
- Connected instrumentation smoke gate: ./gradlew connectedDebugAndroidTest passed (1 test executed).
- Release build gate: ./gradlew assembleRelease passed with minify enabled.

## Phase 5 Remaining Checklist

1. Add/expand migration chain tests with historical schema fixtures.
2. Run instrumented tests for boot recovery, geofence transitions, and service lifecycle continuity.
3. Run staged soak for sync/location/reminder workloads.
4. Attach benchmark and reliability artifacts to release PR using .github/pull_request_template.md.
