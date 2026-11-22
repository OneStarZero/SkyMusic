/**
 * SkyMusic · 勾选逻辑 + 保存进度
 */

import { view } from './state.js';
import { loadProgress, saveProgress } from './progress-store.js';
import { themeState, restoreTheme } from './ui-theme.js';

/* ========================== 勾选 ========================== */

export function updateSelectUI() {
  const total = view.pages.length;
  const count = view.selected.filter(Boolean).length;

  if (view.selectCountEl) {
    view.selectCountEl.textContent = count + ' / ' + total;
  }

  if (view.selectAllSwitch) {
    const allSelected = total > 0 && count === total;
    view.selectAllSwitch.classList.toggle('smsi-on', allSelected);
  }

  for (let i = 0; i < view.pages.length; i++) {
    const page = view.pages[i];
    if (!page._wrap) continue;
    page._wrap.classList.toggle('smsi-unselected', !view.selected[i]);
  }

  persistProgress();
}

export function togglePageSelection(index) {
  if (index < 0 || index >= view.selected.length) return;
  view.selected[index] = !view.selected[index];
  updateSelectUI();
}

export function toggleSelectAll() {
  const total = view.pages.length;
  if (total === 0) return;

  const count = view.selected.filter(Boolean).length;
  const allSelected = count === total;

  const next = !allSelected;
  view.selected = new Array(total).fill(next);

  updateSelectUI();
}

/* ========================== 保存进度 ========================== */

function persistProgress() {
  if (!view.title) return;
  saveProgress(view.title, {
    rows: view.rows,
    selected: view.selected.slice(),
    themeKey: themeState.themeKey,
    cornerKey: themeState.cornerKey,
    isRandom: themeState.isRandom,
    updatedAt: Date.now()
  });
}

export function restoreProgress() {
  const saved = loadProgress(view.title);
  if (!saved) return false;

  if (saved.rows === view.rows && Array.isArray(saved.selected) &&
      saved.selected.length === view.pages.length) {
    view.selected = saved.selected.map(Boolean);
  } else {
    view.selected = new Array(view.pages.length).fill(true);
  }

  restoreTheme(saved);

  return true;
}
