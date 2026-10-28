(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  let memoryCache = [];
  const announce = (node, message) => { if (node) node.textContent = message; };
  async function copyText(value, statusNode) {
    try {
      await navigator.clipboard.writeText(value);
      announce(statusNode, 'Copied code to clipboard.');
      if (typeof window.showToast === 'function') window.showToast('Code copied');
    } catch (_) {
      const area = document.createElement('textarea'); area.value = value; area.readOnly = true;
      area.style.position = 'fixed'; area.style.opacity = '0'; document.body.appendChild(area); area.select();
      const ok = document.execCommand('copy'); area.remove();
      announce(statusNode, ok ? 'Copied code to clipboard.' : 'Copy failed. Select the code and copy it manually.');
    }
  }
  document.addEventListener('click', (event) => {
    const button = event.target.closest('.copy-code-btn');
    if (!button) return;
    const card = button.closest('.code-card, .code-demo-card');
    const code = card?.querySelector('pre code');
    if (!code) return;
    const status = card.querySelector('.copy-status') || $('terminalStatus');
    copyText(code.innerText || code.textContent || '', status);
    const previous = button.textContent; button.textContent = 'Copied ✓';
    window.setTimeout(() => { button.textContent = previous; }, 1300);
  });
  $('copyTerminalCommand')?.addEventListener('click', () => copyText($('terminalCommand')?.value || '', $('terminalStatus')));
  $('terminalSafetyInfo')?.addEventListener('click', () => announce($('terminalStatus'), 'Execution is disabled because this deployment has not confirmed an isolated sandbox runtime. A real runner must enforce filesystem, network, process, CPU, memory, and time limits server-side. This UI will not execute commands on the gateway host.'));
  $('checkAuditIntegrity')?.addEventListener('click', async () => {
    const status = $('auditIntegrityResult'); announce(status, 'Checking server audit chain…');
    try {
      const response = await fetch('/api/v1/security/audit/verify', { headers: { Accept: 'application/json' }, cache: 'no-store' });
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
      $('auditStatus')?.classList.toggle('audit-failed', !data.valid);
      announce(status, `${data.valid ? 'Integrity check passed' : 'INTEGRITY WARNING'} · ${data.events} events · ${data.note}`);
    } catch (error) { announce(status, `Unable to verify audit log: ${error.message}. Logs have not been cleared.`); }
  });
  async function requestMemory(path, options = {}) {
    const token = typeof authToken !== 'undefined' ? authToken : '';
    if (!token) throw new Error('Sign in with Google first.');
    const response = await fetch(path, { ...options, cache: 'no-store', headers: { Accept: 'application/json', Authorization: `Bearer ${token}`, ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(options.headers || {}) } });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || `Request failed (${response.status})`);
    return data;
  }
  function renderMemory(records) {
    memoryCache = Array.isArray(records) ? records : records.records || [];
    const host = $('personalMemoryList'); if (!host) return;
    host.replaceChildren();
    if (!memoryCache.length) { host.textContent = 'No saved personal memories. Nothing was deleted or reset.'; $('exportPersonalMemory').disabled = true; return; }
    $('exportPersonalMemory').disabled = false;
    memoryCache.forEach((record) => {
      const item = document.createElement('article'); item.className = 'memory-record';
      const heading = document.createElement('div'); heading.className = 'memory-record-head';
      const purpose = document.createElement('strong'); purpose.textContent = record.purpose || 'Personal memory';
      const time = document.createElement('small'); time.textContent = record.updatedAt ? new Date(record.updatedAt).toLocaleString() : 'Timestamp unavailable';
      heading.append(purpose, time);
      const body = document.createElement('p'); body.textContent = record.text || '';
      const meta = document.createElement('small'); meta.textContent = `Source: ${record.source || 'not provided'} · Confidence: ${record.confidence || 'unspecified'}`;
      const del = document.createElement('button'); del.type = 'button'; del.className = 'button button-ghost memory-delete'; del.textContent = 'Delete memory';
      del.addEventListener('click', async () => {
        if (!window.confirm('Delete this personal memory? This endpoint does not guarantee backup recovery.')) return;
        del.disabled = true;
        try { await requestMemory(`/api/v1/personal-memory/${encodeURIComponent(record.id)}`, { method: 'DELETE' }); await loadMemory(); announce($('personalMemoryStatus'), 'Memory deletion processed by server. Check configured backup retention for recoverability.'); }
        catch (error) { announce($('personalMemoryStatus'), error.message); del.disabled = false; }
      });
      item.append(heading, body, meta, del); host.append(item);
    });
  }
  async function loadMemory() { const data = await requestMemory('/api/v1/personal-memory'); renderMemory(data); }
  $('loadPersonalMemory')?.addEventListener('click', async () => { announce($('personalMemoryStatus'), 'Loading your account-scoped memory…'); try { await loadMemory(); announce($('personalMemoryStatus'), 'Loaded personal memory for the signed-in account.'); } catch (error) { announce($('personalMemoryStatus'), error.message); } });
  $('personalMemoryForm')?.addEventListener('submit', async (event) => {
    event.preventDefault(); const status = $('personalMemoryStatus');
    const payload = { text: $('personalMemoryText').value.trim(), purpose: $('personalMemoryPurpose').value, consent: $('personalMemoryConsent').checked };
    try { const saved = await requestMemory('/api/v1/personal-memory', { method: 'POST', body: JSON.stringify(payload) }); announce(status, `Saved memory ${saved.id}. You can review or delete it below.`); $('personalMemoryForm').reset(); await loadMemory(); }
    catch (error) { announce(status, error.message); }
  });
  $('exportPersonalMemory')?.addEventListener('click', async () => {
    try { const data = await requestMemory('/api/v1/personal-memory/export'); const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }); const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = 'vibes-personal-memory-export.json'; a.click(); URL.revokeObjectURL(url); announce($('personalMemoryStatus'), 'Personal memory export created. Keep the file private.'); }
    catch (error) { announce($('personalMemoryStatus'), error.message); }
  });
})();
