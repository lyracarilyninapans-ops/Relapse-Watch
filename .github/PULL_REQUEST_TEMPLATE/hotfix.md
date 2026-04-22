## Hotfix Summary

- Incident/Ticket:
- Affected risk area:
- Severity:
- Production impact:

## Root Cause

- Trigger:
- Failure mode:
- Why existing safeguards did not prevent it:

## Minimal Fix Scope

- Change introduced:
- Why this is the smallest safe fix:
- Explicitly excluded from this PR:

Files touched:
- 

## Validation (Required)

- [ ] Targeted unit tests pass.
- [ ] Relevant integration/instrumented checks pass.
- [ ] No regression in core flows: pairing, monitoring, geofence/reminder triggers, sync, media playback.
- [ ] Release build for affected variant succeeds.

Commands/results:
- 

## Risk and Safety Checks

- [ ] No data-loss path introduced.
- [ ] No new plaintext secret/sensitive data exposure.
- [ ] Logging does not leak sensitive identifiers.
- [ ] Failure is observable (clear log/metric signal).

## Rollback Plan (Mandatory)

- Revert commit(s):
- Runtime disable switch (if available):
- Safe rollback window:
- Rollback trigger threshold:

## Deployment Plan

- Rollout scope (internal/canary/full):
- Monitoring window:
- Signals to watch:
- Owner on-call:

## Reviewer Signoff

- [ ] Fix scope is minimal and correct.
- [ ] Validation is sufficient for hotfix urgency.
- [ ] Rollback plan is actionable.
- [ ] Approved for expedited merge.
