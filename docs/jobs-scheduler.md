# Jobs Scheduler

**Repository:** `management-node`  
**Description:** `Provides APIs to be accessed by Consumer and Producer Federators for the purpose of dynamic configuration management `  
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0 `

---
This document describes the two ways to schedule recurring jobs in the system: CRON and Interval. It also includes examples for both CRON expressions and ISO‑8601 durations.

## 1. CRON type

Use CRON when you want precise calendar-based schedules (e.g., "every weekday at 09:00" or "at 2:30 AM on the first of every month").

A typical CRON expression uses 5 or 6 space-separated fields, depending on the scheduler implementation:

- Second (optional): 0–59
- Minute: 0–59
- Hour: 0–23
- Day of month: 1–31
- Month: 1–12 or JAN–DEC
- Day of week: 0–7 (0 or 7 = Sunday) or SUN–SAT

Common special characters:
- `*`: any value
- `,`: value list separator
- `-`: range of values
- `/`: step values (e.g., */5)
- `?`: no specific value (used in some cron dialects where both DOM and DOW exist)

Examples:
- Every day at 02:30 (with seconds): 0 30 2 * * *
- Every day at 02:30 (5-field style): 30 2 * * *
- Every 5 minutes: */5 * * * * (or 0 */5 * * * * when using seconds)
- Every Monday at 09:00: 0 0 9 * * MON
- At 00:00 on the first of every month: 0 0 0 1 * *
- Weekdays at 18:15: 0 15 18 * * MON-FRI

Tips:
- If your scheduler expects the seconds field, use 6 fields; otherwise use 5.
- If both Day-of-month and Day-of-week are present, some schedulers require one of them to be ?, indicating "not specified."

## 2. Interval type

Use Interval when you want a fixed duration between runs (e.g., "every 15 minutes"), independent of calendar concepts. Intervals are represented as ISO‑8601 duration strings.

ISO‑8601 Duration format: PnYnMnDTnHnMnS
- P: designator meaning "period"
- nY: years
- nM: months (in the date part)
- nW: weeks (alternative to days; if used, don’t combine with D)
- nD: days
- T: time designator that precedes the time components
- nH: hours
- nM: minutes (in the time part)
- nS: seconds

Common duration examples:
- PT15M: every 15 minutes
- PT1H: every 1 hour
- PT1H30M: every 1 hour and 30 minutes
- P1D: every 1 day (24 hours)
- P2DT12H: every 2 days and 12 hours

Some systems also support repeating intervals using the ISO‑8601 repeating interval notation:
- Rn/start/duration, where Rn is the repeat count (R without a number means unlimited repeats)
- Example (repeat 5 times starting on a given instant, once per day): R5/2025-10-14T00:00:00Z/P1D

Notes:
- When only a duration is provided (e.g., PT15M), the next run is typically computed from the last run time plus the duration.
- If your platform supports a startAt or firstRunAt property, pair it with the duration to control the initial trigger time.

## Choosing between CRON and Interval
- Choose CRON for calendar-aware schedules or when you need specific days/times (like "every weekday at 09:00").
- Choose Interval for simple, uniform spacing between runs (like "every 15 minutes"), irrespective of wall-clock boundaries.

## Quick reference examples

CRON:
- 0 0 9 * * MON-FRI: Weekdays at 09:00
- 0 0 0 1 * *: Midnight on the first day of each month
- 0 */10 * * * *: Every 10 minutes (with seconds field)

ISO‑8601 durations (Interval):
- PT5M: every five minutes
- PT2H: every two hours
- P1D: every day
- R/2025-10-14T08:00:00Z/PT30M: from 2025-10-14 08:00Z, every 30 minutes, repeat indefinitely

## Database tables that accept schedule expressions and types

The following tables store schedule configuration and accept both CRON expressions and Interval (ISO‑8601 duration) values:

- consumer
  - schedule_type (varchar): expected values are 'cron' or 'interval' (case-insensitive depending on DB usage).
  - schedule_expression (varchar):
    - If schedule_type = 'cron' → a CRON expression (e.g., "0 */10 * * * *" or "*/5 * * * *").
    - If schedule_type = 'interval' → an ISO‑8601 duration (e.g., "PT15M", "P1D").

- product_consumer
  - schedule_type (varchar): expected values are 'cron' or 'interval'.
  - schedule_expression (varchar):
    - If schedule_type = 'cron' → a CRON expression.
    - If schedule_type = 'interval' → an ISO‑8601 duration.

Notes:
- Default/backfill in migration sets schedule_type to 'cron' with a sample expression (*/5 * * * *) for existing rows.
- Ensure expressions match the scheduler dialect in use (5-field or 6-field with seconds).