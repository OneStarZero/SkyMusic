/**
 * SkyMusic · 预览层入口
 * ------------------------------------------------------------------
 * 实际实现已拆分到：
 *   ui-theme.js      按键样式列表构建 + 应用
 *   ui-selection.js  勾选逻辑 + 保存进度
 *   ui-overlay.js    打开/关闭/标签页
 *   ui-rebuild.js    重建 + 键位切换
 *   ui-events.js     点击事件分发
 */

import { view } from './state.js';
import { injectStyle } from './styles.js';
import { OVERLAY_HTML } from './template.js';
import { bindPinchZoom } from './zoom.js';
import { buildThemeLists } from './ui-theme.js';
import { onOverlayClick } from './ui-events.js';

/* ========================== DOM 构建 ========================== */

export function ensureOverlay() {
  if (view.overlay) return view.overlay;
  injectStyle();

  const overlay = document.createElement('div');
  overlay.className = 'smsi-overlay';
  overlay.innerHTML = OVERLAY_HTML;

  overlay.addEventListener('click', onOverlayClick);
  document.body.appendChild(overlay);

  view.overlay = overlay;
  view.titleEl = overlay.querySelector('.smsi-title');
  view.pagesHost = overlay.querySelector('.smsi-pages');
  view.longHost = overlay.querySelector('.smsi-long-wrap');
  view.zoomLabel = overlay.querySelector('.smsi-zoom');
  view.key15Btn = overlay.querySelector('[data-act="key15"]');
  view.key9Btn = overlay.querySelector('[data-act="key9"]');
  view.selectAllRow = overlay.querySelector('[data-role="select-all-row"]');
  view.selectAllSwitch = overlay.querySelector('.smsi-switch[data-act="select-all"]');
  view.selectCountEl = overlay.querySelector('[data-role="select-count"]');
  view.progressBar = overlay.querySelector('[data-role="progress"]');
  view.progressFill = overlay.querySelector('.smsi-progress-fill');
  view.progressText = overlay.querySelector('.smsi-progress-text');
  view.tabPagesBtn = overlay.querySelector('[data-act="tab-pages"]');
  view.tabLongBtn = overlay.querySelector('[data-act="tab-long"]');
  view.bodyPages = overlay.querySelector('[data-pane="pages"]');
  view.bodyLong = overlay.querySelector('[data-pane="long"]');
  view.btnSave = overlay.querySelector('[data-act="save"]');
  view.btnSaveLong = overlay.querySelector('[data-act="save-long"]');

  view.themeBtn = overlay.querySelector('[data-act="theme"]');
  view.themePanel = overlay.querySelector('[data-role="theme-panel"]');
  view.cornerList = overlay.querySelector('[data-role="corner-list"]');
  view.themeCloseBtn = overlay.querySelector('[data-act="theme-close"]');

  buildThemeLists();

  blockScrollChaining(overlay);
  blockScrollChaining(view.bodyPages);
  blockScrollChaining(view.bodyLong);

  bindPinchZoom(view.bodyPages, 'pages');
  bindPinchZoom(view.bodyLong, 'long');

  return overlay;
}

function blockScrollChaining(el) {
  if (!el) return;
  el.addEventListener('touchmove', function (e) {
    if (e.touches.length >= 2) return;
    const target = e.currentTarget;
    const atTop = target.scrollTop <= 0;
    const atBottom = target.scrollTop + target.clientHeight >= target.scrollHeight - 1;
    const dy = e.touches[0].clientY - (target._lastY || e.touches[0].clientY);
    target._lastY = e.touches[0].clientY;
    if ((atTop && dy > 0) || (atBottom && dy < 0)) {
      e.preventDefault();
    }
  }, { passive: false });

  el.addEventListener('touchend', function () {
    el._lastY = null;
  }, { passive: true });
}

/* ========================== 对外导出 ========================== */

export { switchTab, openOverlay, closeOverlay } from './ui-overlay.js';
export { updateSelectUI, restoreProgress } from './ui-selection.js';
export { rebuild, switchKeyCount } from './ui-rebuild.js';
