document.getElementById("sendBtn").addEventListener("click", () => {
  const statusEl = document.getElementById("status");
  chrome.runtime.sendMessage({ type: "HANDOFF_NOW" }, (resp) => {
    if (chrome.runtime.lastError || !resp || !resp.ok) {
      statusEl.textContent = "Only https:// tabs can be handed off.";
    } else {
      statusEl.textContent = "Sent to ec0 daemon.";
    }
  });
});
