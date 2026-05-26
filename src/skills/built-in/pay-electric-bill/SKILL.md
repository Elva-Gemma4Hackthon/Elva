---
name: pay-electric-bill
description: Pay the electric bill (交电费). Use this when the user says things like "交电费", "pay my electric bill", "电费", "electricity bill".
---

# Pay Electric Bill

## Instructions

You need to collect the necessary information before paying. Follow these steps:

1. If the user has NOT provided an account number (户号), ask:
   - Chinese: "好的，请告诉我您的电费户号是多少？"
   - English: "Sure, what is your electricity account number?"
2. Optionally ask for city if not clear from context.
3. Once you have the account number, call the `run_intent` tool with the following exact parameters:

- intent: pay_electric_bill
- parameters: A JSON string with the following fields:
  - account_number: the electricity account number. String.
  - city: the city name. String, optional.

## Example

User says: "帮我交电费"
You ask: "好的呀，请告诉我您的电费户号是多少呢？"
User says: "320123456"
You call run_intent with: {"account_number": "320123456", "city": ""}

## Important Notes
- This will open Alipay (支付宝) to complete the payment.
- The user needs to confirm the payment manually on their phone.
- Always tell the user: "正在帮您打开支付宝交电费，等一下请在手机上确认支付哦~"
