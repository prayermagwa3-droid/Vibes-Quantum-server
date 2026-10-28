"use strict";

(() => {
  const byId = id => document.getElementById(id);
  const bridge = () => window.VibesQuantumAdmin;
  let currentAppId = "";

  function status(id, message, kind = "") {
    const node = byId(id);
    if (!node) return;
    node.textContent = String(message || "");
    node.className = `form-status${kind ? ` ${kind}` : ""}`;
  }
  function make(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = String(text);
    return node;
  }
  function appPath(suffix) { return `/api/v1/learning/apps/${encodeURIComponent(currentAppId)}/${suffix}`; }
  function requireAppId() {
    const value = byId("learningAppId")?.value.trim() || "";
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(value)) throw new Error("Enter a valid app ID before loading or saving policy.");
    currentAppId = value;
    return value;
  }
  function renderPolicy(policy) {
    byId("learningPolicyEnabled").checked = policy.enabled === true;
    byId("learningRetentionDays").value = String(policy.retentionDays ?? 30);
    byId("learningDailyQuota").value = String(policy.dailyQuota ?? 500);
    byId("learningPolicyVersion").value = String(policy.policyVersion || "1");
    const selected = new Set(Array.isArray(policy.allowedCategories) ? policy.allowedCategories : []);
    document.querySelectorAll('input[name="learningCategory"]').forEach(input => { input.checked = selected.has(input.value); });
    byId("learningEnabledState").textContent = policy.enabled ? "Enabled" : "Disabled";
    byId("learningPolicyUpdated").textContent = policy.updatedAt && policy.updatedAt !== "1970-01-01T00:00:00Z" ? `Updated ${new Date(policy.updatedAt).toLocaleString()}` : "No explicit policy saved yet";
  }
  function renderSummary(summary) {
    byId("learningSignalCount").textContent = String(summary.retainedSignals ?? 0);
    byId("learningPendingCount").textContent = String(summary.pendingTeachingExamples ?? 0);
    byId("learningDailyCount").textContent = `${summary.signalsToday ?? 0} / ${summary.dailyQuota ?? 0}`;
    byId("learningConsentCount").textContent = `${summary.consentedSubjects ?? 0} currently consented subject(s)`;
    byId("learningEnabledState").textContent = summary.enabled ? "Enabled" : "Disabled";
  }
  function renderFeedback(rows) {
    const container = byId("learningFeedbackList");
    container.replaceChildren();
    if (!rows.length) { container.appendChild(make("div", "registry-empty", "No retained signals for this app yet.")); return; }
    rows.forEach(row => {
      const card = make("article", "source-candidate-card learning-feedback-card");
      const top = make("div", "learning-feedback-top");
      top.appendChild(make("span", "learning-type-pill", String(row.category || row.signalType || "signal").replaceAll("_", " ")));
      top.appendChild(make("span", `learning-review-state${row.status === "PENDING_REVIEW" ? " pending" : ""}`, row.status === "PENDING_REVIEW" ? "Pending review" : "Recorded"));
      card.appendChild(top);
      const value = row.rating ? `Rating: ${row.rating}` : row.taskOutcome ? `Task outcome: ${row.taskOutcome}` : row.errorCategory ? `Error category: ${row.errorCategory}` : row.teachingExample ? `Submitted example: ${row.teachingExample}` : "Signal received";
      card.appendChild(make("p", "source-candidate-excerpt", value));
      card.appendChild(make("small", "source-candidate-reason", `Recorded ${new Date(row.createdAt).toLocaleString()} · expires ${new Date(row.expiresAt).toLocaleDateString()}`));
      container.appendChild(card);
    });
  }
  async function load() {
    const api = bridge();
    if (!api?.getKey()) return;
    byId("learningPanel").hidden = false;
    byId("learningGate").hidden = true;
    let appId;
    try { appId = requireAppId(); } catch (error) { status("learningPolicyStatus", error.message, "error"); return; }
    const button = byId("loadLearningApp");
    if (button) { button.disabled = true; button.textContent = "Loading…"; }
    status("learningPolicyStatus", "Loading tenant policy and aggregate summary…");
    try {
      const headers = api.headers();
      const [policyResult, summaryResult, feedbackResult] = await Promise.all([
        api.requestJson(appPath("policy"), { headers }),
        api.requestJson(appPath("summary"), { headers }),
        api.requestJson(`${appPath("feedback")}?limit=25&offset=0`, { headers })
      ]);
      renderPolicy(policyResult.data);
      renderSummary(summaryResult.data);
      renderFeedback(Array.isArray(feedbackResult.data.feedback) ? feedbackResult.data.feedback : []);
      status("learningPolicyStatus", `Loaded tenant ${appId}. Signal text is not sent to third-party providers by this dashboard.`, "success");
      byId("learningPanel").hidden = false;
      byId("learningGate").hidden = true;
    } catch (error) {
      status("learningPolicyStatus", error.message || "Could not load app policy", "error");
      if (error.status === 401) lock();
    } finally {
      if (button) { button.disabled = false; button.textContent = "Load app policy"; }
    }
  }
  function lock() {
    byId("learningPanel").hidden = true;
    byId("learningGate").hidden = false;
    if (byId("learningAdminKey")) byId("learningAdminKey").value = "";
    status("learningUnlockStatus", "Administrator credential cleared from page memory.", "success");
  }
  byId("learningUnlockForm")?.addEventListener("submit", async event => {
    event.preventDefault();
    const api = bridge();
    const candidate = byId("learningAdminKey").value;
    if (!api || !candidate) return;
    const button = byId("learningUnlockButton");
    if (button) { button.disabled = true; button.textContent = "Verifying…"; }
    status("learningUnlockStatus", "Verifying administrator permissions…");
    try {
      await api.requestJson("/api/v1/keys", { headers: { "X-Vibes-Gateway-Key": candidate } });
      api.setKey(candidate);
      byId("learningAdminKey").value = "";
      status("learningUnlockStatus", "Privacy controls unlocked.", "success");
      byId("learningPanel").hidden = false;
      byId("learningGate").hidden = true;
      if (byId("learningAppId").value.trim()) await load();
    } catch (error) { status("learningUnlockStatus", error.message || "Admin key rejected", "error"); }
    finally { if (button) { button.disabled = false; button.textContent = "Unlock privacy console"; } }
  });
  byId("learningAppForm")?.addEventListener("submit", event => { event.preventDefault(); void load(); });
  byId("learningPolicyForm")?.addEventListener("submit", async event => {
    event.preventDefault();
    const api = bridge(); if (!api?.getKey()) return;
    try {
      requireAppId();
      const allowedCategories = [...document.querySelectorAll('input[name="learningCategory"]:checked')].map(input => input.value);
      if (!allowedCategories.length) throw new Error("Select at least one allowed category. Disable the whole policy to stop sharing.");
      const body = {
        enabled: byId("learningPolicyEnabled").checked,
        allowedCategories,
        retentionDays: Number(byId("learningRetentionDays").value),
        dailyQuota: Number(byId("learningDailyQuota").value),
        policyVersion: byId("learningPolicyVersion").value.trim()
      };
      await api.requestJson(appPath("policy"), { method: "PUT", headers: api.headers(), body });
      status("saveLearningPolicyStatus", "Policy saved. This does not grant user consent; every subject must separately opt in.", "success");
      api.showToast("Tenant learning policy saved", "success");
      await load();
    } catch (error) { status("saveLearningPolicyStatus", error.message || "Could not save policy", "error"); }
  });
  byId("refreshLearning")?.addEventListener("click", () => void load());
  byId("refreshLearningSignals")?.addEventListener("click", () => void load());
  byId("lockLearningButton")?.addEventListener("click", () => { bridge()?.clearKey(); lock(); });
  window.VibesLearning = { load, lock };
})();
