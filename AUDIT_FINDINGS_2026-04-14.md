# Relapse-Watch Audit Findings

Date: 2026-04-14  
Auditor role: Senior Software Engineer and Security Auditor  
Scope: Relapse-Watch only

## Executive Summary

The project has a solid baseline architecture (Hilt, Room, DataStore, WorkManager, lifecycle-aware coroutines), but there are material production risks in data protection, network input hardening, sync orchestration, and long-running performance behavior on Wear OS hardware.

Top priorities to fix first:

1. Backup exposure of sensitive pairing/patient metadata.
2. Untrusted media URL handling and transport security validation.
3. Duplicate sync pipelines (foreground loop + WorkManager) causing avoidable race and battery pressure.

## Risk Register (Table)

| Risk ID | Summary | Severity | Likelihood | Impact | Priority | Effort Level | Status |
|---|---|---|---|---|---|---|---|
| R1 | Backup-exposed sensitive pairing/patient data | High | Medium | High | P0 | Medium | Open |
| R2 | Weak media URL validation (non-HTTPS/untrusted hosts) | High | Medium | High | P0 | Medium | Open |
| R3 | Duplicate sync orchestrators causing overlap and battery drain | High | High | High | P0 | High | Open |
| R4 | Expensive daily summary recomputation on watch hardware | Medium | High | Medium | P1 | High | Open |
| R5 | Destructive Room migration can wipe local history | Medium | Medium | High | P1 | High | Open |
| R6 | Boot receiver export broadens trigger surface | Medium | Medium | Medium | P1 | Low | Open |
| R7 | Verbose trace logs may leak operational details | Low | High | Medium | P2 | Low | Open |
| R8 | Mutable geofence PendingIntent where immutable is safer | Medium | Medium | Medium | P1 | Low | Open |
| R9 | Reminder sync failures are masked as success | Medium | High | Medium | P1 | Medium | Open |
| R10 | Full-table reminder refresh causes DB churn/inconsistency windows | Medium | Medium | Medium | P1 | Medium | Open |
| R11 | Firestore set semantics may overwrite unrelated fields | Medium | Medium | High | P1 | Medium | Open |
| R12 | Release build has minify/obfuscation disabled | Low | Medium | Medium | P2 | Medium | Open |
| R13 | Metadata type fidelity lost through string coercion | Low | Medium | Low | P3 | Medium | Open |

## Findings

### 1) High - Sensitive data can be extracted via backups

Category: Security / Privacy  
Impact: Pairing and patient identifiers may be recoverable from device backups.

Evidence:

- app/src/main/AndroidManifest.xml:33 sets `android:allowBackup="true"`.
- app/src/main/java/com/example/relapse_watch/data/preferences/WatchPreferences.kt stores sensitive values in clear preferences keys:
  - pairing_code
  - caregiver_uid
  - patient_id
  - patient_name
  - watch_id

Why this matters:

- In regulated or privacy-sensitive contexts, this increases exposure risk for identity linkage and pairing metadata.

Production-ready fix:

- Set `android:allowBackup="false"`.
- Also set `android:fullBackupContent="false"` and define strict data extraction rules if backup must remain enabled for non-sensitive state.
- Encrypt sensitive preference values at rest (Jetpack Security Crypto/Tink wrapper before writing to DataStore).

---

### 2) High - Media downloader accepts weak/unbounded remote input

Category: Security / Network Hardening  
Impact: Cleartext traffic and untrusted remote hosts may be fetched if backend data is poisoned.

Evidence:

- app/src/main/java/com/example/relapse_watch/services/MediaCacheManager.kt:48 only checks `startsWith("http")`.
- app/src/main/java/com/example/relapse_watch/services/MediaCacheManager.kt:86 performs raw `HttpURLConnection` fetch.

Why this matters:

- `http://` is currently accepted.
- Arbitrary host URLs can be consumed from Firestore-backed metadata.
- No explicit maximum response-size guard exists before download completion.

Production-ready fix:

- Require URI scheme to be exactly HTTPS.
- Enforce host allowlist (Firebase Storage domain set and/or approved CDN list).
- Reject private/local/link-local addresses after DNS resolution.
- Add max content-length and streaming byte cap.
- Prefer OkHttp with hardened TLS config and centralized interceptor-based policy checks.

---

### 3) High - Duplicate sync engines create race, cost, and battery risk

Category: Architecture / Reliability / Performance  
Impact: Redundant uploads, non-deterministic sync timing, and higher battery/network usage.

Evidence:

- app/src/main/java/com/example/relapse_watch/services/MonitoringForegroundService.kt:241 starts internal periodic sync.
- app/src/main/java/com/example/relapse_watch/services/MonitoringForegroundService.kt:252 uses `while (true)` loop with 5-minute interval.
- app/src/main/java/com/example/relapse_watch/services/SyncScheduler.kt:29 schedules periodic WorkManager sync.
- app/src/main/java/com/example/relapse_watch/services/SyncScheduler.kt:53 schedules one-time immediate sync.
- app/src/main/java/com/example/relapse_watch/presentation/MainActivity.kt:109 and BootCompletedReceiver also trigger scheduler calls.

Why this matters:

- Multiple independent schedulers call the same sync path.
- Increased chance of overlapping sync attempts and write contention.

Production-ready fix:

- Pick one source of truth for periodic scheduling (recommended: WorkManager).
- Keep service-level calls only for high-priority one-shot triggers.
- Add single-flight guard in `SyncService.syncActivityData()` via `Mutex` to serialize concurrent invocations.
- Add idempotency guard on upload paths (already partly present via stable IDs; keep and test).

---

### 4) Medium - Daily summary recomputation has poor scaling on watch devices

Category: Performance  
Impact: CPU and battery cost rises throughout the day.

Evidence:

- app/src/main/java/com/example/relapse_watch/services/ActivityTrackingService.kt:92 loads full-day records on each update.
- app/src/main/java/com/example/relapse_watch/services/ActivityTrackingService.kt:122 computes places using cluster scan.
- app/src/main/java/com/example/relapse_watch/services/ActivityTrackingService.kt:127 uses `clusters.none(...)` in loop (`O(n^2)` pattern).

Why this matters:

- Location updates can be frequent; full recomputation each time is expensive on Wear OS.

Production-ready fix:

- Switch to incremental aggregation:
  - Distance: add segment from previous point only.
  - Counters: update by event delta.
  - Places visited: geohash/grid bucket cardinality, not pairwise distance scan.
- Run expensive reconciliation opportunistically (charging + unmetered) as maintenance work.

---

### 5) Medium - Destructive Room migration can erase history in production

Category: Data Integrity / Reliability  
Impact: Local records may be dropped on schema upgrade.

Evidence:

- app/src/main/java/com/example/relapse_watch/di/AppModule.kt:37 uses `fallbackToDestructiveMigration(dropAllTables = true)`.

Why this matters:

- Activity logs, pending uploads, and reminder cache state can be lost silently after updates.

Production-ready fix:

- Replace with explicit migration chain for each schema version.
- Keep destructive fallback only for debug/internal builds.
- Add migration tests validating data survival for representative upgrade paths.

---

### 6) Medium - Boot receiver exported surface is broader than required

Category: Security Hardening  
Impact: External apps may attempt to trigger boot logic paths.

Evidence:

- app/src/main/AndroidManifest.xml:91 sets `android:exported="true"` for BootCompletedReceiver.

Why this matters:

- Even if behavior checks exist, reducing exposed components shrinks attack and abuse surface.

Production-ready fix:

- Set `android:exported="false"` for receiver unless there is a strict external requirement.
- If export remains necessary, enforce signature-level permission and perform defensive action validation.

---

### 7) Low - Debug/trace logs leak operational details

Category: Security / Observability Hygiene  
Impact: Reminder IDs, timestamps, and media context are visible in logs.

Evidence:

- Verbose trace logging in reminder/media flows:
  - app/src/main/java/com/example/relapse_watch/presentation/ReminderActivity.kt
  - app/src/main/java/com/example/relapse_watch/services/MediaCacheManager.kt
  - app/src/main/java/com/example/relapse_watch/services/ReminderPlaybackQueueManager.kt

Production-ready fix:

- Introduce centralized logger facade.
- Gate verbose logs behind debug builds.
- Redact identifiers, URLs, and timing values in production logs.

---

### 8) Medium - Mutable geofence PendingIntent is broader than necessary

Category: Security Hardening  
Impact: Increases attack surface for intent mutation where immutable PendingIntent would be sufficient.

Evidence:

- app/src/main/java/com/example/relapse_watch/services/GeofenceService.kt:38 uses `PendingIntent.FLAG_MUTABLE`.

Why this matters:

- Mutable PendingIntents should only be used when mutation is required by platform behavior.
- Security posture on modern Android favors immutable PendingIntents by default.

Production-ready fix:

- Switch to `PendingIntent.FLAG_IMMUTABLE` unless a proven platform/API requirement mandates mutable.
- Add a regression test on geofence registration/removal behavior after this change.

---

### 9) Medium - Reminder sync failure is masked, causing false success signals

Category: Reliability / Observability  
Impact: Sync pipeline can report success even when Firestore reminder pull failed.

Evidence:

- app/src/main/java/com/example/relapse_watch/services/SyncService.kt:253 calls `geoReminderRepository.syncFromFirestore(...)` and later returns `true` for this sync path when no exception bubbles.
- app/src/main/java/com/example/relapse_watch/data/repository/GeoReminderRepositoryImpl.kt:94 catches exceptions internally and only logs at line 95.

Why this matters:

- Upstream orchestration cannot distinguish successful config sync from failed reminder sync.
- This weakens retry strategy and can leave stale geofences/reminders active.

Production-ready fix:

- Change repository API to return `Result<Unit>` (or throw) and propagate failure to `SyncService`.
- Emit structured sync outcome metrics so alerting/retry logic can act on exact failure class.

---

### 10) Medium - Full-table reminder replacement causes churn and transient inconsistency

Category: Performance / Data Consistency  
Impact: Unnecessary delete-reinsert cycles increase IO, invalidate observers, and can create brief empty-state windows.

Evidence:

- app/src/main/java/com/example/relapse_watch/data/repository/GeoReminderRepositoryImpl.kt:91 deletes all rows.
- app/src/main/java/com/example/relapse_watch/data/repository/GeoReminderRepositoryImpl.kt:92 reinserts all reminders.

Why this matters:

- Frequent Firestore-driven sync can repeatedly churn local DB and downstream geofence reconciliation.
- Observer consumers may see temporary empty collections.

Production-ready fix:

- Replace wholesale refresh with diff-based upsert + targeted delete-by-id.
- Run sync in a single Room transaction to avoid inconsistent intermediate states.

---

### 11) Medium - Firestore write semantics risk unintended field loss

Category: Data Integrity / API Contract  
Impact: Document-level `.set(...)` without merge can overwrite fields not supplied in payload.

Evidence:

- app/src/main/java/com/example/relapse_watch/data/remote/FirestoreActivitySource.kt:100 uses `.set(summary)`.
- app/src/main/java/com/example/relapse_watch/data/remote/FirestorePairingSource.kt:84-90 writes watch pairing state with `.set(mapOf(...))` for unpair.

Why this matters:

- Future schema additions at the same document path can be silently removed.

Production-ready fix:

- Use `SetOptions.merge()` where partial updates are intended.
- Reserve full replace writes for explicitly versioned, fully-owned documents.
- Add contract tests validating non-targeted fields survive updates.

---

### 12) Low - Release build lacks shrinking/obfuscation hardening

Category: Security / Release Engineering  
Impact: Larger attack surface and easier reverse engineering in production APK.

Evidence:

- app/build.gradle.kts:25 has `isMinifyEnabled = false` for release.

Production-ready fix:

- Enable R8/ProGuard in release.
- Add keep rules for reflection-heavy frameworks (Hilt, Room, serialization, Media3) and verify startup + critical flows under minified build.

---

### 13) Low - Metadata type information is lost during persistence round-trip

Category: Code Quality / Data Fidelity  
Impact: Numeric/boolean metadata values are coerced to strings and cannot be reconstructed as original types.

Evidence:

- app/src/main/java/com/example/relapse_watch/data/repository/ActivityRepositoryImpl.kt:58 serializes metadata values via `v.toString()`.
- app/src/main/java/com/example/relapse_watch/data/repository/ActivityRepositoryImpl.kt:73 deserializes as `Map<String, String>`.

Why this matters:

- Limits downstream analytics and rule evaluation that depend on typed metadata.

Production-ready fix:

- Persist metadata as typed JSON (`JsonElement` map or sealed event payload model) instead of string coercion.
- Add migration for existing rows if typed metadata becomes required for new features.

## Architectural Smells

1. Sync responsibilities are split across service loops, activity triggers, boot receiver actions, and worker scheduling.  
Recommended direction: one orchestration model with explicit trigger taxonomy (periodic, transition, recovery).

2. Firestore DTO parsing is spread across services/repositories with stringly-typed maps.  
Recommended direction: schema-validated DTO layer + mappers + centralized parse error metrics.

3. Local/remote state coherence depends heavily on side effects rather than a single synchronization state machine.  
Recommended direction: define sync states and transitions explicitly (Idle, Running, Backoff, OfflineQueued, Recovery).

## Suggested Remediation Order

1. Security baseline hardening:
   - Backup policy fix
   - HTTPS/host validation for media
   - exported receiver review

2. Sync architecture consolidation:
   - Single scheduler model
   - Single-flight sync lock
   - Integration tests for no-overlap guarantees

3. Data durability:
   - Replace destructive migrations
   - Add migration and recovery tests

4. Performance optimization:
   - Incremental daily summary updates
   - Reduced high-frequency full scans

## Validation Checklist After Fixes

- Verify no sensitive keys are included in backups.
- Verify downloader rejects non-HTTPS and non-allowlisted hosts.
- Verify only one sync execution path runs at a time under stress.
- Verify schema upgrade preserves historical and pending-upload records.
- Verify daily battery profile improves in 24-hour watch run.
