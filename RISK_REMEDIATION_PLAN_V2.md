# Relapse-Watch Risk Remediation Plan V2

Date: 2026-04-14  
Scope: Relapse-Watch risk closure for R1-R13 with behavior preservation for pairing, monitoring, geofence/reminder triggers, sync, and media playback.

## 1) Execution Model

- Branching: one branch per phase, merge only after phase gate pass.
- Commit strategy: one logical risk slice per commit, test evidence attached to each PR.
- Delivery posture: test-first for high-risk slices (sync, migration, network hardening).
- Non-goal: feature expansion unrelated to risk closure.

Branch map:
- Phase 0: fix/phase0-baseline-hardening
- Phase 1: fix/phase1-data-durability
- Phase 2: fix/phase2-network-security
- Phase 3: fix/phase3-sync-architecture
- Phase 4: fix/phase4-performance-optimization
- Phase 5: fix/phase5-release-readiness

## 2) Risk Closure Matrix (R1-R13)

| Risk | Summary | Owner Phase | Commit Slice(s) | Required Verification | Closure Evidence |
|---|---|---|---|---|---|
| R1 | Backup exposure of sensitive pairing/patient data | Phase 0 + 2 | P0-A, P2-C | Manifest/security tests, prefs-at-rest check | Backup disabled or tightly scoped + encrypted sensitive values |
| R2 | Weak media URL validation | Phase 2 | P2-A, P2-B | Malicious URL matrix + host allowlist tests | Non-HTTPS and non-allowlist hosts rejected |
| R3 | Duplicate sync orchestrators | Phase 3 | P3-A, P3-B, P3-C | Concurrency tests + soak | Single periodic scheduler + max 1 in-flight sync |
| R4 | Expensive daily summary recomputation | Phase 4 | P4-A | Performance benchmark | CPU and latency improvement against baseline |
| R5 | Destructive Room migration | Phase 1 | P1-A, P1-B | Migration test suite | No data loss across supported upgrades |
| R6 | Boot receiver export surface | Phase 0 | P0-A | Instrumented boot recovery test | Minimum exposure with verified boot behavior |
| R7 | Verbose trace log leakage | Phase 0 | P0-C | Log policy tests/review | Sensitive fields redacted, debug-only trace |
| R8 | Mutable geofence PendingIntent | Phase 4 | P4-B | Geofence registration/transition tests | Immutable pending intent works on target APIs |
| R9 | Reminder sync failures masked | Phase 3 | P3-D | Contract tests | Failures propagate and trigger retries/visibility |
| R10 | Full-table reminder refresh churn | Phase 3 | P3-E | Repository integration tests | Transactional diff-based update validated |
| R11 | Firestore overwrite semantics risk | Phase 1 | P1-C | Firestore contract tests | Partial updates preserve unrelated fields |
| R12 | Minify/obfuscation disabled | Phase 0 | P0-B | Release build + smoke tests | Minified release stable in core flows |
| R13 | Metadata type fidelity loss | Phase 4 | P4-C | Repository tests + migration compatibility tests | Numeric/boolean/object fidelity preserved |

## 3) Global Entry Criteria

All phases must satisfy:
- Mainline is green before branch cut.
- Baseline metrics captured before risky changes (sync overlap, battery, summary latency).
- Existing core behavior recorded with smoke test script.

Baseline metrics to capture once (before Phase 0):
- Duplicate sync attempts per hour.
- Avg sync duration and failure rate.
- Daily summary update latency.
- 2-hour battery drain under monitoring workload.

## 4) Phase Plan

## Phase 0 - Baseline Safety and Release Hygiene

Branch: fix/phase0-baseline-hardening

Commit slices:
- P0-A: Manifest hardening in app/src/main/AndroidManifest.xml.
- P0-B: Release hardening in app/build.gradle.kts and app/proguard-rules.pro.
- P0-C: Logging facade + debug gating in service/reminder playback flows.

Go/No-Go gates:
- Release minified build succeeds.
- Pairing, monitoring, sync trigger, and reminder playback smoke pass on release variant.
- No sensitive identifiers in production logs.

Notes:
- For boot receiver hardening, prefer minimum exported surface that still preserves boot recovery on target Wear OS/API levels; verify behavior instrumentally before locking config.

Rollback strategy:
- Keep release hardening and logging facade independent commits so any issue can be reverted without rolling back manifest safety defaults.

Definition of done:
- Code merged.
- Unit/instrumented tests pass.
- Release smoke evidence attached.
- Risk mapping updated for R1/R6/R7/R12.

## Phase 1 - Data Durability and Firestore Write Contracts

Branch: fix/phase1-data-durability

Commit slices:
- P1-A: Remove destructive migration path in app/src/main/java/com/example/relapse_watch/di/AppModule.kt and enable schema export.
- P1-B: Add explicit migration chain and migration test fixtures.
- P1-C: Firestore merge-safe write contract updates in activity/pairing sources.

Go/No-Go gates:
- Migration tests pass for all supported prior versions.
- Seed DB fixtures survive upgrade with zero row loss in critical tables.
- Firestore tests verify non-target fields preserved after updates.

Rollback strategy:
- Keep migration chain and Firestore semantics in separate commits.
- If migration defect appears, block rollout and ship hotfix migration patch before feature work resumes.

Definition of done:
- DB schema versioning documented.
- Migration coverage added to CI.
- R5/R11 closure evidence included.

## Phase 2 - Network and Secrets Hardening

Branch: fix/phase2-network-security

Commit slices:
- P2-A: Introduce MediaUrlValidator and integrate into media cache path.
- P2-B: Replace raw HttpURLConnection with hardened OkHttp policy (HTTPS-only, allowlist, timeout, max-bytes).
- P2-C: Encrypt sensitive preferences with backward-compatible read migration.

Go/No-Go gates:
- URL validator matrix passes:
  - malformed URLs
  - non-HTTPS
  - localhost/private/link-local targets
  - non-allowlist hosts
  - oversize content
- Sensitive preference keys are not readable as plaintext on device storage.
- Backward compatibility migration succeeds for existing unencrypted values.

Rollback strategy:
- Put preference encryption read/write behind a kill switch for staged rollout.
- Keep validator enforcement and HTTP client swap separately revertible.

Definition of done:
- Security tests added and green.
- Telemetry for rejected media URLs and migration fallback events added.
- R1/R2 closure evidence included.

## Phase 3 - Sync Architecture Consolidation

Branch: fix/phase3-sync-architecture

Execution checkpoints:
- Checkpoint 3A: scheduler unification + single-flight guard.
- Checkpoint 3B: reminder sync contract and diff-based persistence.

Commit slices:
- P3-A: Remove duplicate periodic sync loop from MonitoringForegroundService.
- P3-B: WorkManager as single periodic scheduler, one-time sync only for event triggers.
- P3-C: Single-flight Mutex guard in SyncService.
- P3-D: Propagate reminder sync failure via explicit Result contract.
- P3-E: Replace delete-all reminder refresh with transactional diff-based upsert/delete.

Quantitative gates:
- Under 20 concurrent sync triggers, max concurrent sync executions = 1.
- Duplicate upload rate = 0 in stress tests.
- Reminder sync failure paths are surfaced and retried; no false-success outcome.

Rollback strategy:
- Ship 3A first and soak for 24h internal testing.
- Merge 3B only after 3A stability.
- Keep old reminder refresh path behind temporary fallback switch for one release cycle if needed.

Definition of done:
- Concurrency tests added for single-flight.
- Contract tests added for reminder failure propagation.
- R3/R9/R10 closure evidence included.

## Phase 4 - Performance and Geofence Hardening

Branch: fix/phase4-performance-optimization

Commit slices:
- P4-A: Incremental summary aggregation in ActivityTrackingService.
- P4-B: PendingIntent mutability hardening in GeofenceService.
- P4-C: Metadata type fidelity modernization in ActivityRepositoryImpl.

Quantitative gates:
- Daily summary update latency reduced by >= 40% from baseline (same workload).
- 2-hour monitoring battery drain not worse than baseline; target >= 10% improvement.
- Geofence transition success rate unchanged or improved versus baseline.

Rollback strategy:
- Keep incremental aggregation behind a feature flag for rapid fallback.
- Geofence mutability change isolated in a single commit for fast revert if device-specific issues appear.

Definition of done:
- Performance benchmark report attached (before/after).
- Metadata compatibility tests pass.
- R4/R8/R13 closure evidence included.

## Phase 5 - Full Regression, Soak, and Release Readiness

Branch: fix/phase5-release-readiness

Commit slices:
- P5-A: Expand integration and reliability tests across critical flows.
- P5-B: Run soak and reliability checks.
- P5-C: Final release notes with explicit R1-R13 closure mapping.

Release gates:
- All critical/high-priority tests green.
- 72-hour staged internal soak with no critical regressions.
- Release build smoke pass on target devices/emulators.
- Risk closure report signed off with evidence links.

Definition of done:
- Go-live checklist completed.
- Residual risks documented with mitigations.
- Rollback playbook validated.

## 5) Test Strategy by Layer

Unit tests (mandatory each phase):
- Sync single-flight behavior.
- URL validation policy.
- Metadata type serialization/deserialization fidelity.
- Result contract propagation.

Integration tests:
- Room migration survival.
- Firestore merge semantics and partial update preservation.
- Reminder diff-based transactional consistency.

Instrumented tests:
- Boot recovery.
- Foreground monitoring lifecycle continuity.
- Geofence transitions and reminder triggering.

Soak tests:
- Long-running monitoring + periodic sync + reminder playback under network variance.

## 6) CI/CD Quality Gates

Per phase PR requirements:
- ./gradlew testDebugUnitTest passes.
- Required integration/instrumented suites for touched risk areas pass.
- Release assemble for minified variant succeeds.
- Static checks pass (lint, detekt if configured).

Merge policy:
- No direct merge without evidence artifacts attached.
- No scope creep changes in risk-remediation branches.

## 7) Observability and Telemetry Requirements

Add/confirm structured events:
- sync_started
- sync_completed
- sync_failed(reason)
- reminder_sync_applied
- reminder_sync_failed(reason)
- media_url_rejected(reason)
- prefs_migration_fallback_used

Operational dashboards:
- Sync success rate and failure taxonomy.
- Reminder sync drift indicators.
- Security policy rejection counts.

## 8) Rollback Playbook

For each phase release candidate, define:
- Revert commit set.
- Feature flags/switches to disable risky pathways.
- Data compatibility notes (especially migrations and encrypted prefs).
- Incident trigger thresholds for rollback decision.

Immediate rollback triggers:
- Data loss risk confirmed.
- Sync deadlock/overlap causing user-facing failures.
- Geofence reliability regression over baseline threshold.

## 9) Ownership Template (Fill Before Execution)

| Phase | DRI | Reviewer | Backup Owner | Start | Target End | Status |
|---|---|---|---|---|---|---|
| 0 |  |  |  |  |  |  |
| 1 |  |  |  |  |  |  |
| 2 |  |  |  |  |  |  |
| 3 |  |  |  |  |  |  |
| 4 |  |  |  |  |  |  |
| 5 |  |  |  |  |  |  |

## 10) Final Acceptance Checklist

- All risks R1-R13 moved to Closed with objective evidence.
- Core behavior preserved: pairing, monitoring, geofence/reminder triggers, sync, media playback.
- Performance and reliability meet or exceed baseline targets.
- Release artifacts and runbooks prepared.
- Post-release monitoring plan approved.
