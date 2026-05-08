# High-Risk Events

High-risk events are AI-generated event packages with:

```json
"risk_level": "high"
```

They can place JSON block structures, so the plugin validates them before doing anything in the world.

## Spawn Safety Zone

High-risk events must target a location at least 500 horizontal blocks from the world's spawn location.

The plugin uses X/Z distance only:

```text
distance = sqrt((target_x - spawn_x)^2 + (target_z - spawn_z)^2)
```

Y height is ignored. This protects the spawn hub, starter base, storage rooms, farms, roads, and other shared infrastructure from generated dangerous content.

Default config:

```yaml
highRiskMinSpawnDistance: 500
highRiskMaxSpawnDistance: 5000
allowHighRiskBeyondMaxSpawnDistance: false
requireApprovalForHighRisk: true
requireRollbackForHighRisk: true
maxHighRiskStructureBlocks: 5000
```

If a high-risk event is too close, the plugin refuses it:

```text
Denied: high-risk events must be at least 500 blocks from spawn.
```

If a high-risk event is farther than `highRiskMaxSpawnDistance`, the plugin refuses it unless `allowHighRiskBeyondMaxSpawnDistance` is set to `true`.

## Operator Flow

After the laptop generates or updates event packages:

```text
/aidm reloadevents
/aidm listevents
/aidm eventinfo <event_id>
/aidm previewevent <event_id>
```

For high-risk events, approve before starting:

```text
/aidm approveevent <event_id>
/aidm startevent <event_id>
```

To remove approval:

```text
/aidm cancellevent <event_id>
```

To restore blocks from the rollback snapshot:

```text
/aidm rollbackevent <event_id>
```

Approvals are stored in:

```text
data/approvals/approved_events.json
```

Rollback snapshots are stored in:

```text
data/rollbacks/<event_id>.json
```

## Structure Safety

The plugin only supports structure packages with:

```json
"type": "json_blocks"
```

For JSON block structures, the plugin:

- Validates every material against Bukkit `Material`.
- Rejects malformed JSON.
- Rejects high-risk structures over `maxHighRiskStructureBlocks`.
- Refuses to overwrite protected blocks.
- Saves a rollback snapshot before high-risk placement.

Protected blocks include chests, barrels, shulker boxes, furnaces, beacons, bedrock, and command blocks.

## Example Event Package

Save real event packages as `.json` files under `content/events/`.

```json
{
  "id": "event_001_sunken_cathedral",
  "title": "The Sunken Cathedral",
  "story_arc": "arc_first_bell",
  "description": "A dangerous AI-generated story event.",
  "risk_level": "high",
  "requires_approval": true,
  "location": {
    "mode": "fixed",
    "world": "world",
    "x": 700,
    "y": 64,
    "z": 0,
    "radius_min": 500,
    "radius_max": 2000
  },
  "structure": {
    "type": "json_blocks",
    "structure_id": "sunken_cathedral"
  },
  "lore_books": ["book_id_here"],
  "tags": ["high_risk", "boss", "structure"]
}
```

## Example Structure

Save referenced structures as `.json` files under `content/structures/`.

```json
{
  "id": "sunken_cathedral",
  "blocks": [
    {
      "dx": 0,
      "dy": 0,
      "dz": 0,
      "material": "STONE_BRICKS"
    },
    {
      "dx": 1,
      "dy": 0,
      "dz": 0,
      "material": "CRACKED_STONE_BRICKS"
    }
  ]
}
```

Each block coordinate is relative to the event target location.
