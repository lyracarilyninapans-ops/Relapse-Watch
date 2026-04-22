# Relapse-Watch Battery Optimization Report
Date: 2026-04-16
Scope: Wear OS watch app in Relapse-Watch/app

## Executive Summary
The watch app currently favors reliability over power efficiency (continuous foreground service, high-accuracy GPS, frequent sync triggers, and real-time listeners).

Highest-value optimization opportunities:
1. Remove duplicate location subscriptions (foreground service + ViewModel).
2. Replace always-high-accuracy polling with adaptive location strategy.
3. Reduce unnecessary network wakeups (sync cadence, debounce real-time config reactions, avoid urgent data layer when not needed).
4. Add batching and movement filters to avoid writes/computation for insignificant movement.

Expected result after top 3 fixes: meaningful battery life improvement during active paired operation, with minimal feature regression if guardrails are applied.

Risk and effort scales used in this report:
- Risk Level: Low (minimal behavior impact), Medium (some behavior change risk), High (safety/latency regression possible without strong guardrails).
- Effort Level: Low (less than 1 day), Medium (1-3 days), High (4+ days and/or cross-component refactor).

## Optimization Risk and Effort Matrix
| Optimization | Priority | Risk Level | Effort Level | Notes |
|---|---|---|---|---|
| Remove duplicate GPS subscriptions (service + ViewModel) | P0 | Medium | Medium | Refactor state ownership to service-derived source; high battery gain. |
| Adaptive location strategy (balanced baseline + temporary high accuracy) | P0 | High | High | Strong battery upside, but needs careful tuning to avoid delayed transitions. |
| Sync throttling/debounce and periodic interval tuning | P1 | Medium | Medium | Reduces radio wakeups; keep bypass path for critical events. |
| Restrict urgent Data Layer usage to emergency paths only | P1 | Low | Low | Routine status can be normal priority with minimal behavior risk. |
| Add anti-flap hysteresis for full-screen alert triggers | P1 | Medium | Medium | Avoids noisy boundary triggers; must preserve safety responsiveness. |
| Limit media prefetch concurrency and skip unchanged assets | P2 | Low | Low | Isolated change with low operational risk. |

## Audit Findings (Prioritized)

### P0 - Duplicate GPS subscriptions while UI is visible
Evidence:
- Service tracking starts at 30s interval in ActivityTrackingService.startTracking.
- UI ViewModel also subscribes to location updates at 10s for safe-zone status display.

Why this matters:
- Two active fused-location subscriptions can significantly increase GNSS/radio/CPU work.
- On Wear devices this is typically the largest avoidable battery cost.

Recommendation:
- Make MonitoringForegroundService the single source of truth for location.
- Publish current in/out safe-zone state from service to DataStore/Room and render UI from that state.
- Remove or gate ViewModel location stream unless service is not running.

Risk Level: Medium
Effort Level: Medium

---

### P0 - Always using PRIORITY_HIGH_ACCURACY for periodic polling
Evidence:
- LocationRequest uses PRIORITY_HIGH_ACCURACY with periodic updates.

Why this matters:
- High-accuracy mode continuously favors GPS, which is expensive on watch battery.

Recommendation:
- Use adaptive location policy:
  - Default: PRIORITY_BALANCED_POWER_ACCURACY at 60-180s.
  - Escalate to HIGH_ACCURACY at 15-30s only near boundaries (safe-zone edge or reminder radius margin).
  - De-escalate after stable inside/outside state.
- Add setMinUpdateDistanceMeters to suppress tiny movement updates.
- Consider setMaxUpdateDelayMillis for batching where UX allows.

Risk Level: High
Effort Level: High

---

### P1 - Sync wakeups are more frequent than needed
Evidence:
- Periodic work runs every 15 minutes.
- Immediate sync requested on app pairing/start.
- Extra syncs triggered on safe-zone transitions.
- Real-time listeners trigger sync on every observed active safe-zone/reminder change.

Why this matters:
- Frequent sync attempts increase radio usage and wakeups, especially when there are no pending records.

Recommendation:
- Keep one immediate sync for critical lifecycle events, but throttle subsequent requests with minimum sync spacing (for example 2-5 minutes).
- Debounce listener-triggered sync (for example 10-30 seconds) and skip if payload hash/version unchanged.
- Consider increasing periodic sync from 15m to 30m where clinical requirements allow.
- Keep transition-triggered sync for safety events, but collapse duplicate transition bursts.

Risk Level: Medium
Effort Level: Medium

---

### P1 - Urgent Data Layer should be reserved for true urgency
Evidence:
- sendWatchStatus uses setUrgent on PutDataRequest.

Why this matters:
- Urgent transport attempts faster delivery at higher power cost.

Recommendation:
- Use normal priority for routine status updates.
- Reserve urgent for emergency/safety-critical events only.

Risk Level: Low
Effort Level: Low

---

### P1 - Aggressive full-screen notifications/vibration can amplify power spikes
Evidence:
- Safe-zone and reminder paths use high-priority full-screen notifications and vibration patterns.

Why this matters:
- Repeated full-screen wakeups and haptics are expensive, especially during noisy boundary conditions.

Recommendation:
- Add transition stability gate (for example 2 consecutive confirming samples or dwell time) before firing exit/return full-screen alerts.
- Keep current cooldowns; extend with anti-flap hysteresis around geofence boundaries.

Risk Level: Medium
Effort Level: Medium

---

### P2 - Background media prefetch concurrency can be constrained
Evidence:
- Reminder sync launches background media downloads per reminder in IO scope.

Why this matters:
- Burst downloads can cause short-term radio and CPU spikes.

Recommendation:
- Limit concurrent media downloads (for example semaphore of 1-2).
- Skip download when metadata/hash/etag indicates unchanged media.
- Optionally defer non-critical prefetch to charging + unmetered constraints.

Risk Level: Low
Effort Level: Low

## Proposed Implementation Plan

### Phase 1 (High impact, low-medium risk)
1. Remove duplicate location stream from MainViewModel and consume service-derived safe-zone state.
2. Introduce adaptive location profile in LocationService + ActivityTrackingService:
   - Balanced baseline + temporary high-accuracy escalation near boundaries.
   - Minimum displacement threshold.
3. Add sync throttling/debounce in SyncScheduler + MonitoringForegroundService listener reactions.

### Phase 2 (Medium impact)
1. Change routine Data Layer status updates to non-urgent.
2. Add anti-flap hysteresis for safe-zone transition notifications.
3. Bound media prefetch concurrency and avoid unchanged downloads.

### Phase 3 (Validation and hardening)
1. Add telemetry counters:
   - Location updates/hour
   - Sync attempts/hour, successful uploads/hour
   - Full-screen alert count/day
   - Urgent data layer sends/day
2. Tune thresholds based on field telemetry and safety acceptance criteria.

## Measurement Plan (Before/After)
Collect on at least 3 representative watch models over 24h mixed-use runs:
1. Battery drain per hour while paired and monitoring enabled.
2. Count/location of wakeups and job executions.
3. Location callback rate and average accuracy.
4. Network bytes and radio active time.
5. Safety SLA metrics:
   - Time to safe-zone exit alert.
   - Reminder trigger reliability and latency.

Recommended tooling:
- adb shell dumpsys batterystats
- Android Studio Energy Profiler
- WorkManager diagnostics + custom app metrics logs

## Risks and Guardrails
1. Risk: Lower location aggressiveness delays safety alerts.
   Guardrail: Escalate to high accuracy near boundaries and for transition confirmation windows.
2. Risk: Debounced sync delays caregiver visibility.
   Guardrail: Bypass debounce for safety-critical transition events.
3. Risk: Reducing urgent transport delays non-critical status.
   Guardrail: Keep urgent path for emergency event types only.

## Code References Audited
- app/src/main/java/com/example/relapse_watch/services/ActivityTrackingService.kt
- app/src/main/java/com/example/relapse_watch/services/LocationService.kt
- app/src/main/java/com/example/relapse_watch/services/MonitoringForegroundService.kt
- app/src/main/java/com/example/relapse_watch/services/SyncScheduler.kt
- app/src/main/java/com/example/relapse_watch/services/SyncService.kt
- app/src/main/java/com/example/relapse_watch/services/WearableCommunicationService.kt
- app/src/main/java/com/example/relapse_watch/services/GeofenceService.kt
- app/src/main/java/com/example/relapse_watch/services/BootCompletedReceiver.kt
- app/src/main/java/com/example/relapse_watch/viewmodel/MainViewModel.kt
- app/src/main/AndroidManifest.xml

## Conclusion
The app is functionally safety-first, but currently over-allocates battery to maintain that reliability. The recommended P0/P1 changes preserve safety intent while reducing duplicate sensor use and network wakeups, which should materially improve watch endurance in normal operation.
