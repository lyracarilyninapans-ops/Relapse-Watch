# Safe Entry/Exit Flow Remediation Plan

Date: 2026-04-17  
Scope: Relapse-Watch safe-zone entry/exit detection, transition handling, notification behavior, and reliability under restart/offline/jitter scenarios.

## 1) Objectives and Non-Goals

Objectives:
- Eliminate false safe-zone entry/exit alerts on cold start or unknown state.
- Ensure transition alerts are delivered immediately even when network is degraded.
- Prevent stale persisted inside/outside state from corrupting later transition logic.
- Add practical anti-flap hysteresis for GPS boundary jitter.
- Harden runtime behavior under service restart, boot, listener failure, and safe-zone config changes.
- Add targeted tests for transition correctness and regression protection.

Non-goals:
- No redesign of reminder playback pipeline.
- No schema redesign for Firestore safe-zone events beyond transition-source metadata cleanup.
- No UI theme/layout changes outside safe-zone alert flow.

## 2) Current Findings to Remediate

F1. Unknown-state emission can generate false transitions after restart.
- Current behavior emits SAFE_ZONE_ENTER or SAFE_ZONE_EXIT when previous state is null.
- Risk: false navigation/return alerts without a real boundary crossing in current runtime.

F2. Transition flow blocks on sync before posting user-facing alert.
- Current transition handler calls syncActivityData() before showing notification.
- Risk: delayed or missing urgent alert if network is poor.

F3. Persisted inside-state can become stale when safe zone is removed/inactive.
- Current sync path clears safe zone config but may leave persisted inside/outside state unchanged.
- Risk: next activation compares against stale state and misclassifies first transition.

F4. Hysteresis constants are effectively disabled.
- Entry/exit confirmation sample counts are both 1.
- Risk: boundary jitter causes noisy enter/exit flapping.

F5. Confirmation counter behavior is fragile for future thresholds > 1.
- Current counter seeding and increment order is not robust if required confirmations increase.

F6. Listener resiliency is weak for partial failure cases.
- If one listener fails unexpectedly, restart behavior is not explicit per-stream.

F7. Notification cleanup is not consistently invoked by alert activities.
- Dismiss helpers exist but are not used from activity lifecycle paths.

## 3) Implementation Strategy

Work in 5 focused phases, each with merge gate and rollback-safe commit boundaries.

Branching suggestion:
- fix/safe-entry-exit-phase1-correctness
- fix/safe-entry-exit-phase2-alert-latency
- fix/safe-entry-exit-phase3-state-lifecycle
- fix/safe-entry-exit-phase4-hysteresis
- fix/safe-entry-exit-phase5-tests-and-hardening

## 4) Phase Plan

## Phase 1 - Transition Correctness Baseline

Goal:
- Stop false transitions on unknown state while preserving first-run initialization.

Code touchpoints:
- app/src/main/java/com/example/relapse_watch/services/MonitoringForegroundService.kt

Changes:
1. Introduce explicit baseline initialization behavior for unknown state.
- Replace immediate emit-on-unknown with baseline-only persistence:
  - When previous state is null, store currentlyInside and return without emitting enter/exit event.
- Add optional feature flag to allow old behavior only for temporary diagnostics.

2. Tighten transition preconditions.
- Emit SAFE_ZONE_ENTER/SAFE_ZONE_EXIT only when previous state is non-null and different from currentlyInside.

3. Add structured logs for baseline initialization.
- Log one-time baseline establishment with location accuracy and distance-to-boundary.

Acceptance criteria:
- On service restart with unchanged real position, no safe-zone event is written.
- No exit/enter full-screen alert is shown on first sample solely due to unknown previous state.
- Existing real transition behavior remains functional.

Rollback:
- Single commit revert restores previous unknown-state behavior.

## Phase 2 - Alert-First, Sync-After Ordering

Goal:
- Ensure user guidance appears immediately at transition time.

Code touchpoints:
- app/src/main/java/com/example/relapse_watch/services/MonitoringForegroundService.kt
- app/src/main/java/com/example/relapse_watch/services/SyncService.kt

Changes:
1. Reorder transition side effects in handleConfirmedSafeZoneTransition().
- New order:
  - Persist local event.
  - Record activity event.
  - Show transition notification immediately.
  - Launch sync asynchronously (non-blocking) with error logging.

2. Add bounded async sync execution.
- Use service lifecycleScope launch for sync path and avoid blocking main transition flow.
- Keep current sync mutex behavior in SyncService unchanged.

3. Add timing logs.
- Record event timestamp and notification post timestamp to verify alert latency.

Acceptance criteria:
- Alert posts within expected UI threshold even when network unavailable.
- Sync still occurs eventually and preserves existing retry behavior.
- No duplicate sync storm from transition burst (mutex still protects).

Rollback:
- Revert ordering commit only.

## Phase 3 - Persisted State Lifecycle Integrity

Goal:
- Guarantee persisted inside/outside state is cleared or recalibrated when safe zone lifecycle changes.

Code touchpoints:
- app/src/main/java/com/example/relapse_watch/services/SyncService.kt
- app/src/main/java/com/example/relapse_watch/services/MonitoringForegroundService.kt
- app/src/main/java/com/example/relapse_watch/data/preferences/WatchPreferences.kt

Changes:
1. Clear inside-state whenever safe zone becomes null or inactive in syncSafeZoneFromFirestore().
- In remoteSafeZone == null path: clearInsideSafeZone().
- In config.isActive == false path: clearInsideSafeZone().

2. Guard in proximity check when no active zone.
- Ensure no stale pending transition counters remain when returning early.

3. Optional safety helper.
- Add clearSafeZoneRuntimeState() helper in MonitoringForegroundService to centralize reset of:
  - pendingSafeZoneTransitionToInside
  - pendingSafeZoneTransitionConfirmations
  - boundary sampling counters if needed.

Acceptance criteria:
- Deactivating safe zone from phone side cannot produce stale-state transitions later.
- Re-activation initializes baseline correctly and emits only on real crossing.

Rollback:
- Revert SyncService state-clear additions independently.

## Phase 4 - Hysteresis and Confirmation Robustness

Goal:
- Reduce boundary jitter flapping while keeping genuine transitions responsive.

Code touchpoints:
- app/src/main/java/com/example/relapse_watch/services/MonitoringForegroundService.kt

Changes:
1. Increase confirmation thresholds.
- Proposed start values (tunable):
  - SAFE_ZONE_ENTER_CONFIRMATION_SAMPLES = 2
  - SAFE_ZONE_EXIT_CONFIRMATION_SAMPLES = 3
- Keep high-confidence fast-path for large boundary deltas.

2. Fix counter semantics.
- Initialize candidate confirmations at 1 for first qualifying sample.
- Increment only on subsequent matching samples.
- Trigger once count >= requiredConfirmations.
- Reset immediately on contradictory sample.

3. Add explicit dwell-like protection near boundary.
- Keep or extend boundary margin logic; tune with accuracy-aware thresholding.

Acceptance criteria:
- In simulated jitter around radius edge, enter/exit oscillation is significantly reduced.
- In clear movement across boundary, transition still triggers within acceptable delay.

Rollback:
- Constant-only rollback path available.

## Phase 5 - Reliability Hardening and Notification Hygiene

Goal:
- Improve resilience and ensure clean user experience.

Code touchpoints:
- app/src/main/java/com/example/relapse_watch/services/MonitoringForegroundService.kt
- app/src/main/java/com/example/relapse_watch/presentation/PreNavigationActivity.kt
- app/src/main/java/com/example/relapse_watch/presentation/SafeZoneReturnActivity.kt
- app/src/main/java/com/example/relapse_watch/services/NotificationService.kt

Changes:
1. Listener resiliency.
- Add explicit per-listener error handling and restart/backoff behavior.
- Ensure one failed stream does not silently disable updates forever.

2. Notification cleanup wiring.
- Inject or access NotificationService in alert activities and call:
  - dismissNavigationNotification() when PreNavigationActivity finishes/cancels.
  - dismissReturnNotification() when SafeZoneReturnActivity finishes/dismisses.

3. Transition-source metadata cleanup (optional).
- If needed, update event metadata/source value from watch_geofence to watch_proximity for clarity.

Acceptance criteria:
- Notification cards do not linger after alert activities dismiss.
- Listener failure in one stream recovers without full service restart.

Rollback:
- Activity-level notification cleanup is isolated and easily reversible.

## 5) Test Plan

## Unit tests

Add new tests focused on transition correctness:
- MonitoringForegroundService_UnknownState_DoesNotEmitTransition.
- MonitoringForegroundService_RealExit_EmitsExitOnce.
- MonitoringForegroundService_RealEnter_EmitsEnterOnce.
- MonitoringForegroundService_JitterNearBoundary_NoFlapWithThresholds.
- MonitoringForegroundService_ClearStateOnSafeZoneInactive.
- MonitoringForegroundService_AlertPostedBeforeSync.

Add SyncService tests:
- SyncService_ClearInsideState_WhenRemoteSafeZoneMissing.
- SyncService_ClearInsideState_WhenRemoteSafeZoneInactive.

## Integration tests (service-level)

- Restart continuity:
  - Start service with unknown state, verify baseline only.
  - Restart service at same coordinates, verify no false transition.

- Config lifecycle:
  - Active -> inactive -> active sequence from Firestore sync path.
  - Ensure no stale-state transition on reactivation.

- Offline transition:
  - Force sync failure, verify transition alert still shown promptly.

## Manual validation matrix

1. Cold start while outside safe zone
- Expected: no immediate false exit alert from unknown baseline.

2. Move from inside to outside
- Expected: one navigation alert, event uploaded when network returns.

3. Move from outside to inside
- Expected: one return alert, no duplicate enter spam.

4. Hover near boundary for 5+ minutes
- Expected: no rapid alternating enter/exit sequence.

5. Disable safe zone from phone app
- Expected: inside-state cleared; no stale transition later.

6. Re-enable safe zone and stay stationary
- Expected: baseline set; no immediate artificial transition.

7. Kill/restart service while stationary
- Expected: no false transition on restart.

8. Listener interruption simulation
- Expected: listener stream recovers; sync updates resume.

## 6) Telemetry and Evidence Requirements

Add structured logs/events:
- safe_zone_baseline_initialized
- safe_zone_transition_candidate
- safe_zone_transition_confirmed
- safe_zone_transition_rejected_jitter
- safe_zone_notification_posted
- safe_zone_sync_async_started
- safe_zone_sync_async_failed

Capture these metrics during validation:
- Transition-to-notification latency (ms).
- False transition count during restart tests.
- Boundary flapping rate (events/hour near edge).
- Listener recovery time after induced error.

## 7) Rollout Plan

1. Internal canary build
- Enable full transition tracing logs for QA.
- Run 24-hour internal soak with movement scenarios.

2. Staged release gate
- No critical regression in pairing, monitoring, reminder playback, sync.
- Safe entry/exit manual matrix complete with evidence.

3. Post-release monitoring
- Watch for increased missed transitions or alert delays.
- If needed, tune confirmation constants without architecture rollback.

## 8) Risks and Mitigations

Risk: Over-tuned confirmation thresholds may delay true alerts.
- Mitigation: start conservative (2/3), evaluate field logs, tune quickly.

Risk: Async sync after alert may hide data lag.
- Mitigation: explicit sync-failure logs and periodic sync backstop.

Risk: Listener restart strategy may cause excess churn.
- Mitigation: bounded backoff and debounce.

## 9) Definition of Done

All are required:
- False unknown-state transitions removed.
- Alerts shown immediately on confirmed transitions regardless of network state.
- Persisted inside-state cleared on safe-zone removal/inactivation.
- Hysteresis and confirmation logic validated against boundary jitter.
- Notification cleanup applied in alert activities.
- New tests added and passing for transition correctness.
- Manual matrix executed with documented evidence.
