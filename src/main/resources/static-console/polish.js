(() => {
  'use strict';
  const nav = document.getElementById('drawerNav');
  if (!nav || document.getElementById('commandPaletteBackdrop')) return;

  const backdrop = document.createElement('div');
  backdrop.className = 'command-palette-backdrop';
  backdrop.id = 'commandPaletteBackdrop';
  backdrop.hidden = true;
  backdrop.innerHTML = `
    <section class="command-palette" role="dialog" aria-modal="true" aria-labelledby="commandPaletteTitle">
      <div class="command-palette-head"><span class="command-palette-search-icon" aria-hidden="true">⌕</span>
        <label id="commandPaletteTitle" class="sr-only" for="commandPaletteInput">Quick jump</label>
        <input id="commandPaletteInput" class="command-palette-input" type="search" autocomplete="off" placeholder="Jump to a workspace…" aria-controls="commandPaletteList" aria-expanded="true">
        <span class="command-palette-hint">ESC</span>
      </div>
      <div class="command-palette-label">NAVIGATE</div>
      <div class="command-palette-list" id="commandPaletteList" role="listbox" aria-label="Workspace sections"></div>
      <div class="command-palette-foot"><span>↑ ↓ to navigate · Enter to open</span><span>Vibes Quantum</span></div>
    </section>`;
  document.body.appendChild(backdrop);

  const input = backdrop.querySelector('#commandPaletteInput');
  const list = backdrop.querySelector('#commandPaletteList');
  const titles = new Map();
  let filtered = [];
  let activeIndex = 0;
  let previousFocus = null;

  function items() {
    return [...nav.querySelectorAll('[data-view-target]')].map(button => ({
      button,
      name: (button.innerText || '').replace(/[›→]/g, '').trim(),
      key: button.dataset.viewTarget || ''
    })).filter(item => item.key && item.name);
  }

  function draw(query = '') {
    const q = query.trim().toLocaleLowerCase();
    filtered = items().filter(item => `${item.name} ${item.key}`.toLocaleLowerCase().includes(q));
    activeIndex = Math.min(activeIndex, Math.max(0, filtered.length - 1));
    list.replaceChildren();
    if (!filtered.length) {
      const empty = document.createElement('div'); empty.className = 'command-palette-empty';
      empty.textContent = 'No matching workspace. Try another search.'; list.appendChild(empty); return;
    }
    filtered.forEach((item, index) => {
      const row = document.createElement('button'); row.type = 'button';
      row.className = 'command-palette-item'; row.setAttribute('role', 'option');
      row.setAttribute('aria-selected', String(index === activeIndex));
      const glyph = document.createElement('span'); glyph.className = 'command-palette-glyph'; glyph.setAttribute('aria-hidden', 'true'); glyph.textContent = item.name.slice(0, 1).toUpperCase();
      const copy = document.createElement('span'); copy.className = 'command-palette-copy';
      const strong = document.createElement('strong'); strong.textContent = item.name;
      const small = document.createElement('small'); small.textContent = `Open ${item.name.toLowerCase()}`;
      copy.append(strong, small); row.append(glyph, copy);
      row.addEventListener('mouseenter', () => { activeIndex = index; syncActive(); });
      row.addEventListener('click', () => { close(); item.button.click(); });
      list.appendChild(row);
    });
  }
  function syncActive() {
    [...list.querySelectorAll('[role="option"]')].forEach((node, index) => node.setAttribute('aria-selected', String(index === activeIndex)));
    list.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' });
  }
  function open() {
    previousFocus = document.activeElement;
    backdrop.hidden = false;
    input.value = ''; activeIndex = 0; draw('');
    requestAnimationFrame(() => input.focus());
  }
  function close() {
    backdrop.hidden = true;
    if (previousFocus && typeof previousFocus.focus === 'function') previousFocus.focus();
  }
  input.addEventListener('input', () => { activeIndex = 0; draw(input.value); });
  input.addEventListener('keydown', event => {
    if (event.key === 'ArrowDown') { event.preventDefault(); activeIndex = Math.min(activeIndex + 1, filtered.length - 1); syncActive(); }
    else if (event.key === 'ArrowUp') { event.preventDefault(); activeIndex = Math.max(activeIndex - 1, 0); syncActive(); }
    else if (event.key === 'Enter') { event.preventDefault(); filtered[activeIndex]?.button.click(); close(); }
    else if (event.key === 'Escape') { event.preventDefault(); close(); }
    else if (event.key === 'Tab') {
      event.preventDefault(); const opts = [...list.querySelectorAll('button')];
      if (opts.length) (event.shiftKey ? opts[opts.length - 1] : opts[activeIndex])?.focus(); else close();
    }
  });
  list.addEventListener('keydown', event => {
    if (event.key === 'Escape') { event.preventDefault(); close(); }
    if (event.key === 'Tab') {
      const opts = [...list.querySelectorAll('button')];
      const current = opts.indexOf(document.activeElement);
      if (event.shiftKey && current <= 0) { event.preventDefault(); input.focus(); }
      else if (!event.shiftKey && current === opts.length - 1) { event.preventDefault(); input.focus(); }
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); input.focus(); input.dispatchEvent(new KeyboardEvent('keydown', {key:event.key,bubbles:true})); }
  });
  backdrop.addEventListener('click', event => { if (event.target === backdrop) close(); });
  document.addEventListener('keydown', event => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); backdrop.hidden ? open() : close(); }
    if (!backdrop.hidden && event.key === 'Escape') close();
  });

  const topActions = document.querySelector('.topbar-actions');
  if (topActions) {
    const jump = document.createElement('button'); jump.type = 'button'; jump.className = 'quick-jump-button';
    jump.setAttribute('aria-haspopup', 'dialog'); jump.setAttribute('aria-controls', backdrop.id);
    jump.innerHTML = '<span aria-hidden="true">⌕</span><span>Quick jump</span><kbd>⌘ K</kbd>';
    jump.addEventListener('click', open);
    const first = topActions.firstElementChild;
    topActions.insertBefore(jump, first || null);
  }
  // Respect reduced-motion preferences and avoid exposing a hidden dialog to assistive technology.
  const style = document.createElement('style'); style.textContent = '.sr-only{position:absolute!important;width:1px!important;height:1px!important;padding:0!important;margin:-1px!important;overflow:hidden!important;clip:rect(0,0,0,0)!important;white-space:nowrap!important;border:0!important}[hidden]{display:none!important}';
  document.head.appendChild(style);
})();
