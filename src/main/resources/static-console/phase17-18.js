"use strict";

(() => {
  const byId = id => document.getElementById(id);
  const translations = {
    en: {
      overview: "Overview", apps: "Connected apps", source_learning: "Source learning", curriculum: "Curriculum lab",
      privacy_learning: "Privacy learning", security: "Security & audit", api: "API explorer", api_connections: "API / Connections",
      usage: "Usage", quality: "Languages & quality", evaluations: "Evaluations", operations: "Operations & metrics", settings: "Settings & docs"
    },
    sn: {
      overview: "Pfupiso", apps: "Mapurogiramu akabatana", source_learning: "Kudzidza kubva kunobva ruzivo",
      curriculum: "Chikamu chezvidzidzo", privacy_learning: "Kudzidza nezvekuchengetedza zvakavanzika",
      security: "Chengetedzo uye ongororo", api: "API explorer", api_connections: "API / Connections",
      usage: "Mashandisirwo", quality: "Mitauro nehutano", evaluations: "Ongororo dzemhando", operations: "Mashandiro nemametric", settings: "Zvirongwa nemagwaro"
    },
    nd: {
      overview: "Umbono ophelele", apps: "Ama-app axhunyiwe", source_learning: "Ukufunda okuvela emithonjeni",
      curriculum: "Isigaba sekharikhulamu", privacy_learning: "Ukufunda ngokuvikelwa kwemininingwane",
      security: "Ukuvikeleka lokuhlolwa", api: "API explorer", api_connections: "API / Connections",
      usage: "Ukusetshenziswa", quality: "Izilimi lekhwalithi", evaluations: "Ukuhlolwa kwekhwalithi", operations: "Ukusebenza lemethrikhi", settings: "Izilungiselelo lemibhalo"
    }
  };
  const viewKey = {
    dashboard: "overview", apps: "apps", research: "source_learning", curriculum: "curriculum",
    learning: "privacy_learning", security: "security", api: "api", connections: "api_connections", usage: "usage", quality: "quality", evaluations: "evaluations", operations: "operations", settings: "settings"
  };
  let currentLanguage = "en";
  let opsAdminKey = "";
  let lastOpsData = null;

  function updateLanguage(value) {
    currentLanguage = translations[value] ? value : "en";
    document.documentElement.lang = currentLanguage;
    document.querySelectorAll("[data-i18n]").forEach(node => {
      const key = node.dataset.i18n;
      const translated = translations[currentLanguage][key];
      if (translated) node.textContent = translated;
    });
    const notice = byId("languageReviewNotice");
    if (notice) {
      notice.hidden = currentLanguage === "en";
      notice.textContent = currentLanguage === "en" ? "" : (currentLanguage === "sn"
        ? "Shona interface strings are progressive drafts and have not been approved by native-speaker reviewers. Some advanced panels remain in English; health, legal, finance and chemical-safety content must not rely on unreviewed translations."
        : "IsiNdebele interface strings are progressive drafts and have not been approved by native-speaker reviewers. Some advanced panels remain in English; health, legal, finance and chemical-safety content must not rely on unreviewed translations.");
    }
    const breadcrumb = byId("currentViewLabel");
    if (breadcrumb && typeof activeView !== "undefined") breadcrumb.textContent = viewKey[activeView] ? translations[currentLanguage][viewKey[activeView]] : (window.VibesLocale?.viewLabel(activeView) || activeView);
    window.dispatchEvent(new CustomEvent("vibes:language-changed", { detail: { language: currentLanguage, stage: currentLanguage === "en" ? "baseline" : "progressive_draft" } }));
  }

  window.VibesLocale = {
    viewLabel(key) {
      const translatedKey = viewKey[key];
      if (translatedKey && translations[currentLanguage][translatedKey]) return translations[currentLanguage][translatedKey];
      return (typeof viewLabels !== "undefined" && viewLabels[key]) || key;
    },
    currentLanguage: () => currentLanguage,
    stage: () => currentLanguage === "en" ? "baseline" : "progressive_draft"
  };

  byId("uiLanguage")?.addEventListener("change", event => updateLanguage(event.target.value));
  updateLanguage(byId("uiLanguage")?.value || "en");

  function getOpsKey() {
    const local = (byId("opsAdminKey")?.value || "").trim();
    if (local) return local;
    try { return typeof gatewayAdminKey === "string" ? gatewayAdminKey : ""; } catch (_) { return ""; }
  }

  async function adminRequest(path, options = {}) {
    const key = getOpsKey();
    if (!key) throw new Error("Enter the gateway admin key first.");
    return requestJson(path, { ...options, headers: { ...(options.headers || {}), "X-Vibes-Gateway-Key": key }, timeoutMs: options.timeoutMs || 12000 });
  }

  const safeText = value => value === null || value === undefined || value === "" ? "Unavailable" : String(value);
  const fmt = (value, decimals = 1) => Number.isFinite(Number(value)) ? Number(value).toLocaleString(undefined, { maximumFractionDigits: decimals }) : "—";
  const fmtBytes = value => Number.isFinite(Number(value)) ? `${fmt(Number(value) / (1024 * 1024), 1)} MiB` : "—";

  function emptyRow(tbody, columns, message) {
    if (!tbody) return;
    tbody.replaceChildren();
    const tr = document.createElement("tr");
    const td = document.createElement("td");
    td.colSpan = columns;
    td.textContent = message;
    tr.appendChild(td);
    tbody.appendChild(tr);
  }
  function appendRow(tbody, values) {
    const tr = document.createElement("tr");
    values.forEach(value => {
      const td = document.createElement("td");
      td.textContent = safeText(value);
      tr.appendChild(td);
    });
    tbody.appendChild(tr);
  }

  function renderOps(data, session) {
    lastOpsData = data;
    const routeMetrics = Array.isArray(data.httpRequests) ? data.httpRequests : [];
    const provider = data.provider || {};
    const resources = data.resources || {};
    const requests = routeMetrics.reduce((sum, row) => sum + Number(row.count || 0), 0);
    byId("opsRequestCount").textContent = fmt(requests, 0);
    byId("opsProviderCount").textContent = fmt(provider.calls, 0);
    byId("opsProviderLatency").textContent = provider.avgLatencyMs == null ? "—" : `${fmt(provider.avgLatencyMs, 1)} ms`;
    byId("opsHeapUsed").textContent = resources.heap_used?.value == null ? "—" : fmtBytes(resources.heap_used.value);
    byId("opsHeapFoot").textContent = resources.heap_max?.value == null ? "Max heap unavailable" : `Max heap ${fmtBytes(resources.heap_max.value)}`;
    byId("opsProviderErrorFoot").textContent = `${fmt(provider.errors, 0)} errors · ${fmt(provider.retries, 0)} retries · circuit ${provider.circuitState || "unknown"}`;

    const routeBody = byId("opsRouteRows");
    routeBody.replaceChildren();
    if (!routeMetrics.length) emptyRow(routeBody, 7, "No completed requests have been measured in this process yet.");
    else routeMetrics.forEach(row => appendRow(routeBody, [
      `${row.method} ${row.route}`, fmt(row.count, 0), fmt(row.errors, 0), `${fmt(Number(row.errorRate || 0) * 100, 1)}%`, fmt(row.avgLatencyMs, 1), fmt(row.p95LatencyMs, 1), fmt(row.maxLatencyMs, 1)
    ]));

    const resourceBody = byId("opsResourceRows");
    resourceBody.replaceChildren();
    Object.entries(resources).forEach(([key, value]) => appendRow(resourceBody, [key.replaceAll("_", " "), value?.value == null ? "—" : fmt(value.value, 2), value?.unit, value?.status, `${value?.source || "unknown source"}${value?.note ? ` · ${value.note}` : ""}`]));

    const alertList = byId("opsAlertList");
    if (alertList) {
      alertList.replaceChildren();
      const alerts = Array.isArray(data.alerts) ? data.alerts : [];
      if (!alerts.length) {
        const li = document.createElement("li");
        li.textContent = "No threshold alerts are active. Unknown and uninstrumented metrics remain listed below.";
        alertList.appendChild(li);
      } else alerts.forEach(message => {
        const li = document.createElement("li");
        li.textContent = safeText(message);
        alertList.appendChild(li);
      });
    }
    const switches = data.killSwitches || {};
    byId("opsProviderEnabled").checked = switches.providerRoutingEnabled === true;
    byId("opsLearningEnabled").checked = switches.backgroundLearningEnabled === true;
    const limits = data.limits || {};
    byId("opsStatus").textContent = `Loaded ${new Date(data.generatedAt || Date.now()).toLocaleString()}. Timeout ${fmt(limits.providerTimeoutMs, 0)} ms; concurrency cap ${fmt(limits.maxConcurrentProviderCalls, 0)}; retries ${fmt(limits.maxProviderRetries, 0)} by default.`;
    byId("opsUpdatedAt").textContent = `Updated ${new Date(data.generatedAt || Date.now()).toLocaleString()}. Correlation IDs ${data.correlationIdsEnabled ? "enabled" : "unavailable"}.`;

    const sessionBody = byId("opsSessionRows");
    sessionBody.replaceChildren();
    if (!session || typeof session !== "object") {
      emptyRow(sessionBody, 5, "Learning-session telemetry is unavailable in this deployment.");
      byId("opsSessionSummary").textContent = "Live source-learning session not available.";
    } else {
      appendRow(sessionBody, [
        `${session.status || "unknown"} / ${session.phase || "unknown"}${session.currentConnectorId ? ` · ${session.currentConnectorId}` : ""}`,
        fmt(session.requests, 0), fmt(session.downloadedBytes, 0), fmt(session.candidatesCreated, 0), session.checkpointStatus || "unknown"
      ]);
      byId("opsSessionSummary").textContent = `Session ${session.runId || "not assigned"}; started ${session.startedAt ? new Date(session.startedAt).toLocaleString() : "not started"}. These counters describe source-learning metadata collection only; no query text is included here.`;
    }
    byId("opsPanels").hidden = false;
  }

  async function load() {
    const status = byId("opsStatus");
    if (!getOpsKey()) {
      if (status) status.textContent = "Enter the gateway admin key to load metrics.";
      return;
    }
    if (status) status.textContent = "Loading measured runtime metrics…";
    try {
      const [metricsResult, sessionResult] = await Promise.all([
        adminRequest("/api/v1/ops/metrics"),
        adminRequest("/api/v1/ops/learning-session").catch(() => null)
      ]);
      renderOps(metricsResult.data || {}, sessionResult?.data || null);
      if (status) status.textContent = "Operational data loaded. Unknown values remain unavailable.";
    } catch (error) {
      if (status) status.textContent = `Could not load operational metrics. ${error.message || "Check the key and gateway."}`;
      byId("opsPanels").hidden = true;
    }
  }

  byId("opsUnlockForm")?.addEventListener("submit", event => {
    event.preventDefault();
    opsAdminKey = (byId("opsAdminKey")?.value || "").trim();
    void load();
  });
  byId("refreshOpsMetrics")?.addEventListener("click", () => void load());
  byId("opsSwitchForm")?.addEventListener("submit", async event => {
    event.preventDefault();
    const status = byId("opsSwitchStatus");
    if (status) status.textContent = "Saving server-side switches…";
    try {
      const result = await adminRequest("/api/v1/ops/kill-switches", {
        method: "POST",
        body: { providerRoutingEnabled: byId("opsProviderEnabled").checked, backgroundLearningEnabled: byId("opsLearningEnabled").checked }
      });
      if (status) status.textContent = `Saved at ${new Date(result.data.updatedAt || Date.now()).toLocaleString()}. ${result.data.note || "Switches updated."}`;
      await load();
    } catch (error) {
      if (status) status.textContent = `Switch save failed. ${error.message || "Check the server and admin key."}`;
    }
  });

  window.VibesOps = { load };
})();
