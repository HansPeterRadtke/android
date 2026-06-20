Screen purpose: Task Reminder answers what needs action now, whether reminders can be trusted, and how the user can configure or audit tasks without reading raw logs.

Primary questions:
- Can I trust reminders on this phone right now?
- What needs action today?
- What reminder is next?
- What can I safely do now?
- Where do I configure tasks?
- Where is the audit trail when I need proof?

Immediate visible answers:
- A trust banner at the top with plain OK/BLOCKED language for notification permission and exact-alarm permission.
- A Today cockpit shown by default with enabled task count, due/missed/completed/snoozed counts from today's history, and the next scheduled reminder.
- Task cards show one primary action first, then secondary actions.
- Empty state explains the next safe step instead of showing a blank list.

Hidden by default:
- Raw append-only history log.
- Internal task ids, raw boolean names, and implementation details.
- Full audit details until the History mode is opened.

Primary actions:
- Today mode: Complete today, Snooze, Add first task, Fix permissions.
- Manage mode: Add task, edit schedule, disable/enable by editing, delete task.
- History mode: View today summary, recent readable events, open raw log.
- Delete remains attached to one task card and is not the primary action.

Trust signals:
- The top banner shows notification and exact-alarm state in words, not color only.
- The header shows last refresh time.
- The next reminder shows a concrete weekday and time.

Acceptance gates:
- The app compiles with Gradle.
- The first screen is Today, not a raw form and not a raw log.
- Top-level navigation is by user work mode: Today, Manage, History.
- Raw logs are hidden by default.
- Critical states are visible outside logs.
- Primary actions are visible and labeled by consequence.
