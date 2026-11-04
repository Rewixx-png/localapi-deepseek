/* Runs before the first paint so the stored theme does not flash. */
(function () {
  try {
    var mode = localStorage.getItem("theme") || "system";
    var light = mode === "light" || (mode === "system" &&
      window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches);
    document.documentElement.setAttribute("data-theme", light ? "light" : "dark");
  } catch (e) { /* private mode */ }
})();
