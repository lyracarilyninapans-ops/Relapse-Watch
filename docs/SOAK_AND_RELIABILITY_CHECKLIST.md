# Soak and Reliability Checklist

Date: 2026-04-14  
Scope: Phase 5B reliability validation for Relapse-Watch

## Objective

Validate long-running stability for pairing, monitoring continuity, sync correctness, reminder playback flow, and geofence transitions under realistic watch conditions.

## Test Window

- Target duration: 72 hours staged soak
- Minimum acceptable dry run: 12 hours
- Devices:
  - Wear OS emulator (latest API)
  - At least one physical watch

## Preconditions

- Use release build variant (minified enabled).
- Pair watch with caregiver account and active patient.
- Ensure sample reminders and safe zone exist remotely.
- Confirm network variability scenarios available:
  - steady Wi-Fi
  - intermittent connectivity
  - temporary offline period

## Execution Scenarios

1. Monitoring continuity
- Keep foreground monitoring active for full soak window.
- Confirm service stays active across screen-off and idle periods.
- Validate location events continue to be recorded.

2. Sync reliability and single-flight
- Trigger concurrent sync paths (boot path, one-time trigger, periodic worker).
- Confirm no overlapping upload execution pattern.
- Confirm retries happen when network is unavailable.

3. Reminder lifecycle reliability
- Trigger repeated reminder entries/exits around cooldown boundaries.
- Confirm queue sequencing behavior is stable.
- Confirm media fallback behavior when audio/video fetch fails.

4. Geofence transition integrity
- Simulate safe-zone exit and return transitions repeatedly.
- Verify transition notifications are consistent and non-duplicative.
- Verify immutable pending intent path functions on target API/device set.

5. Boot recovery
- Reboot watch mid-soak.
- Confirm service, sync scheduling, and geofence re-registration recover correctly.

## Metrics to Capture

- Sync success rate
- Sync failure count by reason
- Duplicate upload count
- Reminder sync failures and recovery behavior
- Geofence transition hit rate
- App crash count
- Battery drain over fixed 2-hour and full soak windows

## Evidence Table

| Scenario | Start/End | Device | Result | Evidence Artifact |
|---|---|---|---|---|
| Monitoring continuity |  |  |  |  |
| Sync reliability |  |  |  |  |
| Reminder lifecycle |  |  |  |  |
| Geofence transitions |  |  |  |  |
| Boot recovery |  |  |  |  |

## Go or No-Go Criteria

- No critical crash in soak window.
- No data-loss behavior observed.
- No persistent sync deadlock/overlap pattern.
- Geofence and reminder triggers preserve expected behavior.
- Battery profile not worse than baseline threshold.

## Failure Handling

- If critical failure occurs:
  - capture logs and reproduction steps immediately
  - map to risk ID and open hotfix incident
  - apply rollback playbook from docs/HOTFIX_TRIAGE_RUNBOOK.md

## Final Signoff

- Owner:
- Reviewer:
- Date:
- Decision: Go / No-Go
- Notes:
