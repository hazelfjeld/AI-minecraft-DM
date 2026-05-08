# GitHub Workflow

The repo is the handoff layer between the HP server and the laptop.

The server writes observed gameplay to JSONL:

```text
data/events/player_events.jsonl
```

The laptop reads those logs and writes safe generated content:

```text
content/lore_books/*.json
content/events/*.json
content/structures/*.json
```

The AI does not directly touch the live server or write plugin code in this MVP.

## HP Server Loop

After a play session or on a scheduled task:

```bat
scripts\server_push_logs.bat
```

This stages `data/events/*.jsonl`, commits if there are changes, and pushes.

Before using newly generated lore:

```bat
scripts\server_pull_updates.bat
```

Then in Minecraft:

```text
/aidm reloadcontent
/aidm reloadevents
/aidm listbooks
/aidm listevents
```

## Laptop AI Loop

On the stronger laptop:

```bat
scripts\laptop_run_dm.bat
```

This pulls latest server logs, runs:

```bat
python ai_dm\run_dm.py --mode mock
```

Then it commits and pushes any new generated JSON content.
If you pass `--generate-high-risk-event`, it also writes `content/events/*.json` and `content/structures/*.json`.

## Optional LLM Mode

Install the optional OpenAI Python package:

```bat
python -m pip install openai
```

Set your API key in your shell or user environment. Do not commit it.

```bat
set OPENAI_API_KEY=your_key_here
python ai_dm\run_dm.py --mode llm
```

You can also set `OPENAI_MODEL` if you want to choose a specific model.

## Scheduling Later

For the MVP, run the scripts manually first. Once the loop works, Windows Task Scheduler can run:

- `scripts\server_push_logs.bat` on the HP server every 10-30 minutes.
- `scripts\server_pull_updates.bat` on the HP server when you want new content.
- `scripts\laptop_run_dm.bat` on the laptop after logs are pushed.
