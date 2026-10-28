"use strict";

(() => {
  const byId = id => document.getElementById(id);
  const bridge = () => window.VibesQuantumAdmin;
  let snapshot = null;
  let editingCurriculumId = null;

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
  function render(snapshotValue) {
    snapshot = snapshotValue || {};
    const curricula = Array.isArray(snapshot.curricula) ? snapshot.curricula : [];
    byId("curriculumCount").textContent = String(curricula.length);
    byId("approvedClaimCount").textContent = String(snapshot.approvedClaims ?? 0);
    byId("syntheticCandidateCount").textContent = String(snapshot.candidateQuestions ?? 0);
    const select = byId("generateCurriculumId");
    const previous = select.value;
    select.replaceChildren();
    if (!curricula.length) select.appendChild(new Option("Create a curriculum first", ""));
    curricula.forEach(item => {
      const option = new Option(`${item.title} · ${item.domain} · ${item.difficulty}`, item.id);
      option.disabled = item.enabled === false;
      select.appendChild(option);
    });
    if (curricula.some(item => item.id === previous)) select.value = previous;
    byId("generateQuestionsButton").disabled = !curricula.some(item => item.enabled);
    renderCurricula(curricula);
    renderQuestions(Array.isArray(snapshot.questions) ? snapshot.questions : []);
    byId("curriculumPanel").hidden = false;
    byId("curriculumGate").hidden = true;
  }
  function renderCurricula(items) {
    const container = byId("curriculumList");
    container.replaceChildren();
    if (!items.length) { container.appendChild(make("div", "registry-empty", "No curricula created yet.")); return; }
    items.forEach(item => {
      const card = make("article", "source-candidate-card curriculum-item-card");
      card.appendChild(make("h3", "", item.title));
      card.appendChild(make("p", "source-candidate-excerpt", `${item.domain} · ${item.difficulty} · ${item.enabled ? "enabled" : "disabled"} · sort ${item.sortOrder}`));
      card.appendChild(make("small", "source-candidate-reason", `ID: ${item.id}`));
      card.appendChild(make("small", "source-candidate-reason", `Prerequisites: ${(item.prerequisiteIds || []).join(", ") || "none"}`));
      card.appendChild(make("p", "source-candidate-excerpt", `Objectives: ${(item.objectives || []).join(" · ")}`));
      const edit = make("button", "button button-secondary", "Edit curriculum"); edit.type = "button";
      edit.addEventListener("click", () => startEditing(item));
      card.appendChild(edit); container.appendChild(card);
    });
  }
  function startEditing(item) {
    editingCurriculumId = item.id;
    byId("curriculumTitle").value = item.title || "";
    byId("curriculumDomain").value = item.domain || "";
    byId("curriculumObjectives").value = (item.objectives || []).join("\n");
    byId("curriculumPrerequisites").value = (item.prerequisiteIds || []).join("\n");
    byId("curriculumSortOrder").value = String(item.sortOrder ?? 0);
    byId("curriculumEnabled").checked = item.enabled !== false;
    byId("curriculumDifficulty").value = item.difficulty || "FOUNDATION";
    byId("saveCurriculumButton").textContent = "Save curriculum changes";
    byId("cancelCurriculumEdit").hidden = false;
    status("createCurriculumStatus", `Editing ${item.title} (${item.id}).`, "success");
    byId("curriculumTitle").focus();
  }
  function stopEditing() {
    editingCurriculumId = null;
    byId("createCurriculumForm").reset();
    byId("curriculumEnabled").checked = true;
    byId("curriculumSortOrder").value = "0";
    byId("saveCurriculumButton").textContent = "Create curriculum";
    byId("cancelCurriculumEdit").hidden = true;
    status("createCurriculumStatus", "");
  }
  function renderQuestions(items) {
    const container = byId("syntheticQuestions");
    container.replaceChildren();
    const candidates = items.filter(item => ["CANDIDATE", "NEEDS_LANGUAGE_REVIEW"].includes(item.status));
    if (!candidates.length) {
      container.appendChild(make("div", "registry-empty", "No synthetic questions awaiting review. Create an approved claim, then generate practice questions."));
      return;
    }
    candidates.slice(0, 80).forEach(item => {
      const card = make("article", "source-candidate-card curriculum-question-card");
      const meta = make("div", "source-candidate-meta");
      meta.append(make("span", `source-health-badge ${item.languageReviewRequired ? "pending" : "inactive"}`, item.status));
      meta.append(make("span", "", `${item.questionType} · question: ${item.language} · claim: ${item.sourceClaimLanguage} · SYNTHETIC`));
      card.appendChild(meta);
      card.appendChild(make("h3", "", item.question));
      card.appendChild(make("p", "source-candidate-excerpt", `Expected answer: ${item.expectedAnswer}`));
      card.appendChild(make("small", "source-candidate-reason", `Source claim: ${item.sourceClaimId} · Evidence refs: ${(item.evidenceRefs || []).length} · Split: ${item.split} · Independent evidence: ${item.independentEvidence}`));
      if (item.languageReviewRequired) card.appendChild(make("p", "source-error-note", "Shona/Ndebele content or a language mismatch requires human language review and a review note before acceptance."));
      const actions = make("div", "curriculum-question-actions");
      const accept = make("button", "button button-primary", "Accept"); accept.type = "button";
      accept.addEventListener("click", () => {
        const ratingText = window.prompt("Rate question quality from 1 to 5 (4–5 are eligible for few-shot examples):", "5");
        if (ratingText === null) return;
        const qualityRating = Number(ratingText);
        if (!Number.isInteger(qualityRating) || qualityRating < 1 || qualityRating > 5) { bridge()?.showToast("Enter a whole-number rating from 1 to 5", "error"); return; }
        const trainerNote = window.prompt(item.languageReviewRequired ? "Confirm human language review and add a note:" : "Trainer review note:", "Reviewed by trainer") || "";
        void review(item.id, { action: "ACCEPT", qualityRating, trainerNote });
      });
      const edit = make("button", "button button-secondary", "Edit"); edit.type = "button";
      edit.addEventListener("click", () => {
        const question = window.prompt("Edit question", item.question); if (question === null) return;
        const expectedAnswer = window.prompt("Edit expected answer", item.expectedAnswer); if (expectedAnswer === null) return;
        const trainerNote = window.prompt("Trainer note", item.trainerNote || "Edited by trainer") || "";
        void review(item.id, { action: "EDIT", question, expectedAnswer, trainerNote });
      });
      const discard = make("button", "button button-ghost", "Discard"); discard.type = "button";
      discard.addEventListener("click", () => { if (window.confirm("Discard this synthetic practice question?")) void review(item.id, { action: "DISCARD", trainerNote: "Discarded by trainer" }); });
      actions.append(accept, edit, discard); card.appendChild(actions); container.appendChild(card);
    });
  }
  async function load() {
    const api = bridge();
    if (!api?.getKey()) return;
    try {
      const { data } = await api.requestJson("/api/v1/curriculum", { headers: api.headers() });
      render(data);
      status("curriculumUnlockStatus", "Administrator access verified.", "success");
    } catch (error) {
      if (error.status === 401) { api.clearKey(); byId("curriculumPanel").hidden = true; byId("curriculumGate").hidden = false; }
      status("curriculumUnlockStatus", error.message || "Could not load curriculum lab.", "error");
    }
  }
  async function review(id, body) {
    const api = bridge();
    if (!api?.getKey()) return;
    try {
      await api.requestJson(`/api/v1/curriculum/questions/${encodeURIComponent(id)}/review`, { method: "POST", headers: api.headers(), body });
      api.showToast("Question review saved", "success"); await load();
    } catch (error) { api.showToast(error.message || "Could not save review", "error"); }
  }

  byId("curriculumUnlockForm")?.addEventListener("submit", async event => {
    event.preventDefault();
    const api = bridge(); const candidate = byId("curriculumAdminKey").value;
    if (!api || !candidate) return;
    try {
      await api.requestJson("/api/v1/curriculum", { headers: { "X-Vibes-Gateway-Key": candidate } });
      api.setKey(candidate); byId("curriculumAdminKey").value = ""; await load();
    } catch (error) { status("curriculumUnlockStatus", error.message || "Admin key rejected", "error"); }
  });

  byId("createCurriculumForm")?.addEventListener("submit", async event => {
    event.preventDefault(); const api = bridge(); if (!api?.getKey()) return;
    const objectives = byId("curriculumObjectives").value.split(/\r?\n/).map(x => x.trim()).filter(Boolean);
    const prerequisiteIds = byId("curriculumPrerequisites").value.split(/\r?\n/).map(x => x.trim()).filter(Boolean);
    try {
      const body = { title: byId("curriculumTitle").value.trim(), domain: byId("curriculumDomain").value.trim(), objectives, prerequisiteIds, sortOrder: Number(byId("curriculumSortOrder").value) || 0, difficulty: byId("curriculumDifficulty").value, enabled: byId("curriculumEnabled").checked };
      const path = editingCurriculumId ? `/api/v1/curriculum/curricula/${encodeURIComponent(editingCurriculumId)}` : "/api/v1/curriculum/curricula";
      await api.requestJson(path, { method: editingCurriculumId ? "PUT" : "POST", headers: api.headers(), body });
      const message = editingCurriculumId ? "Curriculum updated." : "Curriculum created.";
      stopEditing(); status("createCurriculumStatus", message, "success"); await load();
    } catch (error) { status("createCurriculumStatus", error.message || "Could not create curriculum", "error"); }
  });

  byId("createClaimForm")?.addEventListener("submit", async event => {
    event.preventDefault(); const api = bridge(); if (!api?.getKey()) return;
    const lines = id => byId(id).value.split(/\r?\n/).map(x => x.trim()).filter(Boolean);
    const evidenceRefs = lines("claimEvidence");
    try {
      await api.requestJson("/api/v1/curriculum/claims", { method: "POST", headers: api.headers(), body: {
        subject: byId("claimSubject").value.trim(), relation: byId("claimRelation").value.trim(), objectValue: byId("claimObject").value.trim(),
        domain: byId("claimDomain").value.trim(), language: byId("claimLanguage").value, scope: byId("claimScope").value.trim(),
        exceptions: lines("claimExceptions"), evidenceRefs, reviewedBy: byId("claimReviewer").value.trim()
      } });
      status("createClaimStatus", "Reviewed claim registered with evidence provenance.", "success"); byId("createClaimForm").reset(); await load();
    } catch (error) { status("createClaimStatus", error.message || "Could not register claim", "error"); }
  });

  byId("generateQuestionsForm")?.addEventListener("submit", async event => {
    event.preventDefault(); const api = bridge(); if (!api?.getKey()) return;
    const curriculumId = byId("generateCurriculumId").value;
    if (!curriculumId) { status("generateQuestionsStatus", "Create or select a curriculum first.", "error"); return; }
    try {
      const { data } = await api.requestJson("/api/v1/curriculum/generate", { method: "POST", headers: api.headers(), body: { curriculumId, language: byId("generateLanguage").value, limit: Number(byId("generateLimit").value) } });
      status("generateQuestionsStatus", `Generated ${data.generated} synthetic questions. They remain pending trainer review and do not count as evidence.`, "success"); await load();
    } catch (error) { status("generateQuestionsStatus", error.message || "Generation failed", "error"); }
  });

  byId("cancelCurriculumEdit")?.addEventListener("click", stopEditing);
  byId("refreshCurriculum")?.addEventListener("click", () => void load());
  byId("lockCurriculumButton")?.addEventListener("click", () => {
    bridge()?.clearKey(); byId("curriculumPanel").hidden = true; byId("curriculumGate").hidden = false;
    status("curriculumUnlockStatus", "Administrator credential cleared from page memory.", "success");
  });
  window.VibesCurriculum = { load };
})();
