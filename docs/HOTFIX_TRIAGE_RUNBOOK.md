# Hotfix Triage Runbook

Date: 2026-04-14  
Project: Relapse-Watch

## Purpose

Provide a single operational flow for incident intake, expedited fix delivery, rollback readiness, and post-release verification.

## Required Templates

- Incident intake: .github/ISSUE_TEMPLATE/hotfix-incident.yml
- Hotfix PR: .github/PULL_REQUEST_TEMPLATE/hotfix.md
- Standard risk-remediation PR: .github/pull_request_template.md
- Master remediation plan: RISK_REMEDIATION_PLAN_V2.md

## Triage Severity Policy

- Sev-0 Critical: Immediate user harm or core monitoring failure. Stop non-critical work, assign owner and reviewer now, start hotfix branch immediately.
- Sev-1 High: Significant user impact on core behavior. Start hotfix same day.
- Sev-2 Medium: Limited or recoverable impact. Patch in next release window unless risk trend worsens.
- Sev-3 Low: Minor impact. Track and include in planned phase work.

## End-to-End Hotfix Flow

1. Intake and classify incident
- Open issue with hotfix incident template.
- Assign hotfix owner and required reviewer.
- Map to one or more risk IDs (R1-R13).

2. Define minimal safe scope
- State exact failure mode and smallest safe code change.
- List explicit exclusions to avoid scope creep.

3. Create branch and implement
- Branch naming: hotfix/<incident-id>-<short-topic>
- Keep commits minimal and logically separated.

4. Validate before PR
- Run targeted unit tests.
- Run integration/instrumented checks for affected area.
- Build affected release variant and execute core smoke checks.

5. Open PR with hotfix template
- Include root cause, validation evidence, and rollback plan.
- Include rollout plan and monitoring signals.

6. Review and merge
- Reviewer confirms minimal scope and rollback readiness.
- Merge with expedited policy once required checks pass.

7. Deploy and monitor
- Roll out internal/canary first for Sev-0/Sev-1 unless emergency full rollout is required.
- Watch failure metrics and incident-specific signals for defined window.

8. Closeout and prevention
- Link merged PR to incident issue.
- Record final root cause and preventive follow-up task(s).
- Update risk closure status in release notes if applicable.

## Validation Matrix by Affected Area

Sync and scheduling issues:
- Concurrency/single-flight tests.
- Duplicate upload checks.
- Reminder sync failure propagation checks.

Data and migration issues:
- Migration survival tests on representative seed DB fixtures.
- Data compatibility checks for rollback.

Network/media security issues:
- URL validator matrix (malformed, non-HTTPS, non-allowlist, private ranges, oversize payload).
- Hardened client behavior checks.

Geofence/location issues:
- Geofence registration/remove tests.
- Transition reliability checks on target API levels.

Release/build integrity issues:
- Minified release build and core flow smoke tests.

## Rollback Decision Rules

Trigger rollback if any occurs during monitoring window:
- Data-loss path is confirmed.
- Core monitoring/sync continuity regresses beyond agreed threshold.
- Geofence or reminder trigger reliability drops below baseline threshold.
- Incident-specific failure metric remains above trigger threshold after deploy.

Rollback checklist:
- Revert identified commit set.
- Activate runtime kill switch if available.
- Confirm compatibility of persisted data post-rollback.
- Re-run critical smoke tests.

## Communication Checklist

At incident open:
- Severity, impact, owner, reviewer, risk IDs.

At PR open:
- Minimal fix scope, tests run, rollback trigger.

At deploy:
- Rollout scope, start time, monitoring owner.

At close:
- Root cause confirmed, preventive action assigned, links to issue/PR/release note.

## SLAs (Recommended)

- Sev-0: triage start within 15 minutes, fix decision within 60 minutes.
- Sev-1: triage start within 1 hour, fix decision within 4 hours.
- Sev-2: triage same business day.
- Sev-3: triage within 2 business days.

## Quick Start

1. Create incident using .github/ISSUE_TEMPLATE/hotfix-incident.yml.
2. Create branch hotfix/<incident-id>-<topic>.
3. Implement minimal fix and validate.
4. Open PR using .github/PULL_REQUEST_TEMPLATE/hotfix.md.
5. Deploy with staged monitoring and close out with lessons learned.
