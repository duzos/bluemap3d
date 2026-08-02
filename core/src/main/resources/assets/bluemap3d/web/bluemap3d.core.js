/*
 * BlueMap3D - real 3D geometry inside BlueMap's three.js scene.
 *
 * This file is part of BlueMap3D, licensed under LGPL-3.0-only.
 *
 * ---------------------------------------------------------------------------------
 * How this works, and why it works at all
 * ---------------------------------------------------------------------------------
 *
 * BlueMap loads this file as a plain <script> because the server called
 * WebApp.registerScript(), which adds it to settings.json's "scripts" array. The
 * webapp then appends a script tag per entry, after window.bluemap exists.
 *
 * Two things in the webapp make raw meshes possible, both verified against the
 * deployed BlueMap 5.7 bundle rather than assumed:
 *
 *   1. window.BlueMap.Three is three.js itself - BlueMap re-exports its own module.
 *      So there is no second copy of three.js here, and nothing to keep in version
 *      step with the renderer.
 *
 *   2. window.bluemap.mapViewer.markers is a real THREE.Scene ("class MarkerSet
 *      extends Scene"), and MapViewer.render() passes it straight to
 *      renderer.render(this.markers, this.camera) every frame. So anything added to
 *      it is drawn by the real renderer with the real camera.
 *
 * Three useful consequences of where that render pass sits:
 *
 *   - It runs after the terrain passes and without an intervening clearDepth(), so
 *     the depth buffer still holds the terrain. Objects behind a hill are correctly
 *     hidden by it, for free.
 *   - It runs outside the block that applies BlueMap's precision shift (the camera
 *     is moved towards the origin for the terrain passes and moved back before this
 *     one), so positions here are plain world coordinates with no offset maths.
 *   - MarkerSet.clear() and updateFromData() only remove markers they are tracking
 *     in their own maps, so children added directly are not swept away by BlueMap's
 *     marker sync.
 *
 * Coordinates are raw Minecraft coordinates. BlueMap's own ExtrudeMarker does
 * `point.x - this.position.x` with no axis remapping, so x, y and z pass straight
 * through.
 */
(function () {
    "use strict";

    var FEED_URL = "assets/bluemap3d/entities3d.json";
    var LOG = "[BlueMap3D]";
    var BUILD = "core-7";

    /* Verbose per-poll diagnostics. Off by default - at two polls a second it is a lot of
     * console for a working install. Turn it on at runtime with
     *     window.__bluemap3d.verbose = true
     * Lifecycle, terrain reloads and errors are logged regardless. */
    var verbose = false;
    var pollCount = 0;
    var frameCount = 0;

    function debug() {
        if (!verbose) {
            return;
        }
        var args = Array.prototype.slice.call(arguments);
        args.unshift(LOG);
        console.debug.apply(console, args);
    }

    /* How far behind live to render. Staying one publish interval in the past means
     * every frame interpolates between two samples we already have, instead of
     * extrapolating past the newest one and then snapping back when it arrives. */
    var DELAY_FACTOR = 1.0;

    var THREE = null;
    var viewer = null;
    var root = null;

    /* id -> { mesh, meshUrl, from, to, label } */
    var objects = Object.create(null);
    /* meshUrl -> Promise<{geometry, material}> */
    var meshCache = Object.create(null);

    var dimensionMaps = {};
    var intervalMs = 1000;
    var pollTimer = null;
    var disposed = false;

    /* Terrain reload state. */
    var tilesVersion = null;
    var lastTileReload = 0;
    var rateLimitNoted = false;
    /* Never reload terrain more often than this. A quarry turtle dirties tiles
     * continuously, and re-downloading every visible tile on every change would cost far
     * more than the stale geometry it removes. Server-controlled via the feed
     * (bluemap3d-server.toml, tileReloadMinSeconds); 0 disables terrain reloading. */
    var tileReloadMinMs = 5000;

    // -----------------------------------------------------------------------------
    // Startup
    // -----------------------------------------------------------------------------

    function ready() {
        return window.bluemap
            && window.bluemap.mapViewer
            && window.bluemap.mapViewer.markers
            && window.BlueMap
            && window.BlueMap.Three;
    }

    function start() {
        THREE = window.BlueMap.Three;
        viewer = window.bluemap.mapViewer;
        _scratch = new THREE.Quaternion();

        root = new THREE.Group();
        root.name = "bluemap3d";
        /* MarkerSet.dispose() calls dispose() on each child, so this is the hook for
         * being cleaned up properly if BlueMap ever tears the marker scene down. */
        root.dispose = disposeAll;
        viewer.markers.add(root);

        var events = window.bluemap.events;
        events.addEventListener("bluemapRenderFrame", onFrame);
        events.addEventListener("bluemapMapChanged", function () {
            console.info(LOG, "map changed ->",
                viewer.map && viewer.map.data ? viewer.map.data.id : null);
            applyVisibility();
        });

        /* Everything the diagnosis needs, reachable from the console. */
        window.__bluemap3d = {
            build: BUILD,
            get verbose() {
                return verbose;
            },
            set verbose(v) {
                verbose = v;
            },
            objects: objects,
            root: root,
            viewer: viewer,
            stats: function () {
                return {
                    build: BUILD,
                    polls: pollCount,
                    frames: frameCount,
                    objects: Object.keys(objects).length,
                    meshesInScene: root.children.length,
                    intervalMs: intervalMs,
                    tilesVersion: tilesVersion,
                    tileReloadMinMs: tileReloadMinMs,
                    msSinceTileReload: lastTileReload ? Date.now() - lastTileReload : null,
                    renderLoopRunning: frameCount > 0
                };
            },
            reloadTerrainNow: function () {
                lastTileReload = 0;
                reloadTerrain("manual");
            }
        };

        console.info(LOG, BUILD, "attached to BlueMap's marker scene.",
            "Diagnostics: window.__bluemap3d.stats()");
        console.info(LOG, "capabilities:", {
            clearTileCache: typeof viewer.clearTileCache,
            updateLoadedMapArea: typeof viewer.updateLoadedMapArea,
            hiresTileManager: !!(viewer.map && viewer.map.hiresTileManager),
            lowresTileManagers: viewer.map && viewer.map.lowresTileManager
                ? viewer.map.lowresTileManager.length : 0
        });

        poll();

        /* The render loop drives interpolation. If it never runs - a background tab, a
         * headless pane - transforms still update, but only once per feed. Worth saying
         * out loud rather than leaving it to look like broken interpolation. */
        setTimeout(function () {
            if (frameCount === 0) {
                console.warn(LOG, "no bluemapRenderFrame events after 3s:"
                    + " this tab is not rendering, so motion will step per feed rather than"
                    + " interpolate. Terrain reloading is unaffected.");
            } else {
                console.info(LOG, "render loop OK,", frameCount, "frames in 3s");
            }
        }, 3000);
    }

    function waitForBlueMap(attempt) {
        if (ready()) {
            try {
                start();
            } catch (e) {
                console.error(LOG, "failed to start", e);
            }
            return;
        }
        if (attempt > 100) {
            console.warn(LOG, "gave up waiting for window.bluemap.mapViewer");
            return;
        }
        setTimeout(function () {
            waitForBlueMap(attempt + 1);
        }, 100);
    }

    // -----------------------------------------------------------------------------
    // The live feed
    // -----------------------------------------------------------------------------

    function poll() {
        if (disposed) {
            return;
        }
        fetch(FEED_URL, {cache: "no-store"})
            .then(function (response) {
                if (!response.ok) {
                    throw new Error("HTTP " + response.status);
                }
                return response.json();
            })
            .then(applyFeed)
            .catch(function (e) {
                /* Anything thrown inside applyFeed lands here too, and silently swallowing
                 * that is how a broken feed handler looks identical to a missing feed. */
                console.warn(LOG, "poll failed:", e && e.message ? e.message : e, e);
            })
            .then(function () {
                pollTimer = setTimeout(poll, intervalMs);
            });
    }

    function applyFeed(feed) {
        if (typeof feed.intervalMs === "number" && feed.intervalMs > 0) {
            intervalMs = feed.intervalMs;
        }
        dimensionMaps = feed.maps || {};
        if (typeof feed.tileReloadMinMs === "number") {
            tileReloadMinMs = feed.tileReloadMinMs;
        }

        pollCount++;
        if (verbose && pollCount % 10 === 1) {
            debug("poll #" + pollCount, {
                objects: (feed.objects || []).length,
                tilesVersion: feed.tilesVersion,
                knownTilesVersion: tilesVersion,
                tileReloadMinMs: tileReloadMinMs,
                frames: frameCount
            });
        }

        /* Accumulate dirty tiles across polls: the server drains its list each time it
         * publishes, so a tile mentioned during the rate-limit window would otherwise be
         * forgotten by the time a reload is actually allowed. */
        collectDirtyTiles(feed.dirtyTiles);
        maybeReloadTerrain(feed.tilesVersion);

        var now = performance.now();
        var present = Object.create(null);

        (feed.objects || []).forEach(function (row) {
            present[row.id] = true;
            var entry = objects[row.id];

            if (!entry) {
                entry = objects[row.id] = {
                    mesh: null,
                    meshUrl: null,
                    dimension: row.dimension,
                    from: null,
                    to: null
                };
            }
            entry.dimension = row.dimension;

            /* A new mesh url means the geometry version changed, so swap the mesh but
             * keep the interpolation state - the object has not teleported. */
            if (entry.meshUrl !== row.mesh) {
                entry.meshUrl = row.mesh;
                debug("mesh: loading", row.mesh, "for", row.id);
                loadMesh(row.mesh).then(function (resource) {
                    var live = objects[row.id];
                    if (!live || live.meshUrl !== row.mesh) {
                        return;
                    }
                    replaceMesh(live, resource, row);
                    console.info(LOG, "mesh ready:", row.label || row.id,
                        resource.geometry.attributes.position.count + " verts");
                }).catch(function (e) {
                    console.error(LOG, "could not load", row.mesh, e);
                });
            }

            var sample = {
                t: now,
                pos: row.pos,
                rot: row.rot
            };
            /* First sighting: no history to interpolate from, so sit still at the
             * first sample rather than sliding in from the origin. */
            entry.from = entry.to || sample;
            entry.to = sample;
        });

        Object.keys(objects).forEach(function (id) {
            if (!present[id]) {
                remove(id);
            }
        });

        /* Write transforms here as well as on every frame, so a new sample lands even
         * when bluemapRenderFrame is not firing. It stops firing whenever the browser
         * pauses requestAnimationFrame - a background tab, a minimised window, a headless
         * pane - and relying on it alone means objects silently freeze at their first
         * position while the feed keeps arriving, which looks like the mod is broken
         * rather than the tab being asleep. With frames running this is redundant and
         * costs nothing; without them it degrades to a step per interval instead of
         * stopping dead. */
        for (var id in objects) {
            writeTransform(objects[id], 1);
        }

        applyVisibility();
    }

    function remove(id) {
        var entry = objects[id];
        delete objects[id];
        if (entry && entry.mesh) {
            root.remove(entry.mesh);
            /* Geometry and material live in meshCache and may be shared with another
             * object, so they are not disposed here. */
        }
    }

    function replaceMesh(entry, resource, row) {
        if (entry.mesh) {
            root.remove(entry.mesh);
        }
        var mesh = new THREE.Mesh(resource.geometry, resource.material);
        mesh.frustumCulled = true;
        mesh.matrixAutoUpdate = true;
        if (row.label) {
            mesh.name = row.label;
        }
        entry.mesh = mesh;
        /* Placed by the next frame; adding it already positioned avoids a one-frame
         * flash at the origin. */
        writeTransform(entry, 1);
        root.add(mesh);
    }

    // -----------------------------------------------------------------------------
    // Interpolation
    // -----------------------------------------------------------------------------

    function onFrame() {
        frameCount++;
        var renderAt = performance.now() - intervalMs * DELAY_FACTOR;

        for (var id in objects) {
            var entry = objects[id];
            if (!entry.mesh || !entry.to) {
                continue;
            }
            var span = entry.to.t - entry.from.t;
            var alpha = span > 0 ? (renderAt - entry.from.t) / span : 1;
            writeTransform(entry, alpha < 0 ? 0 : (alpha > 1 ? 1 : alpha));
        }
    }

    function writeTransform(entry, alpha) {
        var from = entry.from || entry.to;
        var to = entry.to;
        if (!from || !to || !entry.mesh) {
            return;
        }
        var mesh = entry.mesh;

        mesh.position.set(
            from.pos[0] + (to.pos[0] - from.pos[0]) * alpha,
            from.pos[1] + (to.pos[1] - from.pos[1]) * alpha,
            from.pos[2] + (to.pos[2] - from.pos[2]) * alpha
        );

        /* Slerp rather than lerp: a component-wise blend of two quaternions is not a
         * rotation, and shows up as an object shrinking as it turns. */
        mesh.quaternion
            .set(from.rot[0], from.rot[1], from.rot[2], from.rot[3])
            .slerp(
                _scratch.set(to.rot[0], to.rot[1], to.rot[2], to.rot[3]),
                alpha
            );
    }

    var _scratch = null;

    // -----------------------------------------------------------------------------
    // Terrain reload
    // -----------------------------------------------------------------------------

    /**
     * Re-downloads terrain tiles when the server says it re-rendered some.
     *
     * BlueMap's webapp never does this by itself. Its one-second update loop only calls
     * followPlayerMarkerWorld(), and tile urls carry a cache hash that is fixed for the
     * session - so a viewer who leaves the page open keeps seeing the terrain as it was
     * when they opened it, however many times the server re-rendered it. That is what
     * makes a turtle's mined-out tunnel, or the frozen copy of a turtle baked into a
     * tile, appear permanent.
     *
     * clearTileCache() is BlueMap's own method for this: it installs a fresh
     * revalidatedUrls set into every tile loader, which is what lets a repeat request
     * actually reach the server instead of the browser cache.
     */
    function maybeReloadTerrain(version) {
        if (typeof version !== "number") {
            debug("terrain: no tilesVersion in feed (old server?), skipping");
            return;
        }
        if (tileReloadMinMs <= 0) {
            debug("terrain: reloading disabled (tileReloadMinSeconds = 0)");
            return;
        }
        if (tilesVersion === null) {
            /* First feed: nothing has gone stale yet, and reloading on connect would just
             * make every viewer re-download the map for no reason. */
            tilesVersion = version;
            console.info(LOG, "terrain: baseline tilesVersion =", version);
            return;
        }
        if (version === tilesVersion) {
            return;
        }

        var now = Date.now();
        var since = now - lastTileReload;
        if (since < tileReloadMinMs) {
            /* Once per window, not once per poll: at two polls a second this was ten
             * identical lines between every reload. */
            if (!rateLimitNoted) {
                rateLimitNoted = true;
                debug("terrain: changes pending, waiting out the "
                    + tileReloadMinMs + "ms rate limit");
            }
            return;
        }
        rateLimitNoted = false;

        console.info(LOG, "terrain: tilesVersion " + tilesVersion + " -> " + version
            + ", reloading tiles");
        tilesVersion = version;
        reloadTerrain("version " + version);
    }

    /** mapId -> { "x,z": [x, z] } of tiles the server says changed. */
    var dirtyTiles = Object.create(null);

    function collectDirtyTiles(fromFeed) {
        if (!fromFeed) {
            return;
        }
        for (var mapId in fromFeed) {
            var list = fromFeed[mapId];
            if (!list || !list.length) {
                continue;
            }
            var bucket = dirtyTiles[mapId] || (dirtyTiles[mapId] = Object.create(null));
            for (var i = 0; i < list.length; i++) {
                bucket[list[i][0] + "," + list[i][1]] = list[i];
            }
        }
    }

    /**
     * Replaces exactly the tiles the server said changed.
     *
     * Two deliberate restrictions, both learned the hard way:
     *
     * Only the named tiles are touched. Dropping every loaded tile and re-requesting them
     * blanks the map for as long as the round trip takes, which reads as the whole page
     * flashing black several times a minute.
     *
     * Only hires tiles are touched. Lowres is the zoomed-out overview, drawn from three
     * separate LOD managers on a coarser grid, and a turtle-sized edit is invisible at that
     * scale - so unloading them costs a full-screen flash to show nothing. When the viewer
     * is zoomed out far enough that hires is not loaded, this correctly does nothing.
     */
    function reloadTerrain(reason) {
        lastTileReload = Date.now();
        try {
            var map = viewer.map;
            if (!map) {
                return;
            }
            var mapId = map.data ? map.data.id : null;
            var bucket = mapId ? dirtyTiles[mapId] : null;
            var keys = bucket ? Object.keys(bucket) : [];
            if (!keys.length) {
                debug("terrain: nothing dirty on '" + mapId + "', skipping (" + reason + ")");
                return;
            }

            /* Installs a fresh revalidatedUrls set, which is what lets the re-request
             * actually reach the server instead of the browser's cache. */
            viewer.clearTileCache();

            var manager = map.hiresTileManager;
            var hash = window.BlueMap.hashTile;
            var replaced = 0;
            var notLoaded = 0;

            for (var i = 0; i < keys.length; i++) {
                var x = bucket[keys[i]][0];
                var z = bucket[keys[i]][1];
                var tile = manager.tiles.get(hash(x, z));
                if (tile) {
                    tile.unload();
                    manager.tiles.delete(hash(x, z));
                    manager.tryLoadTile(x, z);
                    replaced++;
                } else {
                    /* Off screen, or the viewer is zoomed past hires. Nothing to do - it
                     * will be fetched fresh if they come back to it. */
                    notLoaded++;
                }
            }
            dirtyTiles[mapId] = Object.create(null);

            console.info(LOG, "terrain (" + reason + "):", replaced, "hires tile(s) replaced,",
                notLoaded, "not on screen");
        } catch (e) {
            console.error(LOG, "could not reload terrain tiles", e);
        }
    }

    // -----------------------------------------------------------------------------
    // Visibility
    // -----------------------------------------------------------------------------

    /**
     * Hides objects whose dimension is not rendered by the map currently on screen.
     *
     * The webapp's Map object knows its id but not which world it shows, so the
     * server publishes the dimension-to-map-ids mapping in the feed.
     */
    function applyVisibility() {
        var map = viewer && viewer.map;
        var mapId = map && map.data ? map.data.id : null;

        for (var id in objects) {
            var entry = objects[id];
            if (!entry.mesh) {
                continue;
            }
            var allowed = dimensionMaps[entry.dimension];
            entry.mesh.visible = !mapId || !allowed || allowed.indexOf(mapId) >= 0;
        }
    }

    // -----------------------------------------------------------------------------
    // .bm3d loading
    // -----------------------------------------------------------------------------

    function loadMesh(url) {
        if (!meshCache[url]) {
            meshCache[url] = fetch(url, {cache: "force-cache"})
                .then(function (response) {
                    if (!response.ok) {
                        throw new Error("HTTP " + response.status);
                    }
                    return response.arrayBuffer();
                })
                .then(decode)
                .catch(function (e) {
                    /* Do not cache a failure forever; a re-bake may fix it. */
                    delete meshCache[url];
                    throw e;
                });
        }
        return meshCache[url];
    }

    /**
     * Decodes the .bm3d format written by Bm3dWriter.
     *
     * Layout, little-endian, every typed array 4-byte aligned so it can be wrapped
     * over the buffer with no copying:
     *
     *   0   char[4]     "BM3D"
     *   4   u32         format version
     *   8   u32         vertex count
     *   12  u32         index count
     *   16  u32         atlas url byte length
     *   20  u8[]        atlas url, utf-8, zero-padded to a 4-byte boundary
     *       f32[v*3]    positions, block units relative to the pivot
     *       f32[v*2]    uvs
     *       u32[i]      indices
     *       u8[v*3]     vertex colours, RGB
     */
    function decode(buffer) {
        var view = new DataView(buffer);

        if (view.getUint8(0) !== 0x42 || view.getUint8(1) !== 0x4D
            || view.getUint8(2) !== 0x33 || view.getUint8(3) !== 0x44) {
            throw new Error("not a .bm3d file");
        }
        var version = view.getUint32(4, true);
        if (version !== 1) {
            throw new Error("unsupported .bm3d version " + version);
        }

        var vertices = view.getUint32(8, true);
        var indexCount = view.getUint32(12, true);
        var urlLength = view.getUint32(16, true);

        var url = new TextDecoder("utf-8").decode(new Uint8Array(buffer, 20, urlLength));
        var offset = 20 + ((urlLength + 3) & ~3);

        var positions = new Float32Array(buffer, offset, vertices * 3);
        offset += vertices * 3 * 4;
        var uvs = new Float32Array(buffer, offset, vertices * 2);
        offset += vertices * 2 * 4;
        var indices = new Uint32Array(buffer, offset, indexCount);
        offset += indexCount * 4;
        var colors = new Uint8Array(buffer, offset, vertices * 3);

        var geometry = new THREE.BufferGeometry();
        geometry.setAttribute("position", new THREE.BufferAttribute(positions, 3));
        geometry.setAttribute("uv", new THREE.BufferAttribute(uvs, 2));
        /* normalized: true, so 0..255 arrives in the shader as 0..1. */
        geometry.setAttribute("color", new THREE.BufferAttribute(colors, 3, true));
        geometry.setIndex(new THREE.BufferAttribute(indices, 1));
        geometry.computeBoundingSphere();

        return {
            geometry: geometry,
            material: material(url)
        };
    }

    var materialCache = Object.create(null);

    function material(atlasUrl) {
        if (materialCache[atlasUrl]) {
            return materialCache[atlasUrl];
        }
        var texture = new THREE.TextureLoader().load(atlasUrl);
        /* Crisp texels close up - this is Minecraft, a blurry oak plank is wrong. */
        texture.magFilter = THREE.NearestFilter;
        /* Filtered when minified, which is the opposite decision and the right one.
         * Point-sampling a sprite that is smaller on screen than it is in the atlas
         * makes every pixel pick whichever texel it happens to land on, and a fraction
         * of camera movement makes it pick a different one. On a plank face nobody
         * notices; on something a sixteenth of a block thick and high-contrast - the
         * antenna on Create's redstone link is the one that gave this away - it reads
         * as the texture flickering.
         *
         * Safe only because the baker leaves a gutter of repeated edge pixels around
         * every tile. Mipmapping a bare atlas averages across tile boundaries and
         * bleeds one sprite into the next. */
        texture.minFilter = THREE.NearestMipmapLinearFilter;
        texture.generateMipmaps = true;
        /* Thin geometry is usually seen at a glancing angle, which is exactly the case
         * mipmapping alone over-blurs. */
        texture.anisotropy = 4;
        /* Minecraft measures v from the top of a texture, and the baker emits uvs in
         * that convention. three.js flips images on upload by default, which would
         * turn every face upside down. */
        texture.flipY = false;
        if (THREE.SRGBColorSpace) {
            texture.colorSpace = THREE.SRGBColorSpace;
        }

        var result = new THREE.MeshBasicMaterial({
            map: texture,
            /* Unlit on purpose. The marker scene contains no lights, so anything
             * light-dependent renders black. Minecraft's own directional face shading
             * is baked into the vertex colours instead, which is also what makes these
             * meshes sit correctly next to BlueMap's shader-lit terrain. */
            vertexColors: true,
            /* Winding is not guaranteed consistent across every model in a pack, and a
             * real depth buffer makes single-sided rendering an unnecessary risk. */
            side: THREE.DoubleSide,
            /* Cutout textures - glass, leaves, the turtle's translucent parts. */
            transparent: false,
            alphaTest: 0.5
        });
        materialCache[atlasUrl] = result;
        return result;
    }

    // -----------------------------------------------------------------------------
    // Teardown
    // -----------------------------------------------------------------------------

    function disposeAll() {
        disposed = true;
        if (pollTimer) {
            clearTimeout(pollTimer);
            pollTimer = null;
        }
        Object.keys(objects).forEach(remove);
        Object.keys(meshCache).forEach(function (url) {
            meshCache[url].then(function (resource) {
                resource.geometry.dispose();
            }).catch(function () {
            });
        });
        Object.keys(materialCache).forEach(function (url) {
            if (materialCache[url].map) {
                materialCache[url].map.dispose();
            }
            materialCache[url].dispose();
        });
        meshCache = Object.create(null);
        materialCache = Object.create(null);
    }

    // -----------------------------------------------------------------------------

    waitForBlueMap(0);
})();
