# Wear OS Screen Roles

This document gives a simple, one-line description of each Wear OS screen and activity role.

## Activities (entry points)

- `MainActivity`: Main watch entry point; shows pairing or monitoring based on pairing state.
- `SettingsActivity`: Hosts watch settings actions (notably reset pairing).
- `ReminderActivity`: Displays reminder alert content (text/image/video actions).
- `PreNavigationActivity`: Pre-navigation warning/countdown with vibration before navigation starts.
- `NavigationActivity`: Hosts turn/direction guidance UI toward home/safe location.

## Compose Screens (UI content)

- `PairingScreen`: Shows pairing code and pairing status message.
- `MonitoringScreen`: Home monitoring dashboard (sync status, safe-zone status, reminders, settings access).
- `SettingsScreen`: Settings UI with long-press reset pairing and confirmation.
- `ReminderScreen`: Renders alert title/body with optional image/video and dismiss controls.
- `PreNavigationScreen`: Visual warning + countdown + cancel action before navigation.
- `NavigationScreen`: Direction arrow and distance display for simplified watch navigation.

## Reminder Playback Contract

- Queueing: Reminder playback requests are serialized via `ReminderPlaybackQueueManager`.
- Launch policy: Reminders launch even if audio/video cache prefetch fails; UI degrades gracefully where possible.
- Audio-only edge case: If audio cannot be resolved and there is no photo/video fallback, playback is skipped.
- Finish policy: Audio/video reminders finish on player end/error; timed auto-finish is reserved for photo/text/error fallback flows.