# Release Validation Runbook

Date: 2026-04-14  
Scope: Repeatable execution flow for pre-release validation

## Quick Commands

PowerShell from project root:

```powershell
./scripts/run_release_validation.ps1
```

Include connected instrumentation if a watch/emulator is attached:

```powershell
./scripts/run_release_validation.ps1 -IncludeConnectedTests
```

## What the Script Covers

1. Sets Java runtime to Android Studio JBR (Java 21).
2. Runs unit tests.
3. Builds release with minify enabled.
4. Optionally runs connected instrumentation tests when a device is present.

## Manual Steps After Script

1. Execute scenarios in docs/SOAK_AND_RELIABILITY_CHECKLIST.md.
2. Record per-device results in docs/DEVICE_TEST_MATRIX.md.
3. Update docs/RELEASE_NOTES_RISK_CLOSURE_TEMPLATE.md with final evidence.
4. Confirm closure state in docs/RISK_CLOSURE_MAPPING_R1_R13.md.

## Failure Handling

- If unit tests fail: block release and open remediation PR.
- If release build fails: block release and capture stacktrace output.
- If connected tests fail: capture logs/screenshots and file incident.
- If soak criteria fails: follow docs/HOTFIX_TRIAGE_RUNBOOK.md.

## Final Signoff Checklist

- Unit tests green
- Release build green
- Connected tests green (if executed)
- Soak checklist completed
- Device matrix updated
- Risk closure release notes finalized
