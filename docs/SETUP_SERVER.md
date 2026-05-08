# Server Setup

This MVP assumes the Paper server folder is also the Git checkout folder, or that the checkout is copied into the server folder. The plugin defaults to these paths relative to the server root:

- `data/events/player_events.jsonl`
- `content/lore_books`

## 1. Install Paper

1. Install Java 21 on the HP mini PC.
2. Download a Paper server jar for Minecraft 1.21.x.
3. Place the Paper jar in the server folder.
4. Run it once, accept the EULA, and start the server normally.

Do not commit `world/`, `world_nether/`, `world_the_end/`, `server.properties`, `eula.txt`, downloaded Paper jars, or runtime logs.

## 2. Build The Plugin

From the repo root:

```bat
cd plugin
gradle build
```

If you use the Gradle wrapper later, the command becomes:

```bat
cd plugin
gradlew build
```

The plugin jar will be created under `plugin/build/libs/`.

## 3. Install The Plugin

1. Copy the built jar from `plugin/build/libs/` into the Paper server `plugins/` folder.
2. Start or restart the Paper server.
3. Confirm the plugin loads in the server console.
4. In-game, run:

```text
/aidm status
```

## 4. Optional Config

After first startup, Paper will create:

```text
plugins/AIDungeonMasterObserver/config.yml
```

Default chat logging is off:

```yaml
log-chat: false
```

Only set it to `true` if players understand chat may be written to Git-tracked logs.
