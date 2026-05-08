# Minecraft AI D&D SMP MVP

This repo is a safe first version of an AI-powered Minecraft D&D SMP system.

The Paper server observes meaningful gameplay events and writes them to JSONL. A Python AI Dungeon Master reads those logs and generates structured lore book JSON. The Paper plugin can then load those JSON files and give written books in-game through operator commands.

## AI Containment Layer

The AI does not directly modify the live Minecraft server in this MVP.

The only AI output the plugin trusts is structured JSON under:

```text
content/lore_books/
```

The plugin turns that JSON into Minecraft written books. It does not execute AI-generated code, run AI-generated commands, or let the Python script touch the live world.

## Project Layout

```text
plugin/                 Paper plugin source
ai_dm/                  Python lore generator
data/events/            JSONL gameplay event logs
content/lore_books/     Generated book JSON loaded by the plugin
content/events/         Generated event package JSON loaded by the plugin
content/structures/     JSON block structures referenced by event packages
scripts/                Windows workflow helpers
docs/                   Setup and workflow notes
```

## Build The Plugin

Install Java 21 and Gradle, then run:

```bat
cd plugin
gradle build
```

The jar is created under:

```text
plugin/build/libs/
```

If you later add a Gradle wrapper, use `gradlew build` instead.

## Install On Paper

1. Copy the built plugin jar into your Paper server `plugins/` folder.
2. Start or restart the Paper server.
3. Run this in the server console or in-game as an operator:

```text
/aidm status
```

By default, the plugin logs to:

```text
data/events/player_events.jsonl
```

Chat logging is off by default. You can enable it in `plugins/AIDungeonMasterObserver/config.yml`, but only do that if players know chat may be logged.

## Plugin Commands

```text
/aidm status
/aidm reloadcontent
/aidm listbooks
/aidm givebook <player> <book_id>
/aidm recent
/aidm reloadevents
/aidm listevents
/aidm eventinfo <event_id>
/aidm previewevent <event_id>
/aidm approveevent <event_id>
/aidm startevent <event_id>
/aidm cancellevent <event_id>
/aidm rollbackevent <event_id>
```

## High-Risk Event Safety

High-risk event packages can place generated structure content, so they are fenced off from spawn.

The plugin measures horizontal X/Z distance from the target location to the world's spawn. Y height does not count. By default, a high-risk event must be at least 500 blocks from spawn and no more than 5000 blocks from spawn.

If a high-risk event is too close, `/aidm startevent` refuses it with:

```text
Denied: high-risk events must be at least 500 blocks from spawn.
```

High-risk events also require approval and rollback snapshots by default. The safety config is in `plugins/AIDungeonMasterObserver/config.yml`:

```yaml
highRiskMinSpawnDistance: 500
highRiskMaxSpawnDistance: 5000
allowHighRiskBeyondMaxSpawnDistance: false
requireApprovalForHighRisk: true
requireRollbackForHighRisk: true
maxHighRiskStructureBlocks: 5000
```

Use this flow:

```text
/aidm reloadevents
/aidm previewevent <event_id>
/aidm approveevent <event_id>
/aidm startevent <event_id>
/aidm rollbackevent <event_id>
```

The 500-block spawn safety zone exists so AI-generated high-risk structures cannot accidentally damage the main base, spawn hub, starter farms, player storage, or other shared infrastructure.

## Run The Python Lore Generator

Mock mode needs no API key:

```bat
python ai_dm\run_dm.py --mode mock
```

Mock mode can also generate a sample high-risk event package and a tiny JSON block structure:

```bat
python ai_dm\run_dm.py --mode mock --generate-high-risk-event
```

Optional LLM mode:

```bat
python -m pip install openai
set OPENAI_API_KEY=your_key_here
python ai_dm\run_dm.py --mode llm
```

Do not hardcode or commit API keys.

## GitHub Push/Pull Workflow

On the HP server, push fresh logs:

```bat
scripts\server_push_logs.bat
```

On the laptop, pull logs, generate lore, and push books:

```bat
scripts\laptop_run_dm.bat
```

Back on the HP server, pull generated content:

```bat
scripts\server_pull_updates.bat
```

Then in Minecraft:

```text
/aidm reloadcontent
/aidm listbooks
```

More detail is in [docs/GITHUB_WORKFLOW.md](docs/GITHUB_WORKFLOW.md).
High-risk event details are in [docs/HIGH_RISK_EVENTS.md](docs/HIGH_RISK_EVENTS.md).

## First Successful Test

1. Start the Paper server with the plugin installed.
2. Join the server as `Hazel`.
3. Die, enter a new biome, or place/break a notable block such as an enchanting table, beacon, diamond ore, emerald ore, ancient debris, spawner, amethyst block, or obsidian.
4. Confirm an event appears in `data/events/player_events.jsonl`.
5. Run:

```bat
python ai_dm\run_dm.py --mode mock
```

6. Confirm a lore book JSON file appears in `content/lore_books`.
7. In Minecraft, run:

```text
/aidm reloadcontent
/aidm listbooks
/aidm givebook Hazel <book_id>
```

To test a generated event package:

```text
/aidm reloadevents
/aidm listevents
/aidm previewevent <event_id>
/aidm approveevent <event_id>
/aidm startevent <event_id>
/aidm rollbackevent <event_id>
```

## Backups

World folders are intentionally ignored by Git. Use this local helper before risky server changes:

```bat
scripts\backup_world.bat
```

It copies `world/`, `world_nether/`, and `world_the_end/` into a timestamped folder under `backups/`.

## Current Limitations

- No database.
- No Docker.
- No web dashboard.
- No automatic event log rotation.
- No automated review queue yet.
- The Java plugin uses a hardcoded notable block list for now.
- LLM mode is optional and depends on the `openai` Python package.
- Structure support is intentionally limited to known `json_blocks` files.
