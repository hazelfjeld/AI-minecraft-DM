"""Generate safe Minecraft lore book JSON from observed gameplay events.

This script is intentionally limited: it reads JSONL event logs and writes
structured JSON files. It does not connect to the live server, edit worlds, or
generate Java plugin code.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_EVENTS_PATH = REPO_ROOT / "data" / "events" / "player_events.jsonl"
DEFAULT_OUTPUT_DIR = REPO_ROOT / "content" / "lore_books"
DEFAULT_EVENT_OUTPUT_DIR = REPO_ROOT / "content" / "events"
DEFAULT_STRUCTURE_OUTPUT_DIR = REPO_ROOT / "content" / "structures"
DEFAULT_PROMPT_PATH = REPO_ROOT / "ai_dm" / "prompts" / "lore_prompt.txt"


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate AI DM lore book JSON from Minecraft event logs.")
    parser.add_argument("--mode", choices=["mock", "llm"], default="mock", help="mock needs no API key; llm uses OPENAI_API_KEY.")
    parser.add_argument("--events", type=Path, default=DEFAULT_EVENTS_PATH, help="Path to player_events.jsonl.")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_DIR, help="Directory for lore book JSON files.")
    parser.add_argument("--events-output", type=Path, default=DEFAULT_EVENT_OUTPUT_DIR, help="Directory for event package JSON files.")
    parser.add_argument("--structures-output", type=Path, default=DEFAULT_STRUCTURE_OUTPUT_DIR, help="Directory for structure JSON files.")
    parser.add_argument("--prompt", type=Path, default=DEFAULT_PROMPT_PATH, help="Prompt template used by llm mode.")
    parser.add_argument("--limit", type=int, default=50, help="How many recent events to read.")
    parser.add_argument("--count", type=int, default=2, help="Number of books to request, from 1 to 3.")
    parser.add_argument("--generate-high-risk-event", action="store_true", help="Also write one safe high-risk event package and JSON block structure.")
    parser.add_argument("--force", action="store_true", help="Overwrite existing lore JSON files with the same id.")
    args = parser.parse_args()

    requested_count = max(1, min(args.count, 3))
    recent_events = read_recent_events(args.events, args.limit)
    if not recent_events:
        if args.generate_high_risk_event:
            print(f"No events found at {args.events}. Generating high-risk event from empty context.")
            write_mock_high_risk_event(
                recent_events=[],
                event_output_dir=args.events_output,
                structure_output_dir=args.structures_output,
                force=args.force,
            )
        else:
            print(f"No events found at {args.events}. Join the server or trigger a notable event first.")
        return 0

    if args.mode == "mock":
        lore_books = generate_mock_lore_books(recent_events, requested_count)
    else:
        lore_books = generate_llm_lore_books(recent_events, requested_count, args.prompt)

    if not lore_books:
        print("No lore books were generated.")
        return 0

    args.output.mkdir(parents=True, exist_ok=True)
    written_count = 0
    skipped_count = 0
    for lore_book in lore_books[:3]:
        normalized_book = normalize_lore_book(lore_book, recent_events)
        if write_lore_book(normalized_book, args.output, args.force):
            written_count += 1
        else:
            skipped_count += 1

    if args.generate_high_risk_event:
        write_mock_high_risk_event(
            recent_events=recent_events,
            event_output_dir=args.events_output,
            structure_output_dir=args.structures_output,
            force=args.force,
        )

    print(f"Done. Wrote {written_count} lore book(s), skipped {skipped_count} existing file(s).")
    return 0


def read_recent_events(path: Path, limit: int) -> list[dict[str, Any]]:
    """Read the newest valid JSON objects from a JSONL file."""
    if not path.exists():
        return []

    events: list[dict[str, Any]] = []
    # utf-8-sig accepts normal UTF-8 and also Windows-created UTF-8 files with a BOM.
    with path.open("r", encoding="utf-8-sig") as event_file:
        for line_number, line in enumerate(event_file, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError as exc:
                print(f"Skipping invalid JSONL line {line_number}: {exc}", file=sys.stderr)
                continue
            if isinstance(event, dict):
                events.append(event)

    return events[-max(1, limit):]


def generate_mock_lore_books(events: list[dict[str, Any]], requested_count: int) -> list[dict[str, Any]]:
    """Create deterministic lore from templates so the MVP works offline."""
    players = sorted({str(event.get("player", "Someone")) for event in events if event.get("player")})
    event_types = Counter(str(event.get("type", "unknown")) for event in events)
    notable_materials = [
        str(event.get("details", {}).get("material"))
        for event in events
        if isinstance(event.get("details"), dict) and event.get("details", {}).get("material")
    ]

    books: list[dict[str, Any]] = []

    if any(event_type in event_types for event_type in ["notable_block_place", "notable_block_break"]):
        material = most_common_text(notable_materials, fallback="stone").replace("_", " ")
        books.append(build_book(
            theme="notable_block",
            title=title_case(f"The {material} omen"),
            pages=[
                f"The world remembers when {name_list(players)} touched {material}.",
                "A quiet mark has been pressed into the deep places.",
                "If the sign is answered, the next page will not stay blank.",
            ],
            tags=["notable_block", slugify(material), "omen"],
            source_events=source_event_ids(events, ["notable_block_place", "notable_block_break"]),
        ))

    if event_types["player_death"] > 0:
        death_events = [event for event in events if event.get("type") == "player_death"]
        last_death = death_events[-1]
        player = str(last_death.get("player", "Someone"))
        message = str(last_death.get("details", {}).get("message", "death came without a witness"))
        books.append(build_book(
            theme="death",
            title=f"The Bell That Named {player}",
            pages=[
                f"The bell rang once for {player}.",
                message,
                "A debt has entered the ledger of the world.",
            ],
            tags=["death", slugify(player), "warning"],
            source_events=source_event_ids(death_events, ["player_death"]),
        ))

    if event_types["biome_enter"] > 0:
        biome_events = [event for event in events if event.get("type") == "biome_enter"]
        last_biome = biome_events[-1]
        player = str(last_biome.get("player", "Someone"))
        details = last_biome.get("details", {})
        to_biome = "unknown wilds"
        if isinstance(details, dict):
            to_biome = str(details.get("to_biome", to_biome)).replace("minecraft:", "").replace("_", " ")
        books.append(build_book(
            theme="journey",
            title=title_case(f"Footsteps in the {to_biome}"),
            pages=[
                f"{player} crossed into the {to_biome}, and the horizon noticed.",
                "Old paths wake when new names are carried across them.",
                "There may be a door here, but it will not look like one.",
            ],
            tags=["biome", slugify(to_biome), "journey"],
            source_events=source_event_ids(biome_events, ["biome_enter"]),
        ))

    if not books:
        books.append(build_book(
            theme="arrival",
            title="The First Names",
            pages=[
                f"The first names written in this age are {name_list(players)}.",
                "The world has begun keeping score.",
                "Small deeds become roads when someone remembers them.",
            ],
            tags=["arrival", "memory"],
            source_events=source_event_ids(events),
        ))

    # Fill up to the requested count with a general chronicle if needed.
    if len(books) < requested_count:
        books.append(build_book(
            theme="chronicle",
            title="A Page From the Waking World",
            pages=[
                summarize_events_for_page(events),
                "The pattern is still faint, but it is no longer silent.",
                "Return with proof, and the world may answer in kind.",
            ],
            tags=["chronicle", "world"],
            source_events=source_event_ids(events),
        ))

    return books[:requested_count]


def generate_llm_lore_books(events: list[dict[str, Any]], requested_count: int, prompt_path: Path) -> list[dict[str, Any]]:
    """Ask an OpenAI model for lore JSON, keeping the dependency optional."""
    if not os.environ.get("OPENAI_API_KEY"):
        raise SystemExit("OPENAI_API_KEY is not set. Use --mode mock or set the environment variable.")

    try:
        from openai import OpenAI
    except ImportError as exc:
        raise SystemExit("The openai package is not installed. Run: python -m pip install openai") from exc

    prompt_template = prompt_path.read_text(encoding="utf-8") if prompt_path.exists() else default_llm_prompt()
    prompt = prompt_template.format(
        count=requested_count,
        events_json=json.dumps(events, indent=2, ensure_ascii=False),
    )

    client = OpenAI()
    model = os.environ.get("OPENAI_MODEL", "gpt-4.1-mini")
    response = client.responses.create(
        model=model,
        input=prompt,
    )
    response_text = response.output_text.strip()
    parsed = parse_json_from_text(response_text)
    if isinstance(parsed, dict):
        parsed = parsed.get("books", [])
    if not isinstance(parsed, list):
        raise SystemExit("LLM response did not contain a JSON array of books.")

    return [book for book in parsed if isinstance(book, dict)]


def parse_json_from_text(text: str) -> Any:
    """Parse plain JSON or JSON wrapped in a Markdown code fence."""
    fenced_match = re.search(r"```(?:json)?\s*(.*?)```", text, flags=re.DOTALL | re.IGNORECASE)
    if fenced_match:
        text = fenced_match.group(1)
    return json.loads(text)


def normalize_lore_book(book: dict[str, Any], events: list[dict[str, Any]]) -> dict[str, Any]:
    """Clamp generated content into the schema the plugin expects."""
    title = clean_text(str(book.get("title", "Untitled Lore")))
    author = clean_text(str(book.get("author", "The World")))
    pages = book.get("pages", [])
    if not isinstance(pages, list):
        pages = [str(pages)]
    safe_pages = [clean_text(str(page))[:1024] for page in pages if str(page).strip()]
    if not safe_pages:
        safe_pages = ["The page is quiet, but not empty."]

    tags = book.get("tags", [])
    if not isinstance(tags, list):
        tags = []
    safe_tags = [slugify(str(tag)) for tag in tags if str(tag).strip()]

    source_events = book.get("source_events", [])
    if not isinstance(source_events, list):
        source_events = []
    if not source_events:
        source_events = source_event_ids(events)

    book_id = str(book.get("id") or "")
    if not book_id:
        book_id = make_book_id(str(book.get("theme", "lore")), title, source_events)

    return {
        "id": slugify(book_id)[:80],
        "title": title[:80],
        "author": author[:80],
        "pages": safe_pages[:100],
        "tags": safe_tags[:12],
        "source_events": [str(source_event) for source_event in source_events[:20]],
    }


def write_lore_book(book: dict[str, Any], output_dir: Path, force: bool) -> bool:
    output_path = output_dir / f"{book['id']}.json"
    if output_path.exists() and not force:
        print(f"Skipping existing lore book: {output_path}")
        return False

    with output_path.open("w", encoding="utf-8") as output_file:
        json.dump(book, output_file, indent=2, ensure_ascii=False)
        output_file.write("\n")
    print(f"Wrote {output_path}")
    return True


def write_mock_high_risk_event(
    recent_events: list[dict[str, Any]],
    event_output_dir: Path,
    structure_output_dir: Path,
    force: bool,
) -> None:
    """Write a deterministic high-risk event package plus a tiny safe structure."""
    players = sorted({str(event.get("player", "Someone")) for event in recent_events if event.get("player")})
    source_events = source_event_ids(recent_events)
    fingerprint = hashlib.sha1("|".join(source_events).encode("utf-8")).hexdigest()[:8]
    event_id = f"event_{datetime.now(timezone.utc).strftime('%Y%m%d')}_sunken_cathedral_{fingerprint}"
    structure_id = f"{event_id}_structure"

    event_package = {
        "id": event_id,
        "title": "The Sunken Cathedral",
        "story_arc": "arc_first_bell",
        "description": f"A dangerous story event awakened by {name_list(players)}.",
        "risk_level": "high",
        "requires_approval": True,
        "location": {
            "mode": "fixed",
            "world": "world",
            "x": 700,
            "y": 64,
            "z": 0,
            "radius_min": 500,
            "radius_max": 2000,
        },
        "structure": {
            "type": "json_blocks",
            "structure_id": structure_id,
        },
        "lore_books": [],
        "tags": ["high_risk", "structure", "sunken_cathedral"],
    }

    structure = {
        "id": structure_id,
        "blocks": [
            {"dx": 0, "dy": 0, "dz": 0, "material": "STONE_BRICKS"},
            {"dx": 1, "dy": 0, "dz": 0, "material": "CRACKED_STONE_BRICKS"},
            {"dx": -1, "dy": 0, "dz": 0, "material": "MOSSY_STONE_BRICKS"},
            {"dx": 0, "dy": 1, "dz": 0, "material": "LANTERN"},
            {"dx": 0, "dy": 0, "dz": 1, "material": "CHISELED_STONE_BRICKS"},
        ],
    }

    event_output_dir.mkdir(parents=True, exist_ok=True)
    structure_output_dir.mkdir(parents=True, exist_ok=True)
    write_json_file(event_output_dir / f"{event_id}.json", event_package, force)
    write_json_file(structure_output_dir / f"{structure_id}.json", structure, force)


def write_json_file(path: Path, data: dict[str, Any], force: bool) -> bool:
    if path.exists() and not force:
        print(f"Skipping existing file: {path}")
        return False

    with path.open("w", encoding="utf-8") as output_file:
        json.dump(data, output_file, indent=2, ensure_ascii=False)
        output_file.write("\n")
    print(f"Wrote {path}")
    return True


def build_book(theme: str, title: str, pages: list[str], tags: list[str], source_events: list[str]) -> dict[str, Any]:
    return {
        "id": make_book_id(theme, title, source_events),
        "title": title,
        "author": "The World",
        "pages": pages,
        "tags": tags,
        "source_events": source_events,
    }


def make_book_id(theme: str, title: str, source_events: list[str]) -> str:
    date_part = datetime.now(timezone.utc).strftime("%Y%m%d")
    fingerprint_source = "|".join(source_events) or title
    fingerprint = hashlib.sha1(fingerprint_source.encode("utf-8")).hexdigest()[:8]
    return slugify(f"day_{date_part}_{theme}_{title}_{fingerprint}")


def source_event_ids(events: list[dict[str, Any]], event_types: list[str] | None = None) -> list[str]:
    selected_events = events
    if event_types is not None:
        selected_events = [event for event in events if event.get("type") in event_types]

    ids: list[str] = []
    for event in selected_events[-10:]:
        event_id = event.get("id") or event.get("timestamp")
        if event_id:
            ids.append(str(event_id))
    return ids


def summarize_events_for_page(events: list[dict[str, Any]]) -> str:
    event_bits: list[str] = []
    for event in events[-5:]:
        player = str(event.get("player", "Someone"))
        event_type = str(event.get("type", "acted")).replace("_", " ")
        event_bits.append(f"{player}: {event_type}")
    return "Recent signs: " + "; ".join(event_bits) + "."


def most_common_text(values: list[str], fallback: str) -> str:
    cleaned_values = [value for value in values if value and value != "None"]
    if not cleaned_values:
        return fallback
    return Counter(cleaned_values).most_common(1)[0][0]


def name_list(names: list[str]) -> str:
    if not names:
        return "the unnamed"
    if len(names) == 1:
        return names[0]
    return ", ".join(names[:-1]) + " and " + names[-1]


def title_case(value: str) -> str:
    return " ".join(word[:1].upper() + word[1:] for word in value.split())


def clean_text(value: str) -> str:
    # Minecraft books handle plain text reliably. Keep generated files simple.
    return re.sub(r"\s+", " ", value).strip()


def slugify(value: str) -> str:
    value = value.lower().strip()
    value = re.sub(r"[^a-z0-9]+", "_", value)
    value = re.sub(r"_+", "_", value).strip("_")
    return value or "lore"


def default_llm_prompt() -> str:
    return (
        "You are generating safe Minecraft D&D lore books.\n"
        "Return only JSON: an array of {count} objects with id, title, author, pages, tags, source_events.\n"
        "Do not suggest server commands, code, plugins, shell commands, or direct world edits.\n"
        "High-risk events must be placed at least 500 blocks away from spawn. "
        "The plugin will reject invalid locations.\n"
        "Base the lore only on these events:\n{events_json}\n"
    )


if __name__ == "__main__":
    raise SystemExit(main())
