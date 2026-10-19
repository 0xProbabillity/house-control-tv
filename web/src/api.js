const native = window.HouseTV;
export const nativeConfig = native ? JSON.parse(native.getConfig()) : null;
let token = nativeConfig?.token || sessionStorage.getItem('house-tv:key') || '';
if (!native && location.hash.length > 1) {
  token = new URLSearchParams(location.hash.slice(1)).get('key') || token;
  sessionStorage.setItem('house-tv:key', token);
  history.replaceState(null, '', location.pathname);
}
let sessionPromise = null;
async function ensureSession() {
  if (native || token || !['localhost', '127.0.0.1'].includes(location.hostname)) return;
  if (!sessionPromise) sessionPromise = fetch('/api/local-session', { signal: AbortSignal.timeout(4000) })
    .then(async response => { if (!response.ok) throw new Error('Start TV Bridge on your Mac.'); const data = await response.json(); token = data.token || ''; })
    .finally(() => { sessionPromise = null; });
  await sessionPromise;
}
let counter = 0;
const pending = new Map();
window.__tvReply = (id, response) => {
  const p = pending.get(id); if (!p) return;
  pending.delete(id); clearTimeout(p.timer);
  if (response.error) p.reject(new Error(response.error)); else p.resolve(response);
};
export async function api(path, body) {
  if (native) return new Promise((resolve, reject) => {
    const id = ++counter;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('The TV is still waiting for the device service. Try again.')); }, 65000);
    pending.set(id, { resolve, reject, timer });
    native.request(id, path, body ? JSON.stringify(body) : '');
  });
  await ensureSession();
  const response = await fetch(path, {
    method: body ? 'POST' : 'GET', headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(body ? 65000 : 9000),
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || 'Could not connect to your home.');
  return data;
}
export function remoteURL(endpoint) {
  return (endpoint || nativeConfig?.endpoint || location.origin) + '/remote#key=' + encodeURIComponent(token);
}
