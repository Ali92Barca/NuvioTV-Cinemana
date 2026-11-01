package com.nuvio.tv.core.server

/** Mobile-friendly local page for entering a TMDB v3 key. */
internal object TmdbKeyPairingPage {
    val HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>NuvioTV - TMDB key</title>
<style>
:root { color-scheme: dark; font-family: system-ui, sans-serif; }
body { background: #10131b; color: #fff; margin: 0; padding: 24px; }
main { max-width: 480px; margin: 8vh auto; padding: 28px;
       background: #1b2030; border-radius: 20px; }
h1 { font-size: 24px; margin: 0 0 12px; }
p { color: #bac4d8; line-height: 1.6; }
label { display: block; margin: 22px 0 8px; }
input { box-sizing: border-box; width: 100%; padding: 15px;
        font-size: 16px; border-radius: 10px; border: 1px solid #65718a;
        background: #10131b; color: #fff; }
button { width: 100%; margin-top: 18px; padding: 15px;
         border: 0; border-radius: 10px; background: #5487f5;
         color: #fff; font-size: 17px; font-weight: 600; }
button:disabled { opacity: .5; }
#status { min-height: 26px; margin-top: 18px; }
</style>
</head>
<body>
<main>
<h1>TMDB API key</h1>
<p>Enter your personal TMDB v3 API key to send it to NuvioTV.
You will confirm the change on your television.</p>
<form id="keyForm">
<label for="key">TMDB API v3 key</label>
<input id="key" type="password" autocomplete="off" autocapitalize="off"
       spellcheck="false" maxlength="32" required
       placeholder="32-character API key">
<button id="submit" type="submit">Send to TV</button>
</form>
<p id="status" role="status" aria-live="polite"></p>
<p>Only use this page on your trusted home network.</p>
</main>
<script>
(function () {
  'use strict';
  const token = location.hash.slice(1);
  history.replaceState(null, '', location.pathname);
  const form = document.getElementById('keyForm');
  const input = document.getElementById('key');
  const button = document.getElementById('submit');
  const status = document.getElementById('status');

  if (!/^[a-f0-9]{64}$/i.test(token)) {
    status.textContent = 'Invalid pairing link. Scan the TV QR code again.';
    button.disabled = true;
    return;
  }

  form.addEventListener('submit', async function (event) {
    event.preventDefault();
    const key = input.value.trim();
    if (!/^[a-f0-9]{32}$/i.test(key)) {
      status.textContent = 'Enter a valid 32-character TMDB v3 API key.';
      return;
    }
    button.disabled = true;
    status.textContent = 'Sending to TV...';
    try {
      const response = await fetch('/api/key', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Pairing-Token': token
        },
        body: JSON.stringify({key: key}),
        cache: 'no-store',
        credentials: 'omit'
      });
      const result = await response.json();
      status.textContent = result.message || 'Request completed.';
      if (!response.ok || !result.ok) button.disabled = false;
      if (response.ok && result.ok) input.value = '';
    } catch (_) {
      status.textContent = 'Connection failed. Check that phone and TV share the same network.';
      button.disabled = false;
    }
  });
})();
</script>
</body>
</html>
""".trimIndent()
}