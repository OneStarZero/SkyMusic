/**
 * SkyMusic · 按键样式列表构建 + 应用
 */

import {
  view,
  getActiveTab
} from './state.js';

import {
  THEMES,
  CORNER_STYLES,
  RANDOM_STYLE,
  getThemeStyle
} from '../style.js';

import { showToast } from './toast.js';
import { renderPages, ensureLongPreview } from './render.js';

/* ========================== 主题状态 ========================== */

export const themeState = {
  themeKey: 'morning',
  cornerKey: 'round',
  isRandom: false
};

/* ========================== 列表构建 ========================== */

export function buildThemeLists() {
  if (!view.cornerList) return;
  buildCornerList();
}

function buildCornerList() {
  const host = view.cornerList;
  host.innerHTML = '';

  const keys = Object.keys(CORNER_STYLES);
  const frag = document.createDocumentFragment();

  for (let i = 0; i < keys.length; i++) {
    const key = keys[i];
    const c = CORNER_STYLES[key];
    if (!c) continue;

    const item = document.createElement('div');
    item.className = 'smsi-corner-item' + (themeState.cornerKey === key ? ' smsi-selected' : '');
    item.dataset.role = 'corner-item';
    item.dataset.key = key;

    const preview = document.createElement('div');
    preview.className = 'smsi-corner-preview';
    preview.style.borderRadius = (c.gridRadius || 0) + 'px';

    if (key === 'skystudio') {
      preview.innerHTML = '<svg viewBox="0 0 32 32" width="32" height="32">' +
        '<rect x="2" y="2" width="28" height="28" rx="6" fill="none" stroke="rgba(125,211,252,.6)" stroke-width="1.5"/>' +
        '<polygon points="16,8 24,16 16,24 8,16" fill="none" stroke="rgba(125,211,252,.8)" stroke-width="1.5"/>' +
        '<text x="16" y="19" text-anchor="middle" font-size="8" font-weight="700" fill="rgba(125,211,252,.9)">C</text>' +
        '</svg>';
    }

    const label = document.createElement('span');
    label.textContent = c.name || key;

    item.appendChild(preview);
    item.appendChild(label);
    frag.appendChild(item);
  }

  host.appendChild(frag);
}

/* ========================== 应用 ========================== */

export function applyTheme(key) {
  themeState.themeKey = key;
  themeState.isRandom = false;

  const corner = CORNER_STYLES[themeState.cornerKey] || CORNER_STYLES.round;
  view.style = getThemeStyle(key, themeState.cornerKey);
  view.style.noteShape = corner.noteShape;

  buildThemeLists();
  refreshAllPages();
  showToast('已切换主题：' + (THEMES[key] ? THEMES[key].name : key), 'success');
}

export function applyCorner(key) {
  themeState.cornerKey = key;

  const corner = CORNER_STYLES[key] || CORNER_STYLES.round;

  view.style = Object.assign({}, view.style, {
    gridRadius: corner.gridRadius,
    noteShape: corner.noteShape
  });

  buildThemeLists();
  refreshAllPages();
  showToast('已切换按键样式：' + (CORNER_STYLES[key] ? CORNER_STYLES[key].name : key), 'success');
}

export function applyRandom() {
  themeState.isRandom = true;
  view.style = getThemeStyle('random', themeState.cornerKey);

  buildThemeLists();
  refreshAllPages();
  showToast('已应用：' + RANDOM_STYLE.name, 'success');
}

/* ========================== 刷新（防抖合并） ========================== */

let refreshTimer = 0;

function refreshAllPages() {
  if (refreshTimer) clearTimeout(refreshTimer);
  refreshTimer = setTimeout(function () {
    refreshTimer = 0;
    doRefresh();
  }, 16);
}

function doRefresh() {
  const pages = view.pages;
  for (let i = 0; i < pages.length; i++) {
    const page = pages[i];
    if (page) {
      page._rendered = false;
      page._lod = 0;
    }
  }
  view.longCanvas = null;
  renderPages();
  if (getActiveTab() === 'long') {
    ensureLongPreview();
  }
}

/* ========================== 恢复 ========================== */

export function restoreTheme(saved) {
  if (saved.themeKey && THEMES[saved.themeKey]) {
    themeState.themeKey = saved.themeKey;
    themeState.isRandom = false;
  }
  if (saved.cornerKey && CORNER_STYLES[saved.cornerKey]) {
    themeState.cornerKey = saved.cornerKey;
  }
  if (saved.isRandom) {
    themeState.isRandom = true;
  }

  if (themeState.isRandom) {
    view.style = getThemeStyle('random', themeState.cornerKey);
  } else if (saved.themeKey) {
    view.style = getThemeStyle(themeState.themeKey, themeState.cornerKey);
  }

  if (view.overlay) buildThemeLists();
}
