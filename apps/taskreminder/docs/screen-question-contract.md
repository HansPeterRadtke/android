Screen purpose: show whether task reminders are trustworthy, what needs attention now, what will happen next, and what action is safe.

Primary user questions:
Can I trust reminders on this phone?
What is due, missed, or next?
What can I safely do now?
Where is the exact history?

Immediate visible answers:
Notification permission state, exact alarm state, number of enabled tasks, next scheduled task, task cards with due time and actions.

Hidden by default:
Raw history log and implementation details.

Primary actions:
Add task, edit task, complete now, snooze now, delete task, open history, open alarm settings. Destructive delete stays attached to one task card.

Data freshness and trust signals:
Status text shows current permission/alarm state and refresh time. History file is app-private and append-only.

Acceptance gates:
The app must compile. The first screen must not be dominated by raw logs. History must still be visible. Notifications must keep Complete and Snooze actions.
