/*
 * BlueMap3D loader.
 *
 * This file is part of BlueMap3D, licensed under LGPL-3.0-only.
 *
 * Its only job is to pull in bluemap3d.core.js with a cache-busting query string.
 *
 * The indirection is not gratuitous. BlueMap injects registered scripts as a plain
 * <script src="assets/bluemap3d/bluemap3d.js">, with no version or hash, so a browser
 * that has seen the map once keeps running the copy it cached - and every fix shipped
 * afterwards is invisible until the viewer hard-refreshes.
 *
 * Putting the hash directly in the registered url would fix the caching but break
 * something worse: WebApp.registerScript adds to a persisted Set with no matching
 * unregister, so each new hash would leave the previous url behind in settings.json and
 * the webapp would inject every past version at once, each running its own poll loop.
 *
 * A stable loader whose contents never change sidesteps both. It is fine for this file
 * to be cached forever, because it will never say anything different.
 */
(function () {
    "use strict";

    var LOADER_BUILD = "loader-2";

    if (window.__bluemap3dLoaded) {
        console.warn("[BlueMap3D] loader ran twice; ignoring the second one."
            + " Already loaded by:", window.__bluemap3dLoaded);
        return;
    }
    window.__bluemap3dLoaded = LOADER_BUILD;

    var url = "assets/bluemap3d/bluemap3d.core.js?t=" + Date.now();
    console.info("[BlueMap3D] " + LOADER_BUILD + " loading", url);

    var script = document.createElement("script");
    script.src = url;
    script.async = false;
    script.onerror = function () {
        console.error("[BlueMap3D] FAILED to load", url,
            "- the map will show no 3D objects. Check that the file exists in BlueMap's web root.");
    };
    script.onload = function () {
        console.info("[BlueMap3D] core script loaded");
    };
    document.body.appendChild(script);
})();
