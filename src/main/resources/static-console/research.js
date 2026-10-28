"use strict";

(() => {
  const byId = id => document.getElementById(id);
  const bridge = () => window.VibesQuantumAdmin;
  let loadSequence = 0;

  function setStatus(id, message, kind = "") {
    const element = byId(id);
    if (!element) return;
    element.textContent = String(message || "");
    element.className = `form-status${kind ? ` ${kind}` : ""}`;
  }

  function safeDate(value) {
    const date = value ? new Date(value) : null;
    return date && !Number.isNaN(date.getTime()) ? date.toLocaleString() : "—";
  }

  function make(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = String(text);
    return element;
  }

  function resetList(id, message) {
    const list = byId(id);
    if (list) {
      list.replaceChildren();
      list.appendChild(make("div", "registry-empty", message));
    }
  }

  function renderConnectorHealth(snapshot) {
    const container = byId("sourceConnectorHealth");
    if (!container) return;
    container.replaceChildren();
    const connectors = Array.isArray(snapshot.connectors) ? snapshot.connectors : [];
    if (!connectors.length) {
      container.appendChild(make("div", "registry-empty", "No source connectors are registered."));
      return;
    }
    connectors.forEach(connector => {
      const row = make("article", "source-connector-row");
      const heading = make("div", "source-connector-row-heading");
      const identity = make("div", "source-connector-identity");
      identity.append(make("strong", "", connector.name || connector.id || "Connector"));
      identity.append(make("small", "", connector.id || ""));
      const statusName = connector.status || "unknown";
      const statusClass = statusName === "ready" ? "healthy" : statusName === "degraded" ? "degraded" : "inactive";
      const status = make("span", `source-health-badge ${statusClass}`, statusName);
      heading.append(identity, status);
      row.appendChild(heading);
      row.appendChild(make("p", "", connector.note || "No connector description supplied."));
      const details = make("div", "source-connector-details");
      details.append(make("span", "", `Configured: ${connector.configured ? "yes" : "no"}`));
      details.append(make("span", "", `Runs: ${Number(connector.runs) || 0}`));
      details.append(make("span", "", `Candidates: ${Number(connector.candidates) || 0}`));
      details.append(make("span", "", `Last run: ${safeDate(connector.lastRunAt)}`));
      row.appendChild(details);
      if (connector.lastError) row.appendChild(make("p", "source-error-note", `Last error: ${connector.lastError}`));
      container.appendChild(row);
      document.querySelectorAll(`input[name="connectorIds"][value="${CSS.escape(connector.id)}"]`).forEach(input => {
        input.disabled = !snapshot.enabled || !connector.configured;
        const label = input.closest("label");
        if (label) label.classList.toggle("option-disabled", input.disabled);
      });
    });
    const feature = byId("sourceFeatureState");
    if (feature) feature.textContent = snapshot.enabled ? "Enabled · manual only" : "Disabled by configuration";
    const pending = byId("sourcePendingCount");
    if (pending) pending.textContent = String(snapshot.pendingCandidates ?? 0);
    const budget = snapshot.budgets || {};
    if (byId("sourceBudgetValue")) byId("sourceBudgetValue").textContent = `${budget.maxRequestsPerRun ?? "—"} requests`;
    if (byId("sourceBudgetDetails")) byId("sourceBudgetDetails").textContent = `${formatBytes(budget.maxBytesPerRun)} per run · ${budget.maxCandidatesPerRun ?? "—"} candidates max`;
    const runButton = byId("runSourceLearning");
    if (runButton) runButton.disabled = !snapshot.enabled;
    if (!snapshot.enabled) setStatus("sourceRunStatus", "Source learning is disabled. Set VIBES_SOURCE_CONNECTORS_ENABLED=true on the server and restart it.", "error");
  }

  function formatBytes(value) {
    const bytes = Math.max(0, Number(value) || 0);
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KiB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
  }

  function renderCandidates(items, total) {
    const container = byId("sourceCandidates");
    if (!container) return;
    container.replaceChildren();
    const candidates = Array.isArray(items) ? items : [];
    if (byId("sourceCandidateCount")) byId("sourceCandidateCount").textContent = `${total ?? candidates.length} pending`;
    if (!candidates.length) {
      container.appendChild(make("div", "registry-empty", "No pending evidence candidates yet. Run a targeted research query to create review items."));
      return;
    }
    candidates.forEach(candidate => {
      const card = make("article", "source-candidate-card");
      const meta = make("div", "source-candidate-meta");
      meta.append(make("span", "source-health-badge pending", candidate.status || "PENDING_REVIEW"));
      meta.append(make("span", "", `${candidate.connectorId || "source"} · ${safeDate(candidate.retrievedAt)}`));
      card.appendChild(meta);
      card.appendChild(make("h3", "", candidate.title || "Untitled source"));
      card.appendChild(make("p", "source-candidate-excerpt", candidate.excerpt || "No short excerpt was provided."));
      card.appendChild(make("p", "source-candidate-publisher", `${candidate.publisher || "Publisher unknown"} · ${candidate.sourceType || "source"}`));
      const link = make("a", "source-reference-link", "Open source reference ↗");
      try {
        const url = new URL(candidate.sourceUrl);
        if (url.protocol === "https:" && !url.username && !url.password) {
          link.href = url.toString();
          link.target = "_blank";
          link.rel = "noopener noreferrer";
        } else {
          link.removeAttribute("href");
          link.setAttribute("aria-disabled", "true");
        }
      } catch (_) {
        link.removeAttribute("href");
        link.setAttribute("aria-disabled", "true");
      }
      card.appendChild(link);
      card.appendChild(make("small", "source-license-notes", candidate.licenseNotes || "No license notes supplied; review before reuse."));
      card.appendChild(make("small", "source-candidate-reason", `Research reason: ${candidate.reasonForResearch || "unspecified"} · source hash: ${(candidate.contentSha256 || "").slice(0, 12)}`));
      container.appendChild(card);
    });
  }

  function renderRunHistory(runs) {
    const container = byId("sourceRunHistory");
    if (!container) return;
    container.replaceChildren();
    if (!Array.isArray(runs) || !runs.length) {
      container.appendChild(make("div", "registry-empty", "No research runs recorded."));
      return;
    }
    runs.forEach(run => {
      const row = make("article", "source-run-row");
      const top = make("div", "source-run-row-top");
      top.append(make("strong", "", run.status || "unknown"));
      top.append(make("small", "", safeDate(run.finishedAt)));
      row.appendChild(top);
      row.appendChild(make("p", "", run.query || "No query recorded"));
      row.appendChild(make("small", "", `${run.candidateCount ?? 0} candidate(s) · ${run.duplicateCount ?? 0} duplicate(s) · ${run.requestCount ?? 0} request(s) · ${formatBytes(run.bytesRead)}`));
      if (Array.isArray(run.errors) && run.errors.length) row.appendChild(make("p", "source-error-note", run.errors.join("; ")));
      container.appendChild(row);
    });
  }

  async function load() {
    const api = bridge();
    const key = api?.getKey();
    if (!key) return;
    const sequence = ++loadSequence;
    const refresh = byId("refreshResearch");
    if (refresh) { refresh.disabled = true; refresh.textContent = "↻ Loading…"; }
    try {
      const headers = api.headers();
      const [healthResponse, candidateResponse] = await Promise.all([
        api.requestJson("/api/v1/research/connectors", { headers }),
        api.requestJson("/api/v1/research/candidates?limit=25&offset=0", { headers })
      ]);
      if (sequence !== loadSequence) return;
      const snapshot = healthResponse.data || {};
      renderConnectorHealth(snapshot);
      renderCandidates(candidateResponse.data?.candidates, candidateResponse.data?.total);
      renderRunHistory(snapshot.recentRuns || []);
      if (byId("researchPanel")) byId("researchPanel").hidden = false;
      if (byId("researchGate")) byId("researchGate").hidden = true;
      if (byId("researchAdminKey")) byId("researchAdminKey").value = "";
      setStatus("researchUnlockStatus", "Administrator access verified.", "success");
    } catch (error) {
      if (error.status === 401) {
        api.clearKey();
        setStatus("researchUnlockStatus", "Admin key was rejected or expired. Unlock again.", "error");
      } else if (error.status === 503 && String(error.message).includes("HTTP 503")) {
        setStatus("researchUnlockStatus", error.message, "error");
      } else {
        api.showToast(error.message || "Could not load source learning.", "error");
      }
    } finally {
      if (refresh) { refresh.disabled = false; refresh.textContent = "↻ Refresh"; }
    }
  }

  byId("researchUnlockForm")?.addEventListener("submit", async event => {
    event.preventDefault();
    const api = bridge();
    const input = byId("researchAdminKey");
    const candidate = input?.value || "";
    const button = byId("researchUnlockButton");
    if (!candidate || !api) return;
    if (button) { button.disabled = true; button.textContent = "Verifying…"; }
    setStatus("researchUnlockStatus", "Checking server credentials…");
    try {
      await api.requestJson("/api/v1/research/connectors", { headers: { "X-Vibes-Gateway-Key": candidate } });
      api.setKey(candidate);
      if (input) input.value = "";
      if (byId("researchPanel")) byId("researchPanel").hidden = false;
      if (byId("researchGate")) byId("researchGate").hidden = true;
      await load();
    } catch (error) {
      setStatus("researchUnlockStatus", error.status === 401 ? "That admin key was rejected." : error.message, "error");
    } finally {
      if (button) { button.disabled = false; button.textContent = "Unlock research panel"; }
    }
  });

  byId("sourceRunForm")?.addEventListener("submit", async event => {
    event.preventDefault();
    const api = bridge();
    if (!api?.getKey()) {
      setStatus("sourceRunStatus", "Unlock the research panel first.", "error");
      return;
    }
    const query = byId("sourceQuery")?.value.trim() || "";
    const reasonForResearch = byId("sourceGapReason")?.value || "MISSING_EVIDENCE";
    const connectorIds = Array.from(document.querySelectorAll('input[name="connectorIds"]:checked')).map(input => input.value);
    if (query.length < 3 || query.length > 300) { setStatus("sourceRunStatus", "Enter a focused question between 3 and 300 characters.", "error"); return; }
    if (!connectorIds.length) { setStatus("sourceRunStatus", "Select at least one configured connector.", "error"); return; }
    const button = byId("runSourceLearning");
    if (button) { button.disabled = true; button.textContent = "Searching…"; }
    setStatus("sourceRunStatus", "Running a manual, budget-limited search. Retrieved text will not be executed or auto-approved.");
    try {
      const { data } = await api.requestJson("/api/v1/research/runs", {
        method: "POST", headers: api.headers(),
        body: { query, reasonForResearch, connectorIds }, timeoutMs: 50000
      });
      const run = data.run || {};
      setStatus("sourceRunStatus", `${run.status || "Run finished"}: ${run.candidateCount ?? 0} candidate(s) added, ${run.duplicateCount ?? 0} duplicate(s); ${run.requestCount ?? 0} request(s), ${formatBytes(run.bytesRead)} received.${(run.errors || []).length ? ` Errors: ${run.errors.join("; ")}` : ""}`, run.status === "FAILED" ? "error" : "success");
      if (byId("sourceQuery")) byId("sourceQuery").value = "";
      if (Array.isArray(data.candidates) && data.candidates.length) {
        renderCandidates(data.candidates, data.candidates.length);
      }
      await load();
    } catch (error) {
      setStatus("sourceRunStatus", error.message || "Research run failed.", "error");
    } finally {
      if (button) { button.disabled = false; button.textContent = "Search for evidence"; }
    }
  });

  byId("refreshResearch")?.addEventListener("click", () => void load());
  byId("lockResearchButton")?.addEventListener("click", () => {
    loadSequence += 1;
    bridge()?.clearKey();
    if (byId("researchPanel")) byId("researchPanel").hidden = true;
    if (byId("researchGate")) byId("researchGate").hidden = false;
    setStatus("researchUnlockStatus", "Admin credential cleared from page memory.", "success");
    bridge()?.showToast("Research console locked", "success");
  });

  window.VibesResearch = { load };
})();
