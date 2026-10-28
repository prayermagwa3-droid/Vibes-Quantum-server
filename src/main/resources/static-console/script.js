"use strict";

const PUBLIC_GATEWAY_URL = "https://vibesquantum.duckdns.org";
const byId = id => document.getElementById(id);
const drawer = byId("appDrawer");
const drawerOverlay = byId("drawerOverlay");
const menuToggle = byId("menuToggle");
const drawerClose = byId("drawerClose");
const viewLabels = { dashboard: "Overview", apps: "Connected apps", research: "Source learning", curriculum: "Curriculum lab", learning: "Privacy learning", api: "API explorer", keys: "API keys", account: "Google account", settings: "Settings & docs", connections: "API / Connections", feedback: "Feedback Panel", usage: "Usage", quality: "Languages & quality", evaluations: "Evaluations", operations: "Operations & metrics", help: "Help & API guide" };
let activeView = "dashboard";
let gatewayAdminKey = "";
let googleClientId = "";
let authToken = "";
let authExpiresAt = "";
let dashboardPollTimer = null;
let currentOffset = 0;
let currentTotal = 0;
let lastAppsLoadFailed = false;
let appsRequestId = 0;
let toastTimer = null;
const PAGE_SIZE = 6;

function showToast(message, kind = "") {
  const toast = byId("toast");
  if (!toast) return;
  toast.textContent = String(message || "");
  toast.className = `toast visible${kind ? ` ${kind}` : ""}`;
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => toast.classList.remove("visible"), 3200);
}

function setFormStatus(element, message, kind = "") {
  if (!element) return;
  element.textContent = message || "";
  element.className = `form-status${kind ? ` ${kind}` : ""}`;
}

async function requestJson(path, options = {}) {
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), options.timeoutMs || 10000);
  const headers = { Accept: "application/json", ...(options.headers || {}) };
  if (options.body !== undefined && !Object.keys(headers).some(name => name.toLowerCase() === "content-type")) {
    headers["Content-Type"] = "application/json";
  }
  try {
    const response = await fetch(path, {
      method: options.method || "GET",
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal
    });
    const raw = await response.text();
    let data;
    try { data = raw ? JSON.parse(raw) : null; } catch (_) { data = raw; }
    if (!response.ok) {
      const message = data && typeof data === "object" && data.error ? data.error : response.statusText;
      const error = new Error(`HTTP ${response.status}: ${message || "Request failed"}`);
      error.status = response.status;
      error.payload = data;
      throw error;
    }
    return { response, data };
  } catch (error) {
    if (error && error.name === "AbortError") throw new Error(`The gateway request timed out after ${Math.max(1, Math.round((options.timeoutMs || 10000) / 1000))} seconds.`);
    throw error;
  } finally {
    window.clearTimeout(timeoutId);
  }
}

function openDrawer() {
  if (!drawer) return;
  drawer.classList.add("open");
  if (drawerOverlay) drawerOverlay.classList.add("open");
  if (menuToggle) menuToggle.setAttribute("aria-expanded", "true");
  document.body.classList.add("drawer-open");
  drawerClose?.focus();
}

function closeDrawer() {
  drawer?.classList.remove("open");
  drawerOverlay?.classList.remove("open");
  if (menuToggle) menuToggle.setAttribute("aria-expanded", "false");
  document.body.classList.remove("drawer-open");
}

function showView(viewName) {
  const next = viewLabels[viewName] ? viewName : "dashboard";
  activeView = next;
  document.querySelectorAll("[data-view]").forEach(section => {
    const selected = section.dataset.view === next;
    section.hidden = !selected;
    section.classList.toggle("active", selected);
  });
  document.querySelectorAll("[data-view-target]").forEach(button => {
    const selected = button.dataset.viewTarget === next;
    button.classList.toggle("active", selected);
    if (selected) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  });
  const label = byId("currentViewLabel");
  if (label) label.textContent = window.VibesLocale?.viewLabel(next) || viewLabels[next];
  closeDrawer();
  if (next === "apps") void loadApps();
  if (next === "research" && gatewayAdminKey) void window.VibesResearch?.load();
  if (next === "curriculum" && gatewayAdminKey) void window.VibesCurriculum?.load();
  if (next === "learning" && gatewayAdminKey) void window.VibesLearning?.load();
  if (next === "api") void window.VibesStreaming?.initialize();
  if (next === "keys" && gatewayAdminKey) void loadApiKeys();
  if (next === "account") void initializeGoogleSignIn();
  if (next === "connections") void checkConnectionEndpoints();
  if (next === "operations") void window.VibesOps?.load();
  window.scrollTo({ top: 0, behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
}

document.querySelectorAll("[data-view-target]").forEach(button => button.addEventListener("click", () => showView(button.dataset.viewTarget)));
document.querySelectorAll("[data-go-view]").forEach(button => button.addEventListener("click", () => showView(button.dataset.goView)));
document.querySelectorAll("[data-home-link]").forEach(link => link.addEventListener("click", event => { event.preventDefault(); showView("dashboard"); }));
menuToggle?.addEventListener("click", () => drawer?.classList.contains("open") ? closeDrawer() : openDrawer());
drawerClose?.addEventListener("click", closeDrawer);
drawerOverlay?.addEventListener("click", closeDrawer);
document.addEventListener("keydown", event => { if (event.key === "Escape") closeDrawer(); });

const year = byId("year");
if (year) year.textContent = String(new Date().getFullYear());

async function copyText(value, success = "Copied to clipboard") {
  try {
    await navigator.clipboard.writeText(value);
    showToast(success, "success");
  } catch (_) {
    const textarea = document.createElement("textarea");
    textarea.value = value;
    textarea.setAttribute("readonly", "");
    textarea.className = "copy-fallback";
    document.body.appendChild(textarea);
    textarea.select();
    const copied = document.execCommand("copy");
    textarea.remove();
    showToast(copied ? success : "Copy failed. Select and copy the value manually.", copied ? "success" : "error");
  }
}
byId("copyGatewayUrl")?.addEventListener("click", () => copyText(PUBLIC_GATEWAY_URL, "Gateway URL copied"));
byId("copyApiBase")?.addEventListener("click", () => copyText(PUBLIC_GATEWAY_URL, "API base URL copied"));
byId("copySettingsUrl")?.addEventListener("click", () => copyText(PUBLIC_GATEWAY_URL, "Gateway URL copied"));
byId("copyHeaderExample")?.addEventListener("click", () => copyText("X-Vibes-API-Key: YOUR_GENERATED_KEY", "Header example copied"));

const statTotal = byId("statTotal");
const statOnline = byId("statOnline");
const statOffline = byId("statOffline");
const statUptime = byId("statUptime");
const statsUpdated = byId("statsUpdated");
const statRegistryMode = byId("statRegistryMode");

function formatUptime(seconds) {
  const value = Math.max(0, Number(seconds) || 0);
  const days = Math.floor(value / 86400);
  const hours = Math.floor((value % 86400) / 3600);
  const minutes = Math.floor((value % 3600) / 60);
  if (days) return `${days}d ${hours}h`;
  if (hours) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}

function setGatewayHealth(online, message) {
  const led = byId("drawerHealthLed");
  const title = byId("drawerHealthTitle");
  const text = byId("drawerHealthText");
  const badge = byId("healthBadge");
  const serviceDot = byId("serviceCheckDot");
  const serviceText = byId("serviceCheckText");
  if (led) led.className = `connection-led ${online ? "online" : "offline"}`;
  if (title) title.textContent = online ? "Gateway online" : "Gateway unavailable";
  if (text) text.textContent = online ? "Health endpoint responding" : "Check server and DNS";
  if (badge) { badge.className = `status-badge ${online ? "online" : "offline"}`; badge.textContent = online ? "Online" : "Offline"; }
  if (serviceDot) serviceDot.className = `service-check-dot ${online ? "ok" : "bad"}`;
  if (serviceText) serviceText.textContent = message || (online ? "Health endpoint responded" : "Unable to reach the health endpoint");
}

async function loadStats() {
  try {
    const [statsResult, healthResult] = await Promise.all([
      requestJson("/api/v1/gateway/stats"),
      requestJson("/api/health")
    ]);
    const stats = statsResult.data || {};
    const health = healthResult.data || {};
    if (statTotal) statTotal.textContent = String(stats.totalApps ?? 0);
    if (statOnline) statOnline.textContent = String(stats.onlineApps ?? 0);
    if (statOffline) statOffline.textContent = String(stats.offlineApps ?? 0);
    if (statUptime) statUptime.textContent = formatUptime(stats.uptimeSeconds);
    if (statsUpdated) statsUpdated.textContent = `Updated ${new Date(stats.generatedAt || Date.now()).toLocaleTimeString()}`;
    if (statRegistryMode) {
      if (!health) statRegistryMode.textContent = "Storage status unavailable";
      else if (!health.registrationEnabled) statRegistryMode.textContent = "Admin key not configured";
      else statRegistryMode.textContent = health.registryMode === "file" ? "Persistent registry enabled" : "Memory only · resets on restart";
      if (health && Array.isArray(health.securityWarnings) && health.securityWarnings.length) statRegistryMode.title = health.securityWarnings.join(" ");
      else statRegistryMode?.removeAttribute("title");
    }
    const drawerCount = byId("drawerAppCount");
    if (drawerCount) drawerCount.textContent = String(stats.totalApps ?? 0);
    setGatewayHealth(true, `Health check passed · ${health.service || "Ktor"} service`);
    return true;
  } catch (error) {
    if (statTotal) statTotal.textContent = "—";
    if (statOnline) statOnline.textContent = "—";
    if (statOffline) statOffline.textContent = "—";
    if (statUptime) statUptime.textContent = "Offline";
    if (statsUpdated) statsUpdated.textContent = "Could not reach gateway";
    if (statRegistryMode) statRegistryMode.textContent = "Storage status unavailable";
    setGatewayHealth(false, "Check the server, hosting and DNS configuration");
    return false;
  }
}

const testApi = byId("testApi");
const apiResult = byId("apiResult");
const endpointPath = byId("endpointPath");
async function checkEndpoint(path, trigger) {
  if (!apiResult) return;
  const label = trigger ? trigger.textContent : "";
  if (trigger) { trigger.disabled = true; trigger.textContent = "Checking…"; }
  if (endpointPath) endpointPath.textContent = path;
  document.querySelectorAll("[data-endpoint]").forEach(button => button.classList.toggle("selected", button.dataset.endpoint === path));
  apiResult.textContent = `GET ${path}\nRequesting…`;
  try {
    const { response, data } = await requestJson(path);
    apiResult.textContent = `HTTP ${response.status} ${response.statusText}\n\n${typeof data === "string" ? data : JSON.stringify(data, null, 2)}`;
  } catch (error) {
    apiResult.textContent = `Request failed. ${error.message || "Check that Vibes Quantum is reachable."}`;
  } finally {
    if (trigger) { trigger.disabled = false; trigger.textContent = label; }
  }
}
testApi?.addEventListener("click", () => checkEndpoint((endpointPath && endpointPath.textContent) || "/api/health", testApi));
document.querySelectorAll("[data-endpoint]").forEach(button => button.addEventListener("click", () => checkEndpoint(button.dataset.endpoint, button)));

const appGrid = byId("appGrid");
const registrySummary = byId("registrySummary");
const appFilters = byId("appFilters");
const appSearch = byId("appSearch");
const appCapability = byId("appCapability");
const appStatus = byId("appStatus");
const refreshApps = byId("refreshApps");
const clearFilters = byId("clearFilters");
const previousPage = byId("previousPage");
const nextPage = byId("nextPage");
const pageSummary = byId("pageSummary");

function addText(parent, tag, className, value) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  element.textContent = value == null ? "" : String(value);
  parent.appendChild(element);
  return element;
}

function renderApps(apps) {
  if (!appGrid) return;
  appGrid.replaceChildren();
  if (!apps.length) {
    const empty = document.createElement("div");
    empty.className = "registry-empty";
    empty.textContent = lastAppsLoadFailed ? "Could not load apps. Check that the gateway server is running, then refresh." : "No apps match these filters. Try a different search or clear the filters.";
    appGrid.appendChild(empty);
    return;
  }
  apps.forEach(app => {
    const card = document.createElement("article");
    card.className = "app-card";
    const top = document.createElement("div");
    top.className = "app-card-top";
    const identity = document.createElement("div");
    identity.className = "app-identity";
    addText(identity, "h4", "app-name", app.displayName || app.appId || "Unnamed app");
    addText(identity, "code", "app-id", app.appId || "unknown-id");
    top.appendChild(identity);
    const status = addText(top, "span", `status-pill ${app.status === "online" ? "is-online" : "is-offline"}`, app.status || "unknown");
    status.setAttribute("aria-label", `App status: ${app.status || "unknown"}`);
    card.appendChild(top);
    const versionLine = document.createElement("div");
    versionLine.className = "app-meta";
    addText(versionLine, "span", "meta-label", "VERSION");
    addText(versionLine, "span", "meta-value", app.version || "—");
    card.appendChild(versionLine);
    if (app.baseUrl) {
      const link = document.createElement("a");
      link.className = "app-url";
      link.href = app.baseUrl;
      link.textContent = app.baseUrl;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      link.setAttribute("aria-label", `Open registered URL for ${app.displayName || app.appId}`);
      card.appendChild(link);
    }
    const caps = document.createElement("div");
    caps.className = "capability-list";
    if (Array.isArray(app.capabilities) && app.capabilities.length) {
      app.capabilities.slice(0, 8).forEach(capability => addText(caps, "span", "capability-chip", capability));
      if (app.capabilities.length > 8) addText(caps, "span", "capability-chip more-chip", `+${app.capabilities.length - 8}`);
    } else addText(caps, "span", "muted-chip", "No capabilities listed");
    card.appendChild(caps);
    addText(card, "p", "app-seen", `Last heartbeat: ${app.lastSeenAt ? new Date(app.lastSeenAt).toLocaleString() : "unknown"}`);
    appGrid.appendChild(card);
  });
}

async function loadApps() {
  if (!appGrid) return;
  const requestId = ++appsRequestId;
  if (refreshApps) { refreshApps.disabled = true; refreshApps.textContent = "↻ Loading…"; }
  if (registrySummary) registrySummary.textContent = "Searching the app directory…";
  const params = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(currentOffset) });
  if (appSearch?.value.trim()) params.set("q", appSearch.value.trim());
  if (appCapability?.value.trim()) params.set("capability", appCapability.value.trim());
  if (appStatus?.value) params.set("status", appStatus.value);
  try {
    const { data } = await requestJson(`/api/v1/apps?${params.toString()}`);
    if (requestId !== appsRequestId) return;
    const apps = Array.isArray(data.apps) ? data.apps : [];
    currentTotal = Number(data.total ?? data.count ?? apps.length);
    lastAppsLoadFailed = false;
    renderApps(apps);
    if (registrySummary) registrySummary.textContent = `${currentTotal} matching app${currentTotal === 1 ? "" : "s"}`;
    const start = currentTotal === 0 ? 0 : currentOffset + 1;
    const end = Math.min(currentOffset + apps.length, currentTotal);
    if (pageSummary) pageSummary.textContent = `${start}–${end} of ${currentTotal}`;
    if (previousPage) previousPage.disabled = currentOffset <= 0;
    if (nextPage) nextPage.disabled = currentOffset + apps.length >= currentTotal || apps.length === 0;
  } catch (_) {
    if (requestId !== appsRequestId) return;
    lastAppsLoadFailed = true;
    currentTotal = 0;
    renderApps([]);
    if (registrySummary) registrySummary.textContent = "Directory unavailable";
    if (pageSummary) pageSummary.textContent = "—";
    if (previousPage) previousPage.disabled = true;
    if (nextPage) nextPage.disabled = true;
  } finally {
    if (requestId === appsRequestId && refreshApps) { refreshApps.disabled = false; refreshApps.textContent = "↻ Refresh"; }
  }
}
appFilters?.addEventListener("submit", event => { event.preventDefault(); currentOffset = 0; void loadApps(); });
if (appSearch) {
  let searchDelay;
  appSearch.addEventListener("input", () => { window.clearTimeout(searchDelay); searchDelay = window.setTimeout(() => { currentOffset = 0; void loadApps(); }, 280); });
}
appCapability?.addEventListener("change", () => { currentOffset = 0; void loadApps(); });
appStatus?.addEventListener("change", () => { currentOffset = 0; void loadApps(); });
refreshApps?.addEventListener("click", () => void loadApps());
clearFilters?.addEventListener("click", () => {
  if (appSearch) appSearch.value = "";
  if (appCapability) appCapability.value = "";
  if (appStatus) appStatus.value = "";
  currentOffset = 0;
  void loadApps();
});
previousPage?.addEventListener("click", () => { currentOffset = Math.max(0, currentOffset - PAGE_SIZE); void loadApps(); });
nextPage?.addEventListener("click", () => { if (currentOffset + PAGE_SIZE < currentTotal) { currentOffset += PAGE_SIZE; void loadApps(); } });

// The server-side administrator key remains in page memory only. It is never written to localStorage/sessionStorage.
const keyHeaders = () => ({ "X-Vibes-Gateway-Key": gatewayAdminKey });
const keyGateCard = byId("keyGateCard");
const keyManager = byId("keyManager");
const unlockForm = byId("adminUnlockForm");
const unlockStatus = byId("adminUnlockStatus");
const unlockButton = byId("unlockKeysButton");
const keyFormStatus = byId("apiKeyFormStatus");
const keyList = byId("apiKeyList");

async function unlockKeyManager(candidate) {
  const { data } = await requestJson("/api/v1/keys", { headers: { "X-Vibes-Gateway-Key": candidate } });
  gatewayAdminKey = candidate;
  if (keyGateCard) keyGateCard.hidden = true;
  if (keyManager) keyManager.hidden = false;
  renderApiKeys(data.keys || []);
  setFormStatus(unlockStatus, "Key manager unlocked.", "success");
  showToast("API key manager unlocked", "success");
}

unlockForm?.addEventListener("submit", async event => {
  event.preventDefault();
  const candidate = byId("gatewayAdminKey")?.value || "";
  if (!candidate) { setFormStatus(unlockStatus, "Enter the server gateway admin key.", "error"); return; }
  if (unlockButton) { unlockButton.disabled = true; unlockButton.textContent = "Verifying…"; }
  setFormStatus(unlockStatus, "Verifying with the gateway…");
  try {
    await unlockKeyManager(candidate);
    if (byId("gatewayAdminKey")) byId("gatewayAdminKey").value = "";
    if (activeView !== "keys") showView("keys");
  } catch (error) {
    gatewayAdminKey = "";
    setFormStatus(unlockStatus, error.status === 401 ? "That admin key was rejected." : error.message, "error");
  } finally {
    if (unlockButton) { unlockButton.disabled = false; unlockButton.textContent = "Unlock key manager"; }
  }
});

byId("toggleAdminKey")?.addEventListener("click", () => {
  const input = byId("gatewayAdminKey");
  const button = byId("toggleAdminKey");
  if (!input || !button) return;
  input.type = input.type === "password" ? "text" : "password";
  button.textContent = input.type === "password" ? "Show" : "Hide";
});

function renderApiKeys(keys) {
  if (!keyList) return;
  keyList.replaceChildren();
  if (!keys.length) {
    const empty = document.createElement("div");
    empty.className = "registry-empty";
    empty.textContent = "No app API keys have been issued yet. Use the form above to create one.";
    keyList.appendChild(empty);
    return;
  }
  keys.forEach(key => {
    const row = document.createElement("article");
    row.className = "api-key-row";
    const identity = document.createElement("div");
    identity.className = "api-key-ident";
    addText(identity, "strong", "", key.name || "Unnamed key");
    addText(identity, "code", "", key.keyPrefix || "vq_live_••••••");
    row.appendChild(identity);
    const bound = document.createElement("div");
    bound.className = "api-key-cell";
    addText(bound, "small", "", "BOUND APP ID");
    addText(bound, "strong", "", key.appId || "—");
    row.appendChild(bound);
    const state = document.createElement("div");
    state.className = "api-key-cell";
    addText(state, "small", "", "STATUS · PERMISSIONS");
    const badge = addText(state, "span", `key-status${key.revokedAt ? " revoked" : ""}`, key.revokedAt ? "Revoked" : "Active");
    badge.title = (key.scopes || []).join(", ");
    addText(state, "span", "", (key.scopes || []).map(scope => scope.replace("apps:", "").replace("ai:", "AI ")).join(" · ") || "No permissions");
    row.appendChild(state);
    if (key.revokedAt) {
      const revoked = document.createElement("div");
      revoked.className = "api-key-cell";
      addText(revoked, "small", "", "REVOKED AT");
      addText(revoked, "span", "", new Date(key.revokedAt).toLocaleString());
      row.appendChild(revoked);
    } else {
      const button = addText(row, "button", "revoke-key-button", "Revoke key");
      button.type = "button";
      button.addEventListener("click", async () => {
        if (!window.confirm(`Revoke the API key “${key.name}” for ${key.appId}? Any app using it will lose access immediately.`)) return;
        button.disabled = true;
        button.textContent = "Revoking…";
        try {
          await requestJson(`/api/v1/keys/${encodeURIComponent(key.id)}`, { method: "DELETE", headers: keyHeaders() });
          showToast(`Revoked key for ${key.appId}`, "success");
          await loadApiKeys();
        } catch (error) {
          showToast(error.message, "error");
          button.disabled = false;
          button.textContent = "Revoke key";
        }
      });
    }
    keyList.appendChild(row);
  });
}

async function loadApiKeys() {
  if (!gatewayAdminKey) return;
  const refresh = byId("refreshKeys");
  if (refresh) { refresh.disabled = true; refresh.textContent = "↻ Loading…"; }
  try {
    const { data } = await requestJson("/api/v1/keys", { headers: keyHeaders() });
    renderApiKeys(Array.isArray(data.keys) ? data.keys : []);
    setFormStatus(unlockStatus, `${data.count ?? (data.keys || []).length} API key record(s) loaded.`, "success");
  } catch (error) {
    showToast(error.message, "error");
    if (error.status === 401) lockKeyManager(false);
  } finally {
    if (refresh) { refresh.disabled = false; refresh.textContent = "↻ Refresh"; }
  }
}
byId("refreshKeys")?.addEventListener("click", () => void loadApiKeys());

byId("apiKeyForm")?.addEventListener("submit", async event => {
  event.preventDefault();
  if (!gatewayAdminKey) { showToast("Unlock API key management first.", "error"); return; }
  const name = byId("apiKeyName")?.value.trim() || "";
  const appId = byId("apiKeyAppId")?.value.trim() || "";
  const scopes = [];
  if (byId("scopeRegister")?.checked) scopes.push("apps:register");
  if (byId("scopeHeartbeat")?.checked) scopes.push("apps:heartbeat");
  if (byId("scopeChat")?.checked) scopes.push("ai:chat");
  if (byId("scopeModels")?.checked) scopes.push("ai:models");
  if (byId("scopeLearning")?.checked) scopes.push("learning:feedback");
  if (byId("scopeAgents")?.checked) scopes.push("agents:run");
  if (!scopes.length) { setFormStatus(keyFormStatus, "Select at least one permission.", "error"); return; }
  const button = byId("createApiKeyButton");
  if (button) { button.disabled = true; button.textContent = "Generating…"; }
  setFormStatus(keyFormStatus, "Generating and saving key digest…");
  try {
    const { data } = await requestJson("/api/v1/keys", {
      method: "POST", headers: keyHeaders(), body: { name, appId, scopes }
    });
    const reveal = byId("newKeyReveal");
    const value = byId("newApiKeyValue");
    if (value) value.textContent = data.apiKey;
    if (reveal) reveal.hidden = false;
    const usage = byId("newKeyUsage");
    if (usage) usage.textContent = `Bound app ID: ${data.key.appId} · permissions: ${data.key.scopes.join(", ")}. Send the key in the X-Vibes-API-Key request header.`;
    setFormStatus(keyFormStatus, "API key issued. Copy the secret now; it cannot be recovered later.", "success");
    byId("apiKeyName").value = "";
    byId("apiKeyAppId").value = "";
    await loadApiKeys();
    reveal?.scrollIntoView({ behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "nearest" });
  } catch (error) {
    setFormStatus(keyFormStatus, error.message, "error");
  } finally {
    if (button) { button.disabled = false; button.textContent = "✦ Generate API key"; }
  }
});

byId("copyNewApiKey")?.addEventListener("click", () => copyText(byId("newApiKeyValue")?.textContent || "", "API key copied — store it securely"));
byId("dismissKeyReveal")?.addEventListener("click", () => { if (byId("newKeyReveal")) byId("newKeyReveal").hidden = true; if (byId("newApiKeyValue")) byId("newApiKeyValue").textContent = ""; });

function lockKeyManager(showMessage = true) {
  gatewayAdminKey = "";
  if (keyGateCard) keyGateCard.hidden = false;
  if (keyManager) keyManager.hidden = true;
  if (byId("gatewayAdminKey")) byId("gatewayAdminKey").value = "";
  if (byId("newApiKeyValue")) byId("newApiKeyValue").textContent = "";
  if (byId("newKeyReveal")) byId("newKeyReveal").hidden = true;
  if (byId("researchPanel")) byId("researchPanel").hidden = true;
  if (byId("researchGate")) byId("researchGate").hidden = false;
  if (byId("researchAdminKey")) byId("researchAdminKey").value = "";
  window.VibesLearning?.lock?.();
  if (showMessage) showToast("Admin console locked", "success");
}
byId("lockKeysButton")?.addEventListener("click", () => lockKeyManager(true));

// Google Identity Services is loaded only when its public Web client ID is configured on the server.
function setAccountSignedOut(message, kind = "") {
  const userPanel = byId("authUserPanel");
  if (userPanel) userPanel.hidden = true;
  const buttonSlot = byId("googleButton");
  if (buttonSlot) buttonSlot.hidden = false;
  if (message) setFormStatus(byId("googleSignInStatus"), message, kind);
  const topLabel = byId("topAccountLabel");
  const avatar = byId("topAvatar");
  if (topLabel) topLabel.textContent = "Sign in";
  if (avatar) avatar.textContent = "VQ";
}

function setAccountSignedIn(user) {
  const userPanel = byId("authUserPanel");
  if (userPanel) userPanel.hidden = false;
  const buttonSlot = byId("googleButton");
  if (buttonSlot) buttonSlot.hidden = true;
  const name = user?.name || user?.email || "Signed in";
  if (byId("authUserName")) byId("authUserName").textContent = name;
  if (byId("authUserEmail")) byId("authUserEmail").textContent = user?.email || "";
  if (byId("authExpiresAt")) byId("authExpiresAt").textContent = authExpiresAt ? `Session expires ${new Date(authExpiresAt).toLocaleString()}` : "Temporary session";
  if (byId("authUserAvatar")) byId("authUserAvatar").textContent = name.trim().charAt(0).toUpperCase() || "V";
  if (byId("topAccountLabel")) byId("topAccountLabel").textContent = (user?.name || user?.email || "Account").split(" ")[0];
  if (byId("topAvatar")) byId("topAvatar").textContent = name.trim().charAt(0).toUpperCase() || "V";
  setFormStatus(byId("googleSignInStatus"), "You're signed in to this browser session.", "success");
}

async function acceptGoogleCredential(credential) {
  setFormStatus(byId("googleSignInStatus"), "Verifying your Google identity securely…");
  try {
    const { data } = await requestJson("/api/v1/auth/google", { method: "POST", body: { credential } });
    authToken = data.accessToken;
    authExpiresAt = data.expiresAt || "";
    try { sessionStorage.setItem("vq_user_session", authToken); sessionStorage.setItem("vq_user_expires", authExpiresAt); } catch (_) { /* Session still works in memory if storage is blocked. */ }
    setAccountSignedIn(data.user || {});
  } catch (error) {
    authToken = "";
    try { sessionStorage.removeItem("vq_user_session"); sessionStorage.removeItem("vq_user_expires"); } catch (_) { /* Storage may be unavailable. */ }
    setAccountSignedOut(error.message, "error");
  }
}

async function restoreUserSession() {
  if (!authToken) {
    try { authToken = sessionStorage.getItem("vq_user_session") || ""; authExpiresAt = sessionStorage.getItem("vq_user_expires") || ""; } catch (_) { authToken = ""; }
  }
  if (!authToken) { setAccountSignedOut("Choose Google Sign-In to create a temporary session."); return false; }
  try {
    const { data } = await requestJson("/api/v1/auth/me", { headers: { Authorization: `Bearer ${authToken}` } });
    authExpiresAt = data.expiresAt || authExpiresAt;
    setAccountSignedIn(data.user || {});
    return true;
  } catch (_) {
    authToken = "";
    authExpiresAt = "";
    try { sessionStorage.removeItem("vq_user_session"); sessionStorage.removeItem("vq_user_expires"); } catch (_) { /* Storage may be unavailable. */ }
    setAccountSignedOut("Your previous session expired. Sign in again.");
    return false;
  }
}

let googleInitPromise = null;
async function initializeGoogleSignIn() {
  if (!googleInitPromise) {
    googleInitPromise = (async () => {
      try {
        const { data } = await requestJson("/api/v1/auth/google/config");
        if (!data.enabled || !data.clientId) {
          const slot = byId("googleButton");
          if (slot) { slot.replaceChildren(); const note = document.createElement("span"); note.className = "google-placeholder"; note.textContent = "Google Sign-In is not configured on this server yet."; slot.appendChild(note); }
          setAccountSignedOut("Set GOOGLE_CLIENT_ID on the server and add https://vibesquantum.duckdns.org to your authorized JavaScript origins.");
          return;
        }
        googleClientId = data.clientId;
        await restoreUserSession();
        if (authToken) return;
        await loadGoogleIdentityScript();
        const googleId = window.google?.accounts?.id;
        const slot = byId("googleButton");
        if (!googleId || !slot) throw new Error("Google Identity Services could not initialize.");
        slot.replaceChildren();
        googleId.initialize({ client_id: googleClientId, callback: response => {
          if (response && response.credential) void acceptGoogleCredential(response.credential);
          else setFormStatus(byId("googleSignInStatus"), "Google did not return a credential. Try again.", "error");
        }, auto_select: false, cancel_on_tap_outside: true });
        googleId.renderButton(slot, { type: "standard", theme: "filled_black", size: "large", text: "signin_with", shape: "pill", width: Math.min(320, Math.max(220, slot.clientWidth || 280)), logo_alignment: "left" });
        setFormStatus(byId("googleSignInStatus"), "Ready. Google will return a signed ID token to verify on the server.");
      } catch (error) {
        const slot = byId("googleButton");
        if (slot) slot.textContent = "Could not initialize Google Sign-In.";
        setFormStatus(byId("googleSignInStatus"), error.message, "error");
      }
    })();
  }
  return googleInitPromise;
}

function loadGoogleIdentityScript() {
  if (window.google?.accounts?.id) return Promise.resolve();
  const existing = document.querySelector('script[data-google-identity="true"]');
  if (existing) return new Promise((resolve, reject) => { existing.addEventListener("load", resolve, { once: true }); existing.addEventListener("error", () => reject(new Error("Google Identity Services script failed to load.")), { once: true }); });
  return new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = "https://accounts.google.com/gsi/client";
    script.async = true;
    script.defer = true;
    script.dataset.googleIdentity = "true";
    script.onload = resolve;
    script.onerror = () => reject(new Error("Google Identity Services script failed to load."));
    document.head.appendChild(script);
  });
}

byId("signOutButton")?.addEventListener("click", async () => {
  const previousToken = authToken;
  authToken = "";
  authExpiresAt = "";
  try { if (previousToken) await requestJson("/api/v1/auth/logout", { method: "POST", headers: { Authorization: `Bearer ${previousToken}` } }); } catch (_) { /* Clear local session even if the server is unreachable. */ }
  try { sessionStorage.removeItem("vq_user_session"); sessionStorage.removeItem("vq_user_expires"); } catch (_) { /* Storage may be unavailable. */ }
  setAccountSignedOut("Signed out of Vibes Quantum.", "success");
  showToast("Signed out", "success");
});
byId("accountTopBtn")?.addEventListener("click", () => showView("account"));

byId("refreshDashboard")?.addEventListener("click", async () => { const button = byId("refreshDashboard"); if (button) { button.disabled = true; button.textContent = "↻ Checking…"; } await loadStats(); if (button) { button.disabled = false; button.textContent = "↻ Refresh status"; } });

function refreshDashboard() {
  if (document.hidden) return;
  void loadStats();
  if (activeView === "apps") void loadApps();
  if (activeView === "keys" && gatewayAdminKey) void loadApiKeys();
}
function startDashboardPolling() {
  if (dashboardPollTimer !== null || document.hidden) return;
  dashboardPollTimer = window.setInterval(refreshDashboard, 30000);
}
function stopDashboardPolling() {
  if (dashboardPollTimer === null) return;
  window.clearInterval(dashboardPollTimer);
  dashboardPollTimer = null;
}
document.addEventListener("visibilitychange", () => { if (document.hidden) stopDashboardPolling(); else { refreshDashboard(); startDashboardPolling(); } });
window.addEventListener("online", refreshDashboard);

// Small page-memory bridge for the separately bundled Phase 6 research panel.
// This key is never written to browser storage and is cleared by lockKeyManager().
window.VibesQuantumAdmin = {
  getKey: () => gatewayAdminKey,
  setKey: value => { gatewayAdminKey = String(value || ""); },
  headers: keyHeaders,
  requestJson,
  showToast,
  setFormStatus,
  clearKey: () => lockKeyManager(false)
};

// Initialize the selected screen and poll only while the tab is visible.
void loadStats();
startDashboardPolling();
if (activeView === "account") void initializeGoogleSignIn();


async function checkConnectionEndpoints() {
  const health = byId("connectionHealth");
  const apps = byId("connectionApps");
  if (health) health.textContent = "Checking…";
  if (apps) apps.textContent = "Checking…";
  try {
    const result = await requestJson("/api/health", { timeoutMs: 5000 });
    if (health) health.textContent = `Health endpoint responded (HTTP ${result.response.status}).`;
  } catch (_) {
    try {
      const result = await requestJson("/health", { timeoutMs: 5000 });
      if (health) health.textContent = `Health endpoint responded (HTTP ${result.response.status}).`;
    } catch (_) { if (health) health.textContent = "Health endpoint unavailable at /api/health and /health. This is not a healthy status."; }
  }
  try {
    const result = await requestJson("/api/v1/apps?limit=1", { timeoutMs: 5000 });
    if (apps) apps.textContent = `App API responded (HTTP ${result.response.status}); returned metadata only. No provider secret was requested.`;
  } catch (error) { if (apps) apps.textContent = `App API unavailable or requires authentication (${String(error.message).slice(0, 180)}).`; }
}
byId("checkConnections")?.addEventListener("click", () => void checkConnectionEndpoints());
byId("checkFeedbackApi")?.addEventListener("click", async () => {
  const target = byId("feedbackApiStatus"); if (!target) return; target.textContent = "Checking…";
  try { const r = await requestJson("/api/v1/learning/feedback?limit=1", { timeoutMs: 5000 }); target.textContent = `Feedback endpoint responded (HTTP ${r.response.status}). Review permissions still apply.`; }
  catch (e) { target.textContent = `No usable feedback endpoint confirmed (${String(e.message).slice(0, 180)}). No feedback data has been fabricated.`; }
});
byId("checkUsageApi")?.addEventListener("click", async () => {
  const target = byId("usageApiStatus"); if (!target) return; target.textContent = "Checking…";
  try { const r = await requestJson("/api/v1/usage", { timeoutMs: 5000 }); target.textContent = `Usage endpoint responded (HTTP ${r.response.status}); values will only be displayed after schema validation is implemented.`; }
  catch (e) { target.textContent = `Usage API unavailable or not authorized (${String(e.message).slice(0, 180)}). Metrics remain unknown.`; }
});
