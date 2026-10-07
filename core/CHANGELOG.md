# v1.1.0

## NEW

- addons can now describe blocks whose look lives in their block entity rather than their block state, through `BlockAppearanceResolver`. core draws the result with the real textures of the materials the block is dressed in.
- `BlockVolume.region` reads those block entities itself, so a ship or any other level-backed volume gets them with no extra work from the addon.
- `BlockVolume.of` takes per-block appearances, for addons that hold their block entities as saved tags.

## CHANGED

- `BlockVolume` has a new `appearanceAt`, which is empty by default, so existing volumes and providers behave exactly as before.

## FIXED

- none.

## REMOVED

- none.
