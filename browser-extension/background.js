// Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
// Manifest V3 passive tab handoff service worker (strictly https:// only).

const NATIVE_HOST = "org.ec0.continuity_host";
let lastSentUrl = "";

function isSafeHttpsUrl(url) {
  if (typeof url !== "string" || url.length > 2048) return false;
  try {
    const parsed = new URL(url);
    return parsed.protocol === "https:" && !parsed.username && !parsed.password;
  } catch {
    return false;
  }
}

function sendTabToDaemon(tab) {
  if (!tab || !isSafeHttpsUrl(tab.url)) return;
  if (tab.url === lastSentUrl) return;
  lastSentUrl = tab.url;

  const payload = {
    command: "handoff_tab",
    url: tab.url,
    title: (tab.title || "").slice(0, 128),
    media_position_sec: 0
  };

  try {
    chrome.runtime.sendNativeMessage(NATIVE_HOST, payload, () => {
      // Ignore transient errors when daemon is stopped.
      void chrome.runtime.lastError;
    });
  } catch {
    // Non-fatal
  }
}

chrome.tabs.onActivated.addListener(({ tabId }) => {
  chrome.tabs.get(tabId, (tab) => {
    if (!chrome.runtime.lastError) sendTabToDaemon(tab);
  });
});

chrome.tabs.onUpdated.addListener((_tabId, changeInfo, tab) => {
  if (changeInfo.status === "complete" && tab.active) {
    sendTabToDaemon(tab);
  }
});

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg && msg.type === "HANDOFF_NOW") {
    lastSentUrl = "";
    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (tabs && tabs[0]) {
        sendTabToDaemon(tabs[0]);
        sendResponse({ ok: true, url: tabs[0].url });
      } else {
        sendResponse({ ok: false });
      }
    });
    return true;
  }
  return false;
});
