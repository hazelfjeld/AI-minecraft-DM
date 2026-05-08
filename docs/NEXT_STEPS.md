# Next Steps

Keep the containment layer until the MVP is boring and reliable.

## Good Near-Term Additions

- Add a plugin config list for notable blocks instead of hardcoding them.
- Add event rotation so `player_events.jsonl` does not grow forever.
- Add a simple story memory file summarizing accepted lore.
- Add `/aidm giveall <book_id>` for sessions where everyone should receive the same book.
- Add tests for Python lore generation.
- Add a scheduled Windows Task Scheduler setup guide.
- Add a manual review folder, such as `content/review_queue`, before books become available in-game.
- Add richer event package tests on a disposable Paper test world.
- Add a preview-only structure bounding box before placement.

## Bigger Future Features

- Quest JSON loaded by the plugin.
- Boss encounter JSON loaded by the plugin.
- NPC dialogue JSON loaded by the plugin.
- Structure placement requests that require human approval.
- Better structure formats, such as schematic import after strict validation.
- A dashboard for reviewing generated content before it reaches the server.

## Deliberately Out Of Scope For This MVP

- AI-generated Java plugin code.
- Direct AI access to the live Minecraft process.
- Direct AI edits to the world folder.
- A database.
- Docker.
- A web server.
- Autonomous admin commands.
