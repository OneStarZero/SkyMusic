/**
 * SkyMusic · 打开/关闭/标签页切换
 */

import {
  view,
  setActiveTab,
  getActiveTab
} from './state.js';

import { setNativeTopBarVisible } from './bridge.js';
import { updateZoomLabel } from './zoom.js';
import { ensureLongPreview, disposeObservers } from './render.js';

/* ========================== 标签页 ========================== */

export function switchTab(tab) {
  setActiveTab(tab);

  if (view.tabPagesBtn) view.tabPagesBtn.classList.toggle('smsi-active', tab === 'pages');
  if (view.tabLongBtn) view.tabLongBtn.classList.toggle('smsi-active', tab === 'long');
  if (view.bodyPages) view.bodyPages.classList.toggle('smsi-active', tab === 'pages');
  if (view.bodyLong) view.bodyLong.classList.toggle('smsi-active', tab === 'long');

  if (view.selectAllRow) {
    view.selectAllRow.classList.toggle('smsi-hidden', tab !== 'pages');
  }
  if (view.btnSave) view.btnSave.classList.toggle('smsi-hidden', tab !== 'pages');
  if (view.btnSaveLong) view.btnSaveLong.classList.toggle('smsi-hidden', tab !== 'long');

  if (tab === 'long') {
    requestAnimationFrame(function () {
      ensureLongPreview();
    });
  }
  updateZoomLabel();
}

/* ========================== 开关 ========================== */

export function openOverlay() {
  const overlay = view.overlay;
  if (!overlay) return;

  // 先显示但透明，避免闪烁
  overlay.style.display = 'flex';
  overlay.style.opacity = '0';
  // 强制 reflow
  void overlay.offsetHeight;
  // 下一帧再加 show 类
  requestAnimationFrame(function () {
    overlay.classList.add('smsi-show');
    overlay.style.opacity = '';
  });
}


export function closeOverlay() {
  if (!view.overlay) return;
  const overlay = view.overlay;
  overlay.classList.remove('smsi-show');
  document.body.style.overflow = '';

  disposeObservers();

  setTimeout(function () {
    overlay.style.display = 'none';
    if (view.pagesHost) view.pagesHost.innerHTML = '';
    if (view.longHost) view.longHost.innerHTML = '';
    if (view.themePanel) view.themePanel.classList.remove('smsi-show');
    view.pages = [];
    view.selected = [];
    view.longCanvas = null;
  }, 220);
  setNativeTopBarVisible(true);
}

/* ========================== 主题面板 ========================== */

export function toggleThemePanel(force) {
  if (!view.themePanel) return;
  const show = force !== undefined ? force : !view.themePanel.classList.contains('smsi-show');
  view.themePanel.classList.toggle('smsi-show', show);
}
