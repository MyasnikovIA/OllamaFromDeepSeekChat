const recent = [];
const MAX = 500;

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg || msg.type !== "ds_chunk") return;
  recent.push({ t: Date.now(), phase: msg.phase, streamId: msg.streamId, chunk: msg.chunk });
  if (recent.length > MAX) recent.shift();
  sendResponse({ ok: true });
  return true;
});

chrome.runtime.onMessage.addListener((msg, _s, sendResponse) => {
  if (msg && msg.type === "get_recent") {
    sendResponse({ chunks: recent });
    return true;
  }
});