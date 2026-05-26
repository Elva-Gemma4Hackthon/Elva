---
name: make-phone-call
description: Make a phone call to a family member or contact. Use this when the user says things like "call my son", "给儿子打电话", "call my daughter", "打电话给女儿", "call someone", etc.
---

# Make Phone Call

## Instructions

You need to determine the phone number to call. Follow these steps:

1. If the user provided a specific phone number, use it directly.
2. If the user mentioned a relationship (e.g., "son", "daughter", "儿子", "女儿", "老伴"), but no number:
   - Ask the user: "请问他的电话号码是多少？" or "What is their phone number?"
   - Wait for the user's response, then use that number.
3. Once you have the phone number, call the `run_intent` tool with the following exact parameters:

- intent: make_phone_call
- parameters: A JSON string with the following fields:
  - phone_number: the phone number to call. String. Must be digits only or with + prefix (e.g., "13800138000" or "+8613800138000").

## Example

User says: "给儿子打电话"
You ask: "好的，请问您儿子的电话号码是多少呀？"
User says: "13800138000"
You call run_intent with: {"phone_number": "13800138000"}
