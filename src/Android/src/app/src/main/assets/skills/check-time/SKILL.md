---
name: check-time
description: Tell the user the current date and time. Use this when the user says things like "what time is it", "现在几点了", "今天几号", "what's today's date", etc.
---

# Check Time

## Instructions

Call the `run_intent` tool with the following exact parameters:

- intent: get_current_date_and_time
- parameters: "{}"

## Response

After receiving the result, format it in a friendly way. Examples:
- Chinese: "现在是2026年5月25日，星期一，下午3点半。"
- English: "It's currently Monday, May 25th, 2026, at 3:30 PM."
