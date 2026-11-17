/* Runs before the first paint so stored theme and palette do not flash. */
(function () {
  try {
    var mode = localStorage.getItem("theme") || "system";
    var light = mode === "light" || (mode === "system" &&
      window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches);
    document.documentElement.setAttribute("data-theme", light ? "light" : "dark");
    var pal = localStorage.getItem("palette") || "dynamic";
    document.documentElement.setAttribute("data-palette", pal);
    if (pal === "dynamic") {
      var raw = localStorage.getItem(light ? "dynPalLight" : "dynPalDark");
      if (raw) {
        var obj = JSON.parse(raw);
        if (obj) {
          Object.keys(obj).forEach(function (k) {
            if (k.indexOf("--") === 0) document.documentElement.style.setProperty(k, obj[k]);
          });
        }
      }
    }
  } catch (e) { /* private mode */ }
})();
