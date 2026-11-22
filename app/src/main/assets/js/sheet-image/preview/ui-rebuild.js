/**
 * SkyMusic · 重建 + 键位切换
 */

import { view, getActiveTab } from './state.js';
import { showToast } from './toast.js';
import { renderPages, ensureLongPreview } from './render.js';
import { updateSelectUI, restoreProgress } from './ui-selection.js';
import { buildLayout, buildPages, computeInfo, buildInfoItems, collectNotes } from '../layout.js';

/* ========================== 重建 ========================== */

export function rebuild() {
  const notes = collectNotes(view.raw, view.rows);
  if (!notes.length) return false;

  view.layout = buildLayout(notes, view.rows, view.tol);
  view.pages = buildPages(view.layout, view.style);
  view.info = computeInfo(view.layout, view.rows, view.tol);
  view.infoItems = buildInfoItems(
    view.info, view.rows, view.bpm, view.model, view.author, view.transcriber
  );
  view.selected = new Array(view.pages.length).fill(true);
  view.longCanvas = null;

  if (view.titleEl) view.titleEl.textContent = view.title;
  if (view.key15Btn) view.key15Btn.classList.toggle('smsi-on', view.rows === 15);
  if (view.key9Btn) view.key9Btn.classList.toggle('smsi-on', view.rows === 9);

  return true;
}

/* ========================== 键位切换 ========================== */

export function switchKeyCount(rows) {
  if (view.rows === rows) return;
  view.rows = rows;
  const ok = rebuild();
  if (!ok) {
    showToast('当前谱面没有可绘制到 ' + rows + ' 键范围内的音符', 'error');
    view.rows = rows === 15 ? 9 : 15;
    rebuild();
  }
  restoreProgress();
  renderPages();
  updateSelectUI();
  if (getActiveTab() === 'long') ensureLongPreview();
}