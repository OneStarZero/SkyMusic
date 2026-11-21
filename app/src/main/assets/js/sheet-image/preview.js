/**
 * SkyMusic · 谱面图片预览层（对外入口）
 */

import { view, DEFAULT_ZOOM } from './preview/state.js';
import { STYLE_DEFAULTS, clampInt } from './style.js';
import { showToast } from './preview/toast.js';
import { ensureOverlay, openOverlay, switchTab, rebuild, restoreProgress, updateSelectUI } from './preview/ui.js';
import { renderPages } from './preview/render.js';
import { updateZoomLabel } from './preview/zoom.js';

export function openSheetImagePreview(data, options) {
  if (!document || !document.body) {
    console.warn('[sheet-image] document 尚未就绪');
    return;
  }

  const opts = options || {};
  view.raw = data || {};
  view.rows = clampInt(opts.keyCount, 1, 15, 15);
  view.tol = Number.isFinite(Number(opts.toleranceMs)) ? Number(opts.toleranceMs) : 25;
  view.bpm = Number(opts.bpm || (data && data.bpm)) || 0;
  view.style = Object.assign({}, STYLE_DEFAULTS, opts.style || {});
  view.pageZoom = DEFAULT_ZOOM;
  view.longZoom = DEFAULT_ZOOM;
  view.mergeLong = false;
  view.longCanvas = null;

  const fullName = String(opts.songName || opts.title || '未命名乐谱');
  let baseName = fullName;
  let model = opts.model || '';

  const sep = fullName.indexOf(' || ');
  if (sep >= 0) {
    baseName = fullName.slice(0, sep).trim();
    if (!model) model = fullName.slice(sep + 4).trim();
  }

  view.title = fullName.trim() || '未命名乐谱';
  view.model = model;
  view.transcriber = opts.transcriber || '';
  view.author = opts.author || (function () {
    const dash = baseName.lastIndexOf('-');
    return dash > 0 ? baseName.slice(dash + 1).trim() : '';
  })();

  ensureOverlay();
  openOverlay();
  switchTab('pages');

  const ok = rebuild();
  if (!ok) {
    showToast('没有可绘制的音符，请先完成转谱', 'error');
    if (view.titleEl) view.titleEl.textContent = view.title;
    if (view.pagesHost) view.pagesHost.innerHTML = '';
    return;
  }

  if (view.titleEl) view.titleEl.textContent = view.title;
  restoreProgress();
  renderPages();
  updateSelectUI();
  updateZoomLabel();
}
