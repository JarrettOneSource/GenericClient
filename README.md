# GenericClient

GenericClient is a RuneLite plugin that displays client status, logs nearby NPC
data, and uses the native mouse to click a random nearby ground tile.

## Run

```bash
./gradlew run
```

This launches the current stock RuneLite release and loads `GenericClientPlugin`
through `ExternalPluginManager.loadBuiltin`.

After logging in, open the GenericClient sidebar:

- **Print diagnostics** logs the RuneLite version, game revision, game state,
  player location, classloader, thread, tick count, and uptime.
- **Log nearby NPCs** logs each nearby NPC's name, ID, index, location,
  distance, combat level, animation, interaction target, and actions.
- **Walk to random tile** moves the native cursor to a nearby ground tile,
  executes a left click when the selected action is `Walk here`, records the
  resulting `MenuOptionClicked`, and logs a fresh NPC snapshot.

Diagnostics are written to:

- the terminal running RuneLite;
- `~/.runelite/logs/client.log`;
- the GenericClient sidebar.

Log lines use the `[GenericClient]` prefix.

## Build

```bash
./gradlew clean jar shadowJar
```

Artifacts:

- `build/libs/generic-client-0.1.0.jar`
- `build/libs/GenericClient-0.1.0-all.jar`

Run the standalone artifact with:

```bash
java -ea -jar build/libs/GenericClient-0.1.0-all.jar
```
