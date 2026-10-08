// Tiny Chrome DevTools Protocol helper for the app's WebView. Debug builds only.
// Needs: adb on PATH, a device or emulator with the debug app open, Node 22+.
//   node tools/cdp.mjs eval "<javascript expression>"
//   node tools/cdp.mjs navigate <url>
import { execFileSync } from 'node:child_process';

const adb = (...args) => execFileSync('adb', args, { encoding: 'utf8' }).trim();

const sockets = adb('shell', 'cat', '/proc/net/unix')
  .split('\n')
  .map((line) => line.match(/@(webview_devtools_remote_\d+)/))
  .filter(Boolean)
  .map((m) => m[1]);
if (!sockets.length) {
  console.error('No WebView devtools socket found. Is the debug app open?');
  process.exit(2);
}

const PORT = 9223;
adb('forward', `tcp:${PORT}`, `localabstract:${sockets[0]}`);
const targets = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
const page = targets.find((t) => t.type === 'page');
if (!page) {
  console.error('No page target found.');
  process.exit(3);
}

const ws = new WebSocket(page.webSocketDebuggerUrl);
let nextId = 0;
const pending = new Map();
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  const waiter = msg.id && pending.get(msg.id);
  if (!waiter) return;
  pending.delete(msg.id);
  if (msg.error) waiter.reject(new Error(msg.error.message));
  else waiter.resolve(msg.result);
};
const send = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const id = ++nextId;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
await new Promise((resolve, reject) => {
  ws.onopen = resolve;
  ws.onerror = reject;
});

const [command, argument] = process.argv.slice(2);
if (command === 'eval') {
  const r = await send('Runtime.evaluate', { expression: argument, returnByValue: true, awaitPromise: true });
  console.log(JSON.stringify(r.exceptionDetails ? r.exceptionDetails : r.result.value, null, 2));
} else if (command === 'navigate') {
  await send('Page.navigate', { url: argument });
  console.log('navigating to ' + argument);
} else {
  console.error('usage: eval <javascript> | navigate <url>');
  process.exitCode = 1;
}
ws.close();
setTimeout(() => process.exit(process.exitCode ?? 0), 50);
