# v1.1.0

## NEW

- addons can now describe blocks whose look lives in their block entity rather than their block state, through `BlockAppearanceResolver`. core draws the result with the real textures of the materials the block is dressed in.
- `BlockVolume.region` reads those block entities itself, so a ship or any other level-backed volume gets them with no extra work from the addon.
- `BlockVolume.of` takes per-block appearances, for addons that hold their block entities as saved tags.
- an appearance can be made of free faces - slopes, triangles, rotated bars - not just boxes, with the texture read from the flat face a bent one came from.
- an appearance can spin. it becomes its own animated node, sharing `maxSpinNodesPerObject` with animated attachments, which keep their share first.
- `BlockAppearanceResolver.resolve` has a live block entity form, for appearances that depend on state the saved tag does not carry.

## CHANGED

- `BlockVolume` has a new `appearanceAt`, which is empty by default, so existing volumes and providers behave exactly as before.

## FIXED

- none.

## REMOVED

- none.
