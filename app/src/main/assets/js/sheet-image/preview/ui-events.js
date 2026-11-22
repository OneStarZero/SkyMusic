/**
 * SkyMusic · 点击事件分发
 */

import { view, DEFAULT_ZOOM } from './state.js';
import { setZoom, currentZoom } from './zoom.js';
import { saveImages } from './save.js';
import { togglePageSelection, toggleSelectAll } from './ui-selection.js';
import { applyTheme, applyCorner, applyRandom } from './ui-theme.js';
import { switchTab, closeOverlay, toggleThemePanel } from './ui-overlay.js';
import { switchKeyCount } from './ui-rebuild.js';

/* ========================== 事件分发 ========================== */

export function onOverlayClick(ev) {
  const target = ev.target;
  if (!target || !target.closest) return;

  // 页面勾选
  const pick = target.closest('.smsi-pick');
  if (pick) {
    const wrap = pick.closest('.smsi-page');
    if (wrap && wrap.dataset.pageIndex !== undefined) {
      togglePageSelection(parseInt(wrap.dataset.pageIndex, 10));
    }
    return;
  }

  // 全选开关
  const selectAllSwitch = target.closest('[data-act="select-all"]');
  if (selectAllSwitch) {
    toggleSelectAll();
    return;
  }

  // 主题配色选择
  const themeItem = target.closest('[data-role="theme-item"]');
  if (themeItem) {
    applyTheme(themeItem.dataset.key);
    return;
  }

  // 按键样式选择
  const cornerItem = target.closest('[data-role="corner-item"]');
  if (cornerItem) {
    applyCorner(cornerItem.dataset.key);
    return;
  }

  // 其他按钮
  const actEl = target.closest('[data-act]');
  if (!actEl) return;
  const act = actEl.getAttribute('data-act');

  switch (act) {
    case 'close':
    case 'cancel':
      closeOverlay();
      break;
    case 'zoom-in':
      setZoom(currentZoom() + 0.15);
      break;
    case 'zoom-out':
      setZoom(currentZoom() - 0.15);
      break;
    case 'zoom-reset':
      setZoom(DEFAULT_ZOOM);
      break;
    case 'key15':
      switchKeyCount(15);
      break;
    case 'key9':
      switchKeyCount(9);
      break;
    case 'tab-pages':
      switchTab('pages');
      break;
    case 'tab-long':
      switchTab('long');
      break;
    case 'theme':
      toggleThemePanel();
      break;
    case 'theme-close':
      toggleThemePanel(false);
      break;
    case 'random':
      applyRandom();
      break;
    case 'save':
      saveImages(false, closeOverlay);
      break;
    case 'save-long':
      saveImages(true, closeOverlay);
      break;
    default:
      break;
  }
}
