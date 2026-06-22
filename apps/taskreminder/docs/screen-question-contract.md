Screen purpose: Task Reminder answers what needs action now, whether reminders can be trusted, and how the user can configure or audit tasks on a phone without raw logs or oversized modal workflows.

Primary questions:
Can I trust reminders on this phone right now?
What needs action today?
What reminder is next?
What can I safely do now?
How do I configure one selected task?
Where is the audit trail when I need proof?

Immediate visible answers:
The Today mode shows a trust banner, today metrics, next reminder, and safe actions.
The Manage mode shows task cards and configuration entry points.
The Edit mode shows one selected task as a full-screen phone workflow, not a large modal dialog.
The History mode shows readable recent events first, with raw log as explicit drill-down.

Hidden by default:
Raw log lines, internal task ids, implementation booleans, and full audit detail are hidden until History drill-down.
Secondary task configuration is behind focused selection dialogs.

Primary actions:
Complete today and Snooze are visible on task cards when the task is enabled.
Edit schedule opens a selected-object edit screen.
Due time uses a focused time picker.
Repeat mode uses a focused single-choice dialog.
Snooze duration uses a focused single-choice dialog plus custom value field.
Delete shows a consequence preview and keeps history.
Disabled task actions show a human-readable reason.

Trust signals:
The top banner uses OK/BLOCKED words and explains notification and exact-alarm state.
The screen shows last refresh time.
The next reminder shows weekday and time.
After actions, visible feedback appears below the trust banner.

Acceptance gates:
The app compiles with Gradle.
The first screen is Today, not a raw form and not a raw log.
Top-level navigation is by work mode: Today, Manage, History.
Editing one task is a selected-object screen, not a large modal dialog.
Dialogs are only focused choices or consequence confirmations.
Raw logs are hidden by default.
Critical states are visible outside logs.
Primary actions are visible and disabled actions explain why.


Deep configuration update:
Repeat supports Hourly, Daily, Weekly with weekday selection, Monthly with day-of-month selection, Every N days, Every N hours, Custom interval with days/hours/minutes, and One-shot. Reminder outcomes are Complete, Snooze, Dismiss, and automatic not-completed closure when a new due occurrence replaces an unresolved previous occurrence. History records notified, scheduled, snoozed count, completed, dismissed, auto-not-completed, deleted, and save events.
