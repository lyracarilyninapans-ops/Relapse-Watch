# Phone-Initiated Unpair Sync Implementation Plan (Watch)

Date: 2026-04-20  
Scope: Relapse-Watch Android app unpair-state consistency when unpair is initiated from phone app.

## 1. Problem Statement

When the caregiver unpairs from the phone app, the watch can remain in a locally paired state and continue monitoring workflows until the watch UI process is restarted or local state is otherwise reset.

Observed symptom:
- Phone shows unpaired.
- Watch still behaves as paired (monitoring UI/services continue).

## 2. Root Causes (Audit Summary)

1. Unpair listeners are currently tied to `MainViewModel` lifecycle.
- If UI/ViewModel is not active, remote unpair signal may not be consumed.

2. Pairing listeners drop cache snapshots.
- In reconnect/offline-first scenarios, cache-first `unpaired` can be ignored.

3. Pairing listener failures close flows without retry.
- Transient Firestore errors can permanently stop unpair observation in current session.

4. Signal subscription depends on local identifiers being non-blank.
- If local state is partial/corrupt (e.g., `isPaired=true` but missing code/uid), listeners might not attach.

## 3. Goals

- Ensure watch transitions to unpaired promptly after phone-side unpair, even if watch UI is not open.
- Make unpair detection resilient to network transitions and transient Firestore errors.
- Preserve existing pairing, reminder, safe-zone, and sync behavior.

## 4. Non-Goals

- Redesigning entire pairing protocol.
- Migrating away from Firestore pairing docs in this iteration.
- UI redesign of pairing/monitoring screens.

## 5. High-Level Fix Strategy

Primary change:
- Move phone-initiated unpair observation from UI-only lifecycle into a long-lived service lifecycle (`MonitoringForegroundService`) with retry/backoff and safe fallback handling.

Secondary hardening:
- Adjust Firestore listener behavior to avoid dropping critical unpair transitions.
- Ensure boot-time reconciliation checks remote pairing state before fully resuming paired operations.

## 6. Implementation TODO Checklist

## P0 - Must Complete Before Release

- [ ] Add service-level unpair observer in `MonitoringForegroundService`.
- [ ] Trigger centralized cleanup (`UnpairUseCase.execute(alsoNotifyPhone = false)`) from service observer exactly once per paired session.
- [ ] Add retry/backoff wrapper for unpair listener collection (do not permanently close on transient listener failure).
- [ ] Remove/adjust cache-snapshot filtering so unpaired state is not missed.
- [ ] Add boot-time remote reconciliation path for `isPaired=true` local state.
- [ ] Add/extend unit tests for remote unpair detection and cleanup triggering.

## P1 - Strongly Recommended

- [ ] Introduce explicit “remote pairing health” telemetry logs for listener start, stop, retry, and unpair event handling.
- [ ] Guard duplicate unpair handling across UI + service with a single shared idempotency mechanism.
- [ ] Add integration test scenario for: paired -> phone unpair while watch UI closed.

## P2 - Optional Hardening

- [ ] Add periodic reconciliation worker (lightweight) to self-heal stale paired local state.
- [ ] Consider moving from dual-doc signal dependency to one canonical source-of-truth doc contract.

## 7. Detailed Fix Descriptions

### Fix A: Service-Lifecycle Remote Unpair Observer (Core)

Files:
- `app/src/main/java/com/example/relapse_watch/services/MonitoringForegroundService.kt`
- `app/src/main/java/com/example/relapse_watch/domain/usecase/UnpairUseCase.kt`

Changes:
1. Add a new listener job (e.g., `remoteUnpairListenerJob`) started with other realtime listeners.
2. Build listener inputs from `watchPreferences.isPaired`, `watchPreferences.pairingCode`, `watchPreferences.caregiverUid`.
3. While `isPaired=true`, subscribe to available remote signals:
   - global `watchPairingCodes/{pairingCode}` status
   - caregiver `users/{uid}/watchPairing/current` status
4. Merge signals and trigger unpair cleanup once when first `unpaired=true` arrives.
5. Stop/cancel listener when local paired state becomes false.

Acceptance criteria:
- Phone unpair is detected while watch UI is closed.
- Unpair cleanup runs once (idempotent) and monitoring service stops.

### Fix B: Retryable Listener Collection (Resilience)

Files:
- `app/src/main/java/com/example/relapse_watch/data/remote/FirestorePairingSource.kt`
- `app/src/main/java/com/example/relapse_watch/services/MonitoringForegroundService.kt`

Changes:
1. Replace one-shot listener termination behavior with retry loop at caller or flow-wrapper level.
2. On listener error, log and retry with bounded delay (e.g., 2-5 seconds).
3. Ensure cancellation exceptions still propagate normally.

Acceptance criteria:
- Temporary Firestore failures do not permanently disable unpair observation.

### Fix C: Cache Snapshot Handling for Unpair Safety

Files:
- `app/src/main/java/com/example/relapse_watch/data/remote/FirestorePairingSource.kt`

Changes:
1. Remove blanket ignore of `snapshot.metadata.isFromCache` for pairing status listeners.
2. If needed, gate only specific stale transitions instead of dropping all cache emissions.
3. Prefer transition-aware handling (`paired -> unpaired`) at consumer level to avoid false positives.

Acceptance criteria:
- Cache-first unpair state is not missed.
- No regressions where stale old unpaired incorrectly forces cleanup after fresh pair.

### Fix D: Boot-Time Remote Reconciliation

Files:
- `app/src/main/java/com/example/relapse_watch/services/BootCompletedReceiver.kt`
- `app/src/main/java/com/example/relapse_watch/data/remote/FirestorePairingSource.kt` (optional helper)

Changes:
1. On boot when local `isPaired=true`, perform one remote status check before resuming full paired behavior.
2. If remote indicates unpaired/missing pairing, run unpair cleanup and skip paired startup path.
3. If remote check fails (network unavailable), start minimal safe mode and defer full paired assumptions until listener confirms.

Acceptance criteria:
- Device reboot does not keep stale paired state when phone already unpaired.

### Fix E: Idempotency and Duplicate Trigger Control

Files:
- `app/src/main/java/com/example/relapse_watch/services/MonitoringForegroundService.kt`
- `app/src/main/java/com/example/relapse_watch/viewmodel/MainViewModel.kt`

Changes:
1. Introduce shared guard (atomic flag or preference-backed short-lived marker) to prevent double cleanup across service and ViewModel listeners.
2. Keep cleanup path centralized in `UnpairUseCase`.
3. Maintain behavior where phone-initiated cleanup uses `alsoNotifyPhone=false`.

Acceptance criteria:
- No duplicate cleanup side effects.
- No crash or race due to concurrent unpair handlers.

## 8. Test Plan

## Unit Tests

- `MainViewModel` / service observer logic:
  - emits unpaired from caregiver doc -> triggers cleanup once
  - emits unpaired from code doc -> triggers cleanup once
  - repeated unpaired emissions -> still one cleanup
  - listener error -> retries and eventually handles unpaired

- Firestore pairing source:
  - cache snapshot containing unpaired is forwarded or safely handled
  - transient listener error path does not break long-term observation contract

## Integration / Device Tests

1. Pair watch successfully.
2. Put watch app in background or close UI (service still running).
3. Unpair from phone settings.
4. Verify watch stops monitoring and returns to pairing state on next foreground.
5. Reboot watch after phone unpair; verify it does not resume paired mode.
6. Re-pair and verify no stale-unpaired false reset.

## Regression Coverage

- Pairing flow (fresh pair, re-pair).
- Safe-zone and reminder realtime updates while paired.
- Sync scheduling and cancellation around unpair transitions.

## 9. Rollout & Risk Controls

- Rollout behind internal build validation first.
- Add targeted logs around unpair listener lifecycle and cleanup triggers.
- Keep rollback simple: preserve old ViewModel listener path during transition, but prioritize service path.

Suggested log markers:
- `REMOTE_UNPAIR_LISTENER_STARTED`
- `REMOTE_UNPAIR_LISTENER_RETRY`
- `REMOTE_UNPAIR_SIGNAL_RECEIVED`
- `REMOTE_UNPAIR_CLEANUP_EXECUTED`

## 10. Exit Criteria

Implementation is complete when all are true:
- Phone-initiated unpair reliably reflects on watch within expected listener latency.
- Works when watch UI is not active.
- Works across reboot scenarios.
- No regressions in pairing/monitoring/reminder/safe-zone core flows.
- Test evidence captured in release validation artifacts.
