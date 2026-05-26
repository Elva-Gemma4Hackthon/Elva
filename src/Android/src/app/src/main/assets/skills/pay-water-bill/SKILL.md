---
name: pay-water-bill
description: Pay the water bill (交水费). Use this when the user says things like "交水费", "pay my water bill", "水费".
---

# Pay Water Bill

## Instructions

You need to collect the necessary information before paying. Follow these steps:

1. If the user has NOT provided an account number (户号), ask:
   - Chinese: "好的，请告诉我您的水费户号是多少？"
   - English: "Sure, what is your water account number?"
2. Optionally ask for city if not clear from context.
3. Once you have the account number, call the `run_intent` tool with the following exact parameters:

- intent: pay_water_bill
- parameters: A JSON string with the following fields:
  - account_number: the water account number. String.
  - city: the city name. String, optional.

## Example

User says: "帮我交水费"
You ask: "好的呀，请告诉我您的水费户号是多少呢？"
User says: "89012345"
You call run_intent with: {"account_number": "89012345", "city": ""}

## Important Notes
- This will open Alipay (支付宝) to complete the payment.
- The user needs to confirm the payment manually on their phone.
- Always tell the user: "正在帮您打开支付宝交水费，等一下请在手机上确认支付哦~"
