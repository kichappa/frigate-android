package app.frigate.shell

/**
 * Script injected into Frigate's pages (only on the configured server's origin). It talks
 * to the app through the FrigateShell web-message object:
 *  - "theme": the page background and whether Frigate's dark class is on, so the status
 *    and navigation bars match whatever theme is picked in Frigate's own settings.
 *  - "download": <a download> clicks, so the file name Frigate chose is kept and the file
 *    is fetched with the login cookie and the trusted certificate.
 */
object Bridge {
    const val NAME = "FrigateShell"

    val SCRIPT = """
(function () {
  if (window.__frigateShell) return;
  window.__frigateShell = true;
  var shell = window.FrigateShell;
  function post(o) { try { if (shell) shell.postMessage(JSON.stringify(o)); } catch (e) {} }

  var last = '';
  function reportTheme() {
    var el = document.body || document.documentElement;
    if (!el) return;
    var bg = getComputedStyle(el).backgroundColor;
    var dark = document.documentElement.classList.contains('dark');
    var key = bg + '|' + dark;
    if (key === last) return;
    last = key;
    post({ type: 'theme', bg: bg, dark: dark });
  }
  function watchTheme() {
    reportTheme();
    var mo = new MutationObserver(reportTheme);
    mo.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'style'] });
    if (document.body) mo.observe(document.body, { attributes: true, attributeFilter: ['class', 'style'] });
    window.addEventListener('load', reportTheme);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', watchTheme);
  else watchTheme();

  function sendBlob(url, name) {
    fetch(url).then(function (r) { return r.blob(); }).then(function (b) {
      var fr = new FileReader();
      fr.onloadend = function () { post({ type: 'download', url: fr.result, name: name, mime: b.type }); };
      fr.readAsDataURL(b);
    });
  }
  window.__frigateShellBlob = sendBlob;

  // Bubble phase on window: Frigate's own handlers (e.g. a menu closing on select) run
  // first, then the browser's default download is swapped for the app's.
  window.addEventListener('click', function (ev) {
    if (ev.defaultPrevented) return;
    var t = ev.target;
    var a = t && t.closest ? t.closest('a[download]') : null;
    if (!a || !a.href) return;
    ev.preventDefault();
    var name = a.getAttribute('download') || '';
    if (a.href.indexOf('blob:') === 0) sendBlob(a.href, name);
    else post({ type: 'download', url: a.href, name: name });
  }, false);
})();
"""
}
