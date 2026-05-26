---
name: show-photos
description: Open the photo gallery to view photos. Use this when the user says things like "show me photos", "看看照片", "看看孙女", "show me my granddaughter's photos", "看相册", etc.
---

# Show Photos

## Instructions

Call the `run_intent` tool with the following exact parameters:

- intent: open_photos
- parameters: A JSON string with the following fields:
  - album_name: optional. The name of a specific album to open (e.g., "granddaughter", "孙女"). If not specified, pass null or omit. String.

## Response

After calling the tool, respond warmly. Examples:
- Chinese: "好的，帮您打开相册啦，慢慢看哦~"
- English: "Here you go! I've opened your photo gallery. Enjoy looking at your photos!"
