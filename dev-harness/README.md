# dev-harness

Core's pipeline in isolation. **Never published, never in the bundle jar.**

Registers four synthetic objects that wander a superflat world, through the same
`SceneObjectProvider` seam a real addon uses. No third-party mods involved at all, which is
the point: if something is broken here it is core's fault, not a target mod's API.

For a real test with actual ComputerCraft turtles running actual Lua, use
[`:addon-turtles:runServer`](../addon-turtles/devserver/README.md) instead.

## Running it

```bash
./gradlew :dev-harness:prepareDevServer -Paccept_licences
./gradlew :dev-harness:runServer
```

Then open <http://localhost:8100>.

BlueMap is found automatically in your Modrinth profile; otherwise pass
`-Pbluemap_jar=/path/to/bluemap-5.7-neoforge.jar`.

`-Paccept_licences` writes `eula=true` and BlueMap's `accept-download: true` - the latter
lets BlueMap fetch the vanilla client jar, which is what gives you both terrain tiles and
textured 3D objects. Drop the flag and the task prints the two lines to edit by hand.

## What to look for

| Check | What it proves |
|---|---|
| Four objects visible at all | Script injection, scene access, mesh format, atlas |
| They move **smoothly**, not in 1-second steps | Client-side interpolation |
| They turn gradually rather than snapping | Quaternion slerp, and rotation streamed not baked |
| They are textured, not flat colour | Resource-pack model and texture reading |
| Ones behind terrain are hidden by it | The marker pass inherits the terrain depth buffer |
| They vanish when you switch to the Nether map | Dimension filtering via the server's map mapping |

Without step 2 above the terrain is blank and objects are flat map-colour cubes. Everything
else in that table still applies, so the pipeline is testable either way.

They move continuously at about 1.5 blocks a second rather than hopping between blocks. That
is on purpose: block-snapping would look correct even with interpolation completely broken,
and catching that is the point of the test.
