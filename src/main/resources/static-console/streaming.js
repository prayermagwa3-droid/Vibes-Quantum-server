"use strict";

(() => {
  const byId = id => document.getElementById(id);
  let controller = null;
  let initialized = false;

  function status(message, kind = "") {
    const node = byId("streamFormStatus");
    if (node) { node.textContent = String(message || ""); node.className = `form-status${kind ? ` ${kind}` : ""}`; }
  }
  function streamState(label, kind = "") {
    const node = byId("streamStatus");
    if (!node) return;
    node.className = `streaming-status${kind ? ` ${kind}` : ""}`;
    const dot = node.querySelector("i");
    node.textContent = "";
    if (dot) node.appendChild(dot); else { const led = document.createElement("i"); node.appendChild(led); }
    node.appendChild(document.createTextNode(` ${label}`));
  }
  function appendText(value) {
    const output = byId("streamOutput");
    if (!output || !value) return;
    const nearBottom = output.scrollHeight - output.scrollTop - output.clientHeight < 70;
    if (output.textContent === "Your response will appear here as the server sends each event.") output.textContent = "";
    output.textContent += value;
    if (nearBottom) output.scrollTop = output.scrollHeight;
  }
  function parseEventData(data) {
    if (data === "[DONE]") return { done: true };
    if (!data) return {};
    let payload;
    try { payload = JSON.parse(data); } catch (_) { return {}; }
    if (payload.error) throw new Error(payload.error.message || "The provider returned a stream error.");
    const choice = Array.isArray(payload.choices) ? payload.choices[0] : null;
    const content = choice?.delta?.content;
    if (typeof content === "string") appendText(content);
    else if (Array.isArray(content)) content.forEach(part => { if (typeof part?.text === "string") appendText(part.text); });
    return { done: false };
  }
  async function initialize() {
    if (initialized) return;
    initialized = true;
    const select = byId("streamModel");
    try {
      const response = await fetch("/api/v1/gateway/ai/status", { headers: { Accept: "application/json" }, cache: "no-store", credentials: "same-origin" });
      const info = await response.json();
      select.replaceChildren();
      const models = Array.isArray(info.models) ? info.models : [];
      models.forEach(model => select.appendChild(new Option(model, model)));
      if (!models.length) select.appendChild(new Option("No allowed models configured", ""));
      select.value = models.includes(info.defaultModel) ? info.defaultModel : (models[0] || "");
      if (!info.enabled) status("The gateway has no upstream provider configured yet. Set server-side provider configuration before testing.", "error");
      else if (info.streamingSupported === false) status("This server reports streaming is disabled. Update the gateway server first.", "error");
      else status("Ready. The API key stays in this page's memory and is sent only to this gateway.");
    } catch (_) {
      initialized = false;
      status("Could not load gateway model status. Check the server connection.", "error");
    }
  }
  async function runStream(event) {
    event.preventDefault();
    if (controller) return;
    const key = byId("streamApiKey").value.trim();
    const prompt = byId("streamPrompt").value.trim();
    const model = byId("streamModel").value;
    if (!key) { status("Enter an app API key with the ai:chat scope.", "error"); return; }
    if (!prompt) { status("Enter a prompt to stream.", "error"); return; }
    if (!model) { status("No allowed model is available on the gateway.", "error"); return; }

    controller = new AbortController();
    const activeController = controller;
    const output = byId("streamOutput");
    const start = byId("startStreamButton");
    const stop = byId("cancelStreamButton");
    const meta = byId("streamMeta");
    output.textContent = "";
    output.classList.add("streaming");
    output.classList.remove("error");
    start.disabled = true; start.classList.add("button-loading"); start.textContent = "Streaming…";
    stop.disabled = false;
    if (meta) meta.textContent = `Model ${model}`;
    streamState("Connecting", "live");
    status("Connecting to the gateway, then rendering each received text delta…");
    let chunks = 0;
    try {
      const response = await fetch("/v1/chat/completions", {
        method: "POST", credentials: "same-origin", cache: "no-store", signal: activeController.signal,
        headers: { "Content-Type": "application/json", Accept: "text/event-stream", "X-Vibes-API-Key": key },
        body: JSON.stringify({ model, stream: true, messages: [{ role: "user", content: prompt }], max_completion_tokens: 1024, temperature: 0.7 })
      });
      if (!response.ok) {
        const raw = await response.text();
        let message = `HTTP ${response.status}: ${response.statusText}`;
        try { const data = JSON.parse(raw); if (data.error) message = `${message} — ${data.error}${data.hint ? ` (${data.hint})` : ""}`; } catch (_) { /* Don't print provider internals from an error body. */ }
        throw new Error(message);
      }
      if (!response.body || !response.headers.get("content-type")?.includes("text/event-stream")) throw new Error("The gateway response did not use server-sent events. Confirm that the Phase 8 server is deployed.");
      if (meta) meta.textContent = `Request ${response.headers.get("X-Request-ID") || "accepted"} · ${model}`;
      streamState("Receiving", "live");
      status("Response connected. Text is arriving incrementally.", "success");
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let dataLines = [];
      let reachedDone = false;
      while (!reachedDone) {
        const { value, done } = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
        const lines = buffer.split("\n");
        buffer = lines.pop() || "";
        if (done && buffer) lines.push(buffer);
        for (const rawLine of lines) {
          const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;
          if (line === "") {
            if (dataLines.length) {
              const result = parseEventData(dataLines.join("\n"));
              chunks += 1;
              dataLines = [];
              if (result.done) { reachedDone = true; break; }
            }
          } else if (line.startsWith("data:")) dataLines.push(line.slice(5).replace(/^ /, ""));
        }
        if (done) {
          if (dataLines.length) parseEventData(dataLines.join("\n"));
          break;
        }
        if (reachedDone) { await reader.cancel().catch(() => {}); break; }
      }
      output.classList.remove("streaming");
      streamState("Complete", "done");
      status(`Stream finished. ${chunks} event(s) processed.`, "success");
      if (meta) meta.textContent = `${chunks} events · complete`;
      if (!output.textContent.trim()) output.textContent = "The stream completed without text content.";
    } catch (error) {
      output.classList.remove("streaming");
      if (error.name === "AbortError") {
        streamState("Stopped"); status("Stream stopped by the user.");
        if (meta) meta.textContent = `${chunks} events · stopped`;
      } else {
        streamState("Error", "error"); output.classList.add("error");
        status(error.message || "Streaming failed.", "error");
        if (meta) meta.textContent = "Request failed";
      }
    } finally {
      if (controller === activeController) controller = null;
      start.disabled = false; start.classList.remove("button-loading"); start.textContent = "▶ Start streaming";
      stop.disabled = true;
    }
  }
  byId("streamForm")?.addEventListener("submit", runStream);
  byId("cancelStreamButton")?.addEventListener("click", () => controller?.abort());
  byId("clearStreamButton")?.addEventListener("click", () => {
    if (controller) controller.abort();
    byId("streamOutput").textContent = "Your response will appear here as the server sends each event.";
    byId("streamOutput").classList.remove("error", "streaming");
    byId("streamMeta").textContent = "Waiting for request";
    streamState("Idle");
    status("Output cleared. The app key is held only in this page; reload or erase the field to clear it.");
  });
  window.VibesStreaming = { initialize };
})();
