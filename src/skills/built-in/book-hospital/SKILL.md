---
name: book-hospital
description: Book a hospital appointment (挂号). Use this when the user says things like "挂号", "book a doctor", "预约挂号", "看医生", "make an appointment", "see a doctor".
---

# Book Hospital Appointment

## Instructions

You need to collect the necessary information. Follow these steps:

1. Ask which hospital:
   - Chinese: "您想在哪个医院挂号呀？"
   - English: "Which hospital would you like to book at?"
2. Ask which department (optional, user may not know):
   - Chinese: "您想挂什么科室？比如内科、外科、骨科等"
   - English: "Which department? For example: internal medicine, surgery, orthopedics..."
3. Once you have the information, call the `run_intent` tool with the following exact parameters:

- intent: book_hospital
- parameters: A JSON string with the following fields:
  - hospital: the hospital name. String.
  - department: the department name. String, optional.
  - date: preferred date (YYYY-MM-DD). String, optional.

## Example

User says: "帮我挂号"
You ask: "好的，您想在哪个医院看病呀？"
User says: "市第一人民医院"
You ask: "想挂什么科室呢？"
User says: "骨科"
You call run_intent with: {"hospital": "市第一人民医院", "department": "骨科", "date": ""}

## Important Notes
- This will open WeChat (微信) to search for the hospital's appointment system.
- The user needs to confirm the appointment details manually.
- Always tell the user: "正在帮您打开微信挂号，稍等一下哦，按提示操作就行~"
