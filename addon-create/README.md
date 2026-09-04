<div align="center">

# BlueMap: Create

### Trains and contraptions moving across your map, in real blocks.

![Mod id](https://img.shields.io/badge/mod%20id-bluemap3d__create-2D6FE0?style=for-the-badge)
![Status](https://img.shields.io/badge/status-working-3FB950?style=for-the-badge)

[<img alt="create" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/requires/create_vector.svg">](https://modrinth.com/mod/create)

</div>

## 🚂 What you get

Every moving Create contraption, drawn with its real blocks and textures and moving in real
time:

| Contraption | What you see |
| --- | --- |
| **Trains** | Each carriage separately, so a train on a curve bends instead of rendering as a plank |
| **Minecart contraptions** | The whole assembly rolling along the rail |
| **Gantry carriages** | Sliding along their shaft |
| **Pistons and pulleys** | Mechanical pistons, rope pulleys, elevator pulleys |
| **Bearings** | Windmills, mechanical bearings, clockwork bearings, turning about their real axis |

Not markers, not a dot sliding along the track. The contraption itself.

## 📦 Install

Drop this plus **BlueMap3D** and [Create](https://modrinth.com/mod/create) into `mods/`,
alongside [BlueMap](https://modrinth.com/mod/bluemap). Server side only.

## ⚙️ One setting worth knowing about

Core's `publishIntervalTicks` defaults to `10`, which is two position samples a second.
That is plenty for a train, and not always plenty for a **fast bearing**: a windmill past
roughly 15 RPM turns more than 90 degrees between samples, and the browser then has no way
to tell which way round it went. It will look like it is spinning slowly backwards, the
same way a wagon wheel does on film.

If your server has fast bearings and you care how they look, lower it:

```toml
[general]
    publishIntervalTicks = 4
```

The cost is bandwidth, not server time. Nothing is re-meshed by lowering it, because
geometry is cached and only the transforms are republished.

## 🔍 How it finds them

By enumerating Create's contraption entities, not its railway registry.

All four of Create's contraption entity types share one base class, and a train carriage is
already one entity per carriage with its own pose. So the awkward part of drawing a train,
that it articulates and cannot be one rigid body, is something Create had already solved
before this addon existed. Trains and windmills come out of the same loop.

Reading the railway registry instead is what the older Create map mods do, and it is right
for them: they draw markers on a 2D map, and a marker wants the track graph. Geometry wants
the entity.

## 🐛 Known rough edges

- **A train in unloaded chunks disappears.** Create keeps simulating it, but the carriage
  entity only exists while its chunk is ticking. A viewer watching empty wilderness will
  see a train wink out and come back.
- **Block entities on a contraption render as plain blocks.** A chest on a contraption is
  chest-shaped but has no lid, and a display board is blank. Contraptions are unusually
  dense in block entities, so this is the most visible gap.
- **Minecart contraptions may sit slightly off.** Create's client renderer applies an extra
  offset when a contraption is riding a cart, which the server-side transform does not.

## 🔗 Links

- [Create](https://modrinth.com/mod/create) - required
- [BlueMap](https://modrinth.com/mod/bluemap) - required
- [Contributing and internals](../core/README.md) - including why this reads entities
  rather than the railway registry
