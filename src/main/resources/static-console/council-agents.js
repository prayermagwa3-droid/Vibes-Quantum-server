"use strict";

(() => {
  const $ = id => document.getElementById(id);
  let activeTaskId = null;
  let lastTaskKey = null;
  let roleDirectory = [];
  const terminal = new Set(["succeeded", "failed", "timeout", "cancelled"]);

  function setStatus(id, message, kind = "") {
    const node = $(id);
    if (!node) return;
    node.textContent = String(message || "");
    node.className = `form-status${kind ? ` ${kind}` : ""}`;
  }

  function keyHeader(key) { return { "X-Vibes-API-Key": key, Accept: "application/json" }; }

  async function readResponse(response) {
    const raw = await response.text();
    let body = {};
    try { body = raw ? JSON.parse(raw) : {}; } catch (_) { body = { message: raw.slice(0, 500) }; }
    if (!response.ok) {
      const detail = body.error || body.message || response.statusText || "Request failed";
      const hint = body.hint ? ` (${body.hint})` : "";
      throw new Error(`HTTP ${response.status}: ${detail}${hint}`);
    }
    return body;
  }

  function parseEvidence(raw) {
    return String(raw || "").split(/\r?\n/).map((line, index) => line.trim()).filter(Boolean).slice(0, 40).map((line, index) => {
      const parts = line.split("|").map(part => part.trim());
      if (parts.length >= 3) return { id: parts[0] || `S${index + 1}`, url: parts[1] || null, text: parts.slice(2).join(" | ").slice(0, 12000) };
      if (parts.length === 2) return { id: parts[0] || `S${index + 1}`, text: parts[1].slice(0, 12000) };
      return { id: `S${index + 1}`, text: line.slice(0, 12000) };
    });
  }

  function selectedRoleIds() {
    return Array.from(document.querySelectorAll("input[name='councilRole']:checked"))
      .map(input => input.value).filter(Boolean).slice(0, 12);
  }

  function renderRoles() {
    const host = $("councilRoles");
    if (!host) return;
    host.replaceChildren();
    if (!roleDirectory.length) {
      const text = document.createElement("span"); text.className = "phase9-muted"; text.textContent = "Role list unavailable; server defaults will be used."; host.appendChild(text); return;
    }
    roleDirectory.forEach(role => {
      const label = document.createElement("label"); label.className = "phase9-role-option";
      const input = document.createElement("input"); input.type = "checkbox"; input.name = "councilRole"; input.value = role.id; input.checked = Boolean(role.defaultEnabled);
      const copy = document.createElement("span");
      const title = document.createElement("strong"); title.textContent = role.name;
      const purpose = document.createElement("small"); purpose.textContent = role.purpose;
      copy.append(title, purpose); label.append(input, copy); host.appendChild(label);
    });
  }

  async function loadRoles() {
    try {
      const response = await fetch("/api/v1/council/roles", { cache: "no-store", credentials: "same-origin", headers: { Accept: "application/json" } });
      const roles = await readResponse(response);
      roleDirectory = Array.isArray(roles) ? roles : [];
      renderRoles();
    } catch (_) {
      roleDirectory = [];
      renderRoles();
    }
  }

  function formatCouncil(data) {
    const lines = [
      `Mode: ${data.mode || "—"} | Status: ${data.status || "—"} | Qualitative confidence: ${data.confidence || "—"}`,
      `Distinct providers observed: ${Number.isFinite(data.independentProviderCount) ? data.independentProviderCount : "—"} | Estimated spend: $${Number(data.estimatedCostUsd || 0).toFixed(6)}`,
      "",
      "ANSWER",
      data.answer || "No answer was returned.",
      "",
      "PROGRESS",
      ...(Array.isArray(data.progress) ? data.progress.map(item => `• ${item}`) : []),
      "",
      "AGREEMENTS",
      ...(Array.isArray(data.agreements) && data.agreements.length ? data.agreements.map(item => `• ${item}`) : ["• No explicit agreement summary." ]),
      "",
      "DISAGREEMENTS",
      ...(Array.isArray(data.disagreements) && data.disagreements.length ? data.disagreements.map(item => `• ${item}`) : ["• No explicit contradiction was detected; that is not proof of correctness." ]),
      "",
      "UNVERIFIED / REVIEW GAPS",
      ...(Array.isArray(data.unsupportedOrUnverified) && data.unsupportedOrUnverified.length ? data.unsupportedOrUnverified.map(item => `• ${item}`) : ["• No gap was returned; review sources before relying on consequential claims." ]),
      "",
      "EVIDENCE PROVENANCE",
      ...(Array.isArray(data.evidence) && data.evidence.length ? data.evidence.map(item => `• ${item.id}: ${item.title || "Untitled source"}${item.url ? ` — ${item.url}` : ""}${item.duplicateOf ? ` (duplicates ${item.duplicateOf})` : ""}`) : ["• No evidence items were supplied."]),
      "",
      "SHARED-SOURCE NOTES",
      ...(Array.isArray(data.sharedSourceNotes) && data.sharedSourceNotes.length ? data.sharedSourceNotes.map(item => `• ${item}`) : ["• No shared-source notes returned." ]),
      "",
      "NORMALIZED CLAIMS",
      ...(Array.isArray(data.normalizedClaims) && data.normalizedClaims.length ? data.normalizedClaims.slice(0, 30).map(item => `• [${item.candidateId}${item.providerId ? ` / ${item.providerId}` : ""}] ${item.proposition}${item.evidenceIds?.length ? ` (evidence: ${item.evidenceIds.join(", ")})` : " (no matched evidence ID)"}`) : ["• No claims could be normalized."]),
      "",
      `Note: ${data.note || "Model reviews are not independent verification."}`
    ];
    return lines.join("\n");
  }

  function renderMemberResults(members) {
    const host = $("councilMemberResults");
    if (!host) return;
    host.replaceChildren();
    if (!Array.isArray(members) || !members.length) return;
    members.forEach(member => {
      const article = document.createElement("article"); article.className = "phase9-member-item";
      const title = document.createElement("strong"); title.textContent = `${member.role || member.memberId} · ${member.status || "unknown"}${member.providerId ? ` · ${member.providerId}` : ""}`;
      const body = document.createElement("p");
      body.textContent = [member.summary || "Member summary was not included.", member.evidenceIds?.length ? `Evidence IDs: ${member.evidenceIds.join(", ")}` : "No evidence IDs matched.", member.warning || ""].filter(Boolean).join("\n");
      article.append(title, body); host.appendChild(article);
    });
  }

  async function runCouncil(event) {
    event.preventDefault();
    const button = $("runCouncilButton");
    const key = $("councilApiKey").value.trim();
    const prompt = $("councilPrompt").value.trim();
    if (!key || !prompt) { setStatus("councilStatus", "Enter an app key with ai:chat scope and a question.", "error"); return; }
    const budget = Number($("councilBudget").value);
    const maxMembers = Number($("councilMaxMembers").value);
    if (!Number.isFinite(budget) || budget < 0 || budget > 10 || !Number.isInteger(maxMembers) || maxMembers < 1 || maxMembers > 12) {
      setStatus("councilStatus", "Budget must be 0–10 USD and max members must be 1–12.", "error"); return;
    }
    const evidence = parseEvidence($("councilEvidence").value);
    const body = {
      prompt,
      mode: $("councilMode").value,
      enabled: $("councilEnabled").checked,
      evidence,
      selectedMembers: selectedRoleIds(),
      maxMembers,
      maxCostUsd: budget,
      onlineBoost: $("councilOnlineBoost").checked,
      includeMemberSummaries: true
    };
    button.disabled = true; button.classList.add("button-loading"); button.textContent = "Reviewing evidence…";
    $("councilOutput").textContent = "Council request submitted. Waiting for bounded provider/member responses…";
    $("councilMemberResults").replaceChildren();
    $("councilConfidence").textContent = "Working";
    setStatus("councilStatus", "Running candidate generation, selected reviews, source overlap checks, and final synthesis under the request budget.");
    try {
      const response = await fetch("/v1/council/completions", { method: "POST", cache: "no-store", credentials: "same-origin", headers: { ...keyHeader(key), "Content-Type": "application/json" }, body: JSON.stringify(body) });
      const data = await readResponse(response);
      $("councilOutput").textContent = formatCouncil(data);
      $("councilConfidence").textContent = data.confidence || data.status || "Complete";
      $("councilConfidence").className = `status-badge ${data.status === "contested" || data.status === "insufficient" ? "pending" : "online"}`;
      renderMemberResults(data.members);
      setStatus("councilStatus", `Completed with status “${data.status}”. Review disagreements and source provenance before relying on the answer.`, data.status === "contested" || data.status === "insufficient" ? "error" : "");
    } catch (error) {
      $("councilOutput").textContent = error.message || "Council request failed.";
      $("councilConfidence").textContent = "Request failed";
      setStatus("councilStatus", error.message || "Council request failed; other gateway features remain available.", "error");
    } finally {
      button.disabled = false; button.classList.remove("button-loading"); button.textContent = "Run answer synthesis";
    }
  }

  function renderTask(record) {
    if (!record) return;
    activeTaskId = record.id || activeTaskId;
    const badge = $("agentTaskBadge");
    badge.textContent = record.status || "unknown";
    badge.className = `status-badge ${record.status === "succeeded" ? "online" : record.status === "pending_approval" || record.status === "running" || record.status === "queued" ? "pending" : "offline"}`;
    const output = {
      taskId: record.id,
      agent: record.agent,
      action: record.action,
      status: record.status,
      createdAt: record.createdAt,
      updatedAt: record.updatedAt,
      progress: record.progress || [],
      result: record.result || null,
      audit: record.audit || null,
      cancellationRequested: Boolean(record.cancellationRequested)
    };
    $("agentOutput").textContent = JSON.stringify(output, null, 2);
    $("refreshAgentTask").disabled = !activeTaskId;
    $("approveAgentTask").hidden = record.status !== "pending_approval";
    $("cancelAgentTask").disabled = !activeTaskId || terminal.has(record.status);
    if (record.status === "pending_approval") setStatus("agentStatus", "Approval is required. Review this task and click the approval button only when you intend to proceed.");
    else if (terminal.has(record.status)) setStatus("agentStatus", `Task reached terminal status “${record.status}”.`);
  }

  async function submitAgent(event) {
    event.preventDefault();
    const key = $("agentApiKey").value.trim();
    const task = $("agentTask").value.trim();
    if (!key || !task) { setStatus("agentStatus", "Enter an app key with agents:run scope and describe the task.", "error"); return; }
    const action = $("agentAction").value;
    const code = $("agentCode").value;
    if (action === "execute_code" && !code.trim()) { setStatus("agentStatus", "Enter the code to execute, then review the pending task before approving it.", "error"); return; }
    const budget = Number($("agentBudget").value);
    if (!Number.isFinite(budget) || budget < 0 || budget > 3) { setStatus("agentStatus", "Budget must be between 0 and 3 USD.", "error"); return; }
    const button = $("submitAgentButton");
    button.disabled = true; button.classList.add("button-loading"); button.textContent = "Creating task…";
    setStatus("agentStatus", "Submitting one scoped task. Approval-gated actions will not run until separately approved.");
    const random = typeof crypto !== "undefined" && typeof crypto.randomUUID === "function" ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    lastTaskKey = `vq-task-${random}`;
    const body = {
      agent: $("agentKind").value,
      task,
      action,
      language: $("agentLanguage").value,
      code: code.trim() ? code : null,
      context: [],
      evidence: [],
      maxCostUsd: budget,
      onlineBoost: $("agentOnlineBoost").checked
    };
    try {
      const response = await fetch("/v1/agents/tasks", { method: "POST", cache: "no-store", credentials: "same-origin", headers: { ...keyHeader(key), "Content-Type": "application/json", "Idempotency-Key": lastTaskKey }, body: JSON.stringify(body) });
      const record = await readResponse(response);
      activeTaskId = record.id;
      renderTask(record);
      setStatus("agentStatus", record.status === "pending_approval" ? "Task created; nothing consequential has run. Review the record and explicitly approve to continue." : `Task created with status “${record.status}”. Use Refresh task to inspect checkpoints.`, record.status === "failed" ? "error" : "");
    } catch (error) {
      $("agentOutput").textContent = error.message || "Task creation failed.";
      setStatus("agentStatus", error.message || "Task creation failed; retry with a fresh task request if needed.", "error");
    } finally {
      button.disabled = false; button.classList.remove("button-loading"); button.textContent = "Create task";
    }
  }

  async function refreshTask() {
    const key = $("agentApiKey").value.trim();
    if (!key || !activeTaskId) { setStatus("agentStatus", "Enter the same app's agents:run key and create a task first.", "error"); return; }
    try {
      const response = await fetch(`/v1/agents/tasks/${encodeURIComponent(activeTaskId)}`, { cache: "no-store", credentials: "same-origin", headers: keyHeader(key) });
      const record = await readResponse(response);
      renderTask(record);
      setStatus("agentStatus", `Task refreshed: ${record.status}.`, "");
    } catch (error) { setStatus("agentStatus", error.message || "Could not refresh task.", "error"); }
  }

  async function approveTask() {
    const key = $("agentApiKey").value.trim();
    if (!key || !activeTaskId) return;
    const confirmed = window.confirm("Approve this specific consequential action? Only continue if you reviewed the task ID, agent, action, and code shown in the task result. The server still applies its allowlist and sandbox policy.");
    if (!confirmed) return;
    try {
      const response = await fetch(`/v1/agents/tasks/${encodeURIComponent(activeTaskId)}/approve`, { method: "POST", cache: "no-store", credentials: "same-origin", headers: keyHeader(key) });
      const record = await readResponse(response);
      renderTask(record);
      setStatus("agentStatus", `Approval sent for ${activeTaskId}; refresh to follow progress.`, "");
    } catch (error) { setStatus("agentStatus", error.message || "Approval failed.", "error"); }
  }

  async function cancelTask() {
    const key = $("agentApiKey").value.trim();
    if (!key || !activeTaskId) return;
    try {
      const response = await fetch(`/v1/agents/tasks/${encodeURIComponent(activeTaskId)}`, { method: "DELETE", cache: "no-store", credentials: "same-origin", headers: keyHeader(key) });
      const record = await readResponse(response);
      renderTask(record);
      setStatus("agentStatus", `Cancellation requested; task state is “${record.status}”.`, "");
    } catch (error) { setStatus("agentStatus", error.message || "Cancellation failed.", "error"); }
  }

  async function loadCapabilities() {
    try {
      const response = await fetch("/api/v1/agents/capabilities", { cache: "no-store", credentials: "same-origin", headers: { Accept: "application/json" } });
      const data = await readResponse(response);
      $("agentCapabilitiesOutput").textContent = JSON.stringify(data, null, 2);
    } catch (error) { $("agentCapabilitiesOutput").textContent = error.message || "Capability lookup failed."; }
  }

  async function loadReviewQueue() {
    const key = $("reviewAdminKey").value.trim();
    if (!key) { setStatus("reviewQueueStatus", "Enter the gateway administrator key.", "error"); return; }
    try {
      const response = await fetch("/api/v1/council/review-queue?limit=100", { cache: "no-store", credentials: "same-origin", headers: { "X-Vibes-Gateway-Key": key, Accept: "application/json" } });
      const data = await readResponse(response);
      renderReviewItems(Array.isArray(data.items) ? data.items : []);
      setStatus("reviewQueueStatus", `${Number(data.pending || 0)} pending of ${Number(data.total || 0)} retained review entries.`);
    } catch (error) { setStatus("reviewQueueStatus", error.message || "Could not load the admin queue.", "error"); }
  }

  function renderReviewItems(items) {
    const host = $("reviewQueueItems");
    if (!host) return;
    host.replaceChildren();
    if (!items.length) {
      const empty = document.createElement("span"); empty.className = "phase9-muted"; empty.textContent = "No review queue items returned."; host.appendChild(empty); return;
    }
    items.forEach(item => {
      const article = document.createElement("article"); article.className = "phase9-review-item";
      const heading = document.createElement("strong"); heading.textContent = `${item.title || item.kind} · ${item.status || "unknown"}`;
      const summary = document.createElement("p"); summary.textContent = item.summary || "No summary supplied.";
      const meta = document.createElement("div"); meta.className = "phase9-review-meta";
      const kind = document.createElement("span"); kind.textContent = `Type: ${item.kind || "unknown"}`;
      const id = document.createElement("code"); id.textContent = item.id || "unknown";
      const date = document.createElement("span"); date.textContent = `Created: ${item.createdAt || "unknown"}`;
      meta.append(kind, id, date); article.append(heading, summary, meta);
      if (item.status === "pending") {
        const resolution = document.createElement("textarea"); resolution.className = "phase9-review-resolution"; resolution.rows = 2; resolution.maxLength = 1000; resolution.placeholder = "Required review decision / resolution (no secrets).";
        const actions = document.createElement("div"); actions.className = "phase9-review-actions";
        const reviewed = document.createElement("button"); reviewed.type = "button"; reviewed.className = "button button-primary"; reviewed.textContent = "Mark reviewed";
        const dismiss = document.createElement("button"); dismiss.type = "button"; dismiss.className = "button button-secondary"; dismiss.textContent = "Dismiss";
        reviewed.addEventListener("click", () => resolveReview(item.id, "reviewed", resolution.value));
        dismiss.addEventListener("click", () => resolveReview(item.id, "dismissed", resolution.value));
        actions.append(reviewed, dismiss); article.append(resolution, actions);
      } else if (item.resolution) {
        const resolved = document.createElement("p"); resolved.textContent = `Resolution: ${item.resolution}`; article.appendChild(resolved);
      }
      host.appendChild(article);
    });
  }

  async function resolveReview(id, status, resolution) {
    const key = $("reviewAdminKey").value.trim();
    if (!key || !resolution.trim()) { setStatus("reviewQueueStatus", "Enter the gateway admin key and a short resolution before submitting.", "error"); return; }
    try {
      const response = await fetch(`/api/v1/council/review-queue/${encodeURIComponent(id)}/review`, {
        method: "POST", cache: "no-store", credentials: "same-origin",
        headers: { "X-Vibes-Gateway-Key": key, "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ status, resolution: resolution.trim() })
      });
      const result = await readResponse(response);
      setStatus("reviewQueueStatus", `Review ${result.id} marked “${result.status}”.`);
      await loadReviewQueue();
    } catch (error) { setStatus("reviewQueueStatus", error.message || "Could not update review item.", "error"); }
  }

  function init() {
    $("councilForm")?.addEventListener("submit", runCouncil);
    $("agentForm")?.addEventListener("submit", submitAgent);
    $("refreshAgentTask")?.addEventListener("click", refreshTask);
    $("approveAgentTask")?.addEventListener("click", approveTask);
    $("cancelAgentTask")?.addEventListener("click", cancelTask);
    $("refreshAgentCapabilities")?.addEventListener("click", loadCapabilities);
    $("refreshReviewQueue")?.addEventListener("click", loadReviewQueue);
    loadRoles(); loadCapabilities();
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init, { once: true }); else init();
})();
