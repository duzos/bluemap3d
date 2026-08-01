# addon-turtles dev server

A real end-to-end test: actual ComputerCraft turtles, actually running Lua, found through
the same code path that runs in production. No mock objects.

```bash
./gradlew :addon-turtles:prepareDevServer -Paccept_licences
./gradlew :addon-turtles:runServer
```

Then open <http://localhost:8100>.

`-Paccept_licences` writes `eula=true` and BlueMap's `accept-download: true`. Drop it and
the task tells you which two lines to edit instead.

## What's in here

Everything under `devserver/` is copied verbatim over `run/server`.

| Path | Why |
|---|---|
| `config/computercraft-server.toml` | `need_fuel = false`, so the turtles wander indefinitely without being fed coal |
| `world/datapacks/bluemap3d_test/` | Places four turtles at spawn and boots them |
| `world/computercraft/computer/{0..3}/startup.lua` | The Lua each turtle runs: label itself, then random-walk |

CC:Tweaked itself comes from maven, declared in `build.gradle`:

```groovy
devMods "cc.tweaked:cc-tweaked-1.21.1-forge:1.120.0"
```

(CC publishes its NeoForge artifacts under the `forge` name on 1.21.1.)

## How the turtles get going

Three things have to happen in order, which is why it is a datapack rather than one
function:

1. **`init`** runs on world load. It `setblock`s four turtles at y=-60 - default 1.21
   superflat is bedrock at -64, two dirt, grass at -61, so that is the walkable surface.
   `keep` makes it idempotent, so a `/reload` does not wipe the turtles and orphan their
   computer ids.
2. **`tick`** counts to 40.
3. **`boot`** runs `computercraft turn-on`. A freshly placed turtle is off, and
   `startup.lua` only runs on boot. This is deferred rather than done in `init` because the
   block entities need to exist and have been assigned computer ids first, which does not
   happen inside the tick that places them.

Each turtle's `startup.lua` calls `os.setComputerLabel(...)` before moving. That is
deliberate: the addon reads the label off the block entity and publishes it, so a label
showing up in the browser proves that path rather than just assuming it.

If the turtles sit still, `computercraft turn-on` did not take - run it in the server
console. Nothing else depends on it.

## What to look for

| Check | What it proves |
|---|---|
| Four turtles visible | Script injection, scene access, mesh format, atlas |
| They look like turtles, not grey cubes | `computercraft:block/turtle_normal` resolved through its parent chain, with per-face `#front`/`#top`/`#backpack` textures |
| Movement is smooth, not 1-second steps | Client-side interpolation between published samples |
| Turning is gradual, not snapping between four models | Rotation is streamed as a quaternion, not baked per facing - so one mesh serves all four |
| Labels show | Label read from block entity data, no CC classes involved |
| Ones behind terrain are hidden | The marker render pass inherits the terrain depth buffer |
