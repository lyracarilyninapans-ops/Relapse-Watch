# Device Test Matrix

Date: 2026-04-14  
Scope: Phase 5 instrumented and soak validation coverage for Relapse-Watch

## Matrix

| Device Type | Model / AVD | Wear OS/API | Build Variant | Required Scenarios | Status | Notes |
|---|---|---|---|---|---|---|
| Emulator | Wear API 33 AVD | Wear OS 4 / API 33 | debug + release | pairing, monitoring lifecycle, reminder trigger basic, sync worker basic | Pending | Baseline emulator gate |
| Emulator | Wear API 34 AVD | Wear OS 5 / API 34 | debug + release | geofence transitions, reminder queue sequencing, boot recovery simulation | Pending | API upgrade compatibility |
| Physical Watch | SM-L330 | Device output label "- 16" | debug + release | instrumentation smoke, full soak, battery trend, geofence reliability, media playback | In Progress | connectedDebugAndroidTest now passing with smoke test |
| Physical Watch | Device B (optional) | OEM previous | release | regression spot-check for geofence + notification behavior | Pending | Cross-device safety |

## Required Scenario Definitions

1. Pairing lifecycle
- Fresh pair, unpair, re-pair.
- Verify watch status updates and sync scheduler continuity.

2. Monitoring continuity
- Start monitoring and keep screen off for extended interval.
- Verify continued location events and no service collapse.

3. Sync correctness
- Trigger periodic and immediate sync conditions.
- Verify no overlapping upload behavior and correct retry semantics.

4. Reminder playback queue
- Trigger two reminders close together.
- Verify queue ordering and duplicate suppression.

5. Geofence transitions
- Trigger exit and return transitions repeatedly.
- Verify notification flow and event recording consistency.

6. Boot recovery
- Reboot watch while paired.
- Verify service start, geofence re-registration, and scheduler health.

## Pass Criteria

- No critical crash for each matrix row.
- Scenario pass rate 100% for critical paths.
- No data loss events observed.
- No persistent sync deadlock/overlap behavior.

## Evidence Capture per Row

- Build artifact hash: N/A (instrumentation run only)
- Test execution timestamp: 2026-04-14 local session
- Logs path: app/build/reports/androidTests/connected/debug/index.html
- Screenshots/video proof: Pending
- Issues created (if any): None for instrumentation smoke

## Signoff

- QA owner:
- Engineering owner:
- Date:
- Decision: Pass / Blocked
