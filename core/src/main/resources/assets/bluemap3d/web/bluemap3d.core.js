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
    var BUILD = "core-9";

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

    /* Node kinds, matching BakedMesh's KIND_* constants and the .bm3d trailer. The first
     * three are driven by the same odometer value; they differ only in what they do with
     * it. KIND_RATE is the odd one out - it turns whether or not its object has moved at
     * all, so it is driven by wall-clock time instead. See writeTransform. */
    var KIND_SPIN = 0;
    var KIND_OSCILLATE = 1;
    var KIND_ORBIT = 2;
    var KIND_RATE = 3;

    /* A frame gap longer than this is a tab coming back from the background, a minimised
     * window, or a stall - not a real frame - so KIND_RATE resets rather than integrating
     * it. Integrating a multi-second (or multi-minute) gap would spin the part through
     * many silent revolutions the instant the tab wakes up, which is exactly the kind of
     * jump this clamp exists to prevent. Chosen well above any real frame interval and
     * well below "the tab was actually away for a while". */
    var MAX_RATE_DT_SECONDS = 1;

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
        UP = new THREE.Vector3(0, 1, 0);

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
            /* The segment we've been interpolating through is now entirely behind us -
             * fold its full travel into each node's odometer before starting the next
             * one. Guarded on entry.to because the first sighting has no prior segment. */
            if (entry.to && entry.nodes) {
                for (var n = 0; n < entry.nodes.length; n++) {
                    entry.odometers[n] += entry.segmentTravel[n];
                }
            }

            /* First sighting: no history to interpolate from, so sit still at the
             * first sample rather than sliding in from the origin. */
            entry.from = entry.to || sample;
            entry.to = sample;

            /* Per (entry, node), not per entry: travel is projected onto a rolling
             * direction derived from that node's axis, and two nodes may have different
             * axes. */
            if (entry.nodes) {
                for (var m = 0; m < entry.nodes.length; m++) {
                    entry.segmentTravel[m] = nodeSegmentTravel(entry.nodes[m], entry.from, entry.to);
                }
            }
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
         * stopping dead.
         *
         * Gated on "no frames since the previous poll" rather than frameCount === 0.
         * frameCount is cumulative and never resets, so a tab that rendered and was then
         * minimised - exactly what this write exists for - would fail a zero test forever
         * and its wheels would freeze while the carriage kept stepping. */
        if (frameCount === frameCountAtLastPoll) {
            for (var id in objects) {
                writeTransform(objects[id], 1);
            }
        }
        frameCountAtLastPoll = frameCount;

        applyVisibility();
    }

    var frameCountAtLastPoll = 0;

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
        /* Captured before anything below overwrites entry.nodes / entry.rateAngles, so a
         * KIND_RATE part can keep turning smoothly through a re-bake instead of snapping
         * back to zero. */
        var oldNodes = entry.nodes;
        var oldRateAngles = entry.rateAngles;

        if (entry.mesh) {
            root.remove(entry.mesh);
        }

        /* Clamped to the static range, or every spinning part draws twice: once stuck in
         * its baked pose as part of the parent, and once again turning as its own node. */
        resource.geometry.setDrawRange(0, resource.staticIndexCount);

        var mesh = new THREE.Mesh(resource.geometry, resource.material);
        mesh.frustumCulled = true;
        mesh.matrixAutoUpdate = true;
        if (row.label) {
            mesh.name = row.label;
        }

        var nodeGroups = [];
        for (var i = 0; i < resource.nodes.length; i++) {
            var node = resource.nodes[i];
            var group = new THREE.Group();
            group.position.copy(node.pivot);

            var nodeMesh = new THREE.Mesh(resource.nodeGeometries[i], resource.material);
            nodeMesh.position.copy(node.pivot).negate();
            nodeMesh.frustumCulled = true;
            nodeMesh.matrixAutoUpdate = true;
            group.add(nodeMesh);

            mesh.add(group);
            nodeGroups.push(group);
        }

        entry.mesh = mesh;
        entry.nodes = resource.nodes;
        entry.nodeGroups = nodeGroups;

        /* Zeroed rather than carried over. A wheel's absolute phase is unobservable, so
         * resetting costs nothing visually - unlike position, where this client goes to
         * some trouble to preserve interpolation state. Carrying them would be actively
         * wrong when the node count changes, which happens whenever a carriage re-bakes,
         * and undefined + travel is NaN, which makes that child vanish. */
        entry.odometers = new Array(resource.nodes.length);
        entry.segmentTravel = new Array(resource.nodes.length);
        /* KIND_RATE's own state: an accumulated angle plus the wall-clock time it was
         * last advanced at, per node. rateLastTime starts null rather than "now" so the
         * first writeTransform call for a fresh node establishes a baseline instead of
         * integrating from mesh-load time to first render as if that gap were real
         * elapsed rotation. */
        entry.rateAngles = new Array(resource.nodes.length);
        entry.rateLastTime = new Array(resource.nodes.length);

        /* Carry over rateAngles when the new node table still means the same thing node
         * for node, so a re-bake (e.g. a bearing's quantised RPM changing) does not make
         * the cap visibly snap back to angle zero. Conservative on purpose: if the node
         * count or per-node kind differs, the old angles no longer describe the new
         * nodes and must be discarded. */
        var canCarryRate = !!oldNodes && !!oldRateAngles && oldNodes.length === resource.nodes.length;
        if (canCarryRate) {
            for (var k = 0; k < resource.nodes.length; k++) {
                if (oldNodes[k].kind !== resource.nodes[k].kind) {
                    canCarryRate = false;
                    break;
                }
            }
        }

        for (var j = 0; j < resource.nodes.length; j++) {
            entry.odometers[j] = 0;
            entry.segmentTravel[j] = 0;
            entry.rateAngles[j] = canCarryRate ? oldRateAngles[j] : 0;
            /* Null even when carrying the angle over, so the next writeTransform call
             * re-seeds the timestamp instead of integrating across the whole gap since
             * the last frame before this rebuild - that gap would otherwise be counted
             * as real elapsed rotation and the part would jump by however long the
             * re-bake took. */
            entry.rateLastTime[j] = null;
        }

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

        if (entry.nodeGroups) {
            for (var i = 0; i < entry.nodeGroups.length; i++) {
                var node = entry.nodes[i];
                var group = entry.nodeGroups[i];
                var value = entry.odometers[i] + alpha * entry.segmentTravel[i];

                if (node.kind === KIND_OSCILLATE) {
                    /* No pivot to cancel: the offset is added straight to the baked
                     * position along the declared axis. */
                    var offset = node.period > 0 ? node.radius * Math.sin(value / node.period) : 0;
                    group.position.set(
                        node.axis.x * offset, node.axis.y * offset, node.axis.z * offset);
                } else if (node.kind === KIND_ORBIT) {
                    /* An orbit node's "pivot" is a displacement, not a point: the vector
                     * from the centre of the circle out to where the part was baked. Turn
                     * that one vector about the axis and you have the part's position on
                     * the circle, relative to the same centre.
                     *
                     * group.position ends up as that turned vector while nodeMesh's own
                     * position (set once, in replaceMesh) stays at minus the unturned
                     * one, so the two cancel exactly at theta = 0 - the baked pose IS the
                     * rest pose - and away from zero they differ by the orbit offset and
                     * nothing else. The group's quaternion is never touched, so the part
                     * is displaced and never turned, which is the whole of KIND_ORBIT's
                     * contract.
                     *
                     * The rest direction has to come from the file like this. Deriving it
                     * from the axis instead - the only other thing a node carries - gives
                     * a direction that is right for one axis direction and wrong for the
                     * three others, which is exactly how a bogey pin ended up orbiting a
                     * point beside its axle rather than the axle. */
                    var theta = node.period > 0 ? value / node.period : 0;
                    group.position.copy(node.pivot).applyAxisAngle(node.axis, theta);
                } else if (node.kind === KIND_RATE) {
                    /* Driven by wall-clock time, not by "value" (the odometer) - a
                     * KIND_RATE part turns even while its object stands still, so travel
                     * has nothing to offer it. performance.now() is monotonic, unlike
                     * Date.now(), so a system clock change never shows up as a jump. */
                    var t = performance.now();
                    var last = entry.rateLastTime[i];
                    var dt = last === null ? 0 : (t - last) / 1000;
                    if (dt < 0 || dt > MAX_RATE_DT_SECONDS) {
                        dt = 0;
                    }
                    entry.rateLastTime[i] = t;
                    entry.rateAngles[i] += node.rate * dt;
                    group.quaternion.setFromAxisAngle(node.axis, entry.rateAngles[i]);
                } else {
                    group.quaternion.setFromAxisAngle(node.axis, value);
                }
            }
        }
    }

    var _scratch = null;
    var UP = null;

    /**
     * Travel of one node along its own rolling direction over one segment, projected
     * from the object's displacement in that segment and expressed in the node's local
     * (object-space) frame.
     *
     * Shared by every node kind, not just spin: an oscillation or an orbit is driven by
     * the same projection of the object's own displacement onto its node's axis, and
     * differs only in what nodeSegmentTravel and writeTransform do with the result
     * afterwards.
     */
    function rollTravel(axis, delta, fromRot) {
        var roll = axis.clone().cross(UP);
        if (roll.lengthSq() < 0.01) {
            /* axis within ~6 degrees of vertical: no meaningful rolling direction, and
             * the normalised cross product would be dominated by float error and give a
             * plausible but random one. */
            return 0;
        }
        roll.normalize();
        var local = delta.clone().applyQuaternion(fromRot.clone().invert());
        return local.dot(roll);
    }

    /* A speed no real vehicle in this mod reaches under its own power. Anything faster
     * over one segment is a teleport or a chunk-load pop, and clamping it to zero travel
     * is what stops that from spinning the wheels hundreds of revolutions. */
    var MAX_PLAUSIBLE_BLOCKS_PER_SECOND = 40;

    /**
     * One node's contribution over the segment (from -> to), computed once here from the
     * segment's starting orientation rather than per frame from the interpolated one.
     * writeTransform slerps between from.rot and to.rot, so a per-frame recomputation
     * would make odometer + alpha * travel non-monotonic in alpha and the part would
     * visibly hunt back and forth through a curve.
     *
     * For a spin this is already the angle (radians): dividing by radius here, once per
     * segment, is what the odometer has always accumulated. Oscillation and orbit have
     * no such fixed conversion baked in - period can change meaning per node in a way
     * radius never needed to - so they get the raw travel (blocks) and convert it
     * themselves at render time.
     */
    function nodeSegmentTravel(node, from, to) {
        var fromRot = _scratch.set(from.rot[0], from.rot[1], from.rot[2], from.rot[3]);
        var delta = new THREE.Vector3(
            to.pos[0] - from.pos[0],
            to.pos[1] - from.pos[1],
            to.pos[2] - from.pos[2]
        );
        var travel = rollTravel(node.axis, delta, fromRot);

        /* Clamp implausible segments to zero travel, so a teleport or chunk-load pop
         * does not spin the wheels hundreds of revolutions. */
        var elapsedS = Math.max((to.t - from.t) / 1000, 0.001);
        if (Math.abs(travel) > MAX_PLAUSIBLE_BLOCKS_PER_SECOND * elapsedS) {
            return 0;
        }

        if (node.kind !== KIND_SPIN) {
            return travel;
        }
        return node.radius > 0 ? travel / node.radius : 0;
    }

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
     *   4   u32         format version (1 to 5)
     *   8   u32         vertex count
     *   12  u32         index count
     *   16  u32         atlas url byte length
     *   20  u8[]        atlas url, utf-8, zero-padded to a 4-byte boundary
     *       f32[v*3]    positions, block units relative to the pivot
     *       f32[v*2]    uvs
     *       u32[i]      indices
     *       u8[v*3]     vertex colours, RGB, zero-padded to a 4-byte boundary
     *
     * v2, v3 and v4 only, immediately after the colour padding:
     *
     *       u32         static index count: the parent's draw range is [0, this)
     *       u32         node count
     *       node[]      one per animated part, in draw order:
     *                     u32     kind (v3 only; a v2 node is always kind 0), see the
     *                             KIND_* constants below
     *                     u32     index start
     *                     u32     index count
     *                     f32[3]  pivot, block units relative to the object pivot
     *                     f32[3]  axis, normalised
     *                     f32     radius, block units
     *                     f32     period, the divisor in sin(travel / period) /
     *                             travel / period, block units - a full cycle is
     *                             2 * PI * period of travel, not period itself
     *                             (v3+ only; a v2 node has none, and kind 0 ignores
     *                             it anyway)
     *                     f32     rate, radians per second (v4 only; kinds other than
     *                             KIND_RATE ignore it)
     */
    function decode(buffer) {
        var view = new DataView(buffer);

        if (view.getUint8(0) !== 0x42 || view.getUint8(1) !== 0x4D
            || view.getUint8(2) !== 0x33 || view.getUint8(3) !== 0x44) {
            throw new Error("not a .bm3d file");
        }
        var version = view.getUint32(4, true);
        if (version < 1 || version > 5) {
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
        offset += vertices * 3;
        offset = (offset + 3) & ~3;

        /* A v1 file has no trailer. Defaulting staticIndexCount to the whole index buffer
           is what keeps every turtle and ship rendering: a client that clamped the parent
           to an unset field would draw nothing at all. */
        var staticIndexCount = indexCount;
        var nodes = [];
        if (version >= 2) {
            staticIndexCount = view.getUint32(offset, true);
            offset += 4;
            var nodeCount = view.getUint32(offset, true);
            offset += 4;
            for (var i = 0; i < nodeCount; i++) {
                // A v2 file has no kind or period field at all - every one of its nodes
                // was implicitly a spin, and had no period to read.
                var kind = KIND_SPIN;
                if (version >= 3) {
                    kind = view.getUint32(offset, true);
                    offset += 4;
                }
                var indexStart = view.getUint32(offset, true);
                offset += 4;
                var nodeIndexCount = view.getUint32(offset, true);
                offset += 4;
                var pivot = new THREE.Vector3(
                    view.getFloat32(offset, true),
                    view.getFloat32(offset + 4, true),
                    view.getFloat32(offset + 8, true)
                );
                offset += 12;
                var axis = new THREE.Vector3(
                    view.getFloat32(offset, true),
                    view.getFloat32(offset + 4, true),
                    view.getFloat32(offset + 8, true)
                );
                offset += 12;
                var radius = view.getFloat32(offset, true);
                offset += 4;
                var period = 0;
                if (version >= 3) {
                    period = view.getFloat32(offset, true);
                    offset += 4;
                }
                // A v1-v3 file has no rate field at all - KIND_RATE did not exist yet,
                // so there is nothing for an older node to have meant by it.
                var rate = 0;
                if (version >= 4) {
                    rate = view.getFloat32(offset, true);
                    offset += 4;
                }

                var node = {
                    kind: kind,
                    indexStart: indexStart,
                    indexCount: nodeIndexCount,
                    pivot: pivot,
                    axis: axis,
                    radius: radius,
                    period: period,
                    rate: rate
                };
                nodes.push(node);
            }
        }

        var geometry = new THREE.BufferGeometry();
        geometry.setAttribute("position", new THREE.BufferAttribute(positions, 3));
        geometry.setAttribute("uv", new THREE.BufferAttribute(uvs, 2));
        /* normalized: true, so 0..255 arrives in the shader as 0..1. */
        geometry.setAttribute("color", new THREE.BufferAttribute(colors, 3, true));
        geometry.setIndex(new THREE.BufferAttribute(indices, 1));
        geometry.computeBoundingSphere();

        return {
            geometry: geometry,
            material: material(url),
            staticIndexCount: staticIndexCount,
            nodes: nodes,
            nodeGeometries: buildNodeGeometries(geometry, nodes)
        };
    }

    /**
     * Builds one child geometry per spinning node, sharing the parent's attribute and
     * index buffers with setDrawRange rather than copying them - copying per node would
     * re-upload the whole vertex buffer per wheel.
     *
     * Depends only on the file, so this runs once per cached resource and is then shared
     * by every object using that mesh url, including two identical carriages.
     */
    function buildNodeGeometries(geometry, nodes) {
        var position = geometry.attributes.position;
        var index = geometry.index;
        var result = [];

        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            var nodeGeometry = new THREE.BufferGeometry();
            nodeGeometry.setAttribute("position", position);
            nodeGeometry.setAttribute("uv", geometry.attributes.uv);
            nodeGeometry.setAttribute("color", geometry.attributes.color);
            nodeGeometry.setIndex(index);
            nodeGeometry.setDrawRange(node.indexStart, node.indexCount);

            /* Centred on the pivot rather than fitted to the geometry, because the part
               rotates about that pivot: a sphere fitted to the static pose is swept outside
               by anything whose pivot is off-centre, and the part then gets frustum culled
               while still plainly on screen.

               A KIND_ORBIT or KIND_OSCILLATE node has no pivot point to centre on, so this
               centres on a near-origin vector instead and the sphere comes out larger than
               the part needs. That errs the safe way - an over-large sphere costs a part
               being drawn slightly more often than necessary, never a visible part being
               culled - and these are the smallest nodes in any mesh anyway. */
            var maxDistSq = 0;
            var end = node.indexStart + node.indexCount;
            for (var j = node.indexStart; j < end; j++) {
                var vi = index.array[j];
                var dx = position.array[vi * 3] - node.pivot.x;
                var dy = position.array[vi * 3 + 1] - node.pivot.y;
                var dz = position.array[vi * 3 + 2] - node.pivot.z;
                var distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > maxDistSq) {
                    maxDistSq = distSq;
                }
            }
            nodeGeometry.boundingSphere = new THREE.Sphere(node.pivot.clone(), Math.sqrt(maxDistSq));

            result.push(nodeGeometry);
        }

        return result;
    }

    var materialCache = Object.create(null);

    function material(atlasUrl) {
        if (materialCache[atlasUrl]) {
            return materialCache[atlasUrl];
        }
        var texture = new THREE.TextureLoader().load(atlasUrl);
        texture.magFilter = THREE.NearestFilter;
        texture.minFilter = THREE.NearestFilter;
        texture.generateMipmaps = false;
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
                /* Disposed together, never piecemeal: three.js drops the shared index
                 * attribute's GL buffer on any single dispose, which would force the
                 * parent's buffer to be re-uploaded on the next frame. */
                resource.geometry.dispose();
                resource.nodeGeometries.forEach(function (nodeGeometry) {
                    nodeGeometry.dispose();
                });
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
