/**
 * SkyMusic · 预览层 DOM 构建 + 事件绑定
 */

import {
  view,
  DEFAULT_ZOOM,
  setActiveTab,
  getActiveTab
} from './state.js';

import {
  FONT_STACK,
  THEMES,
  CORNER_STYLES,
  RANDOM_STYLE,
  STYLE_DEFAULTS,
  getThemeStyle
} from '../style.js';

import { setNativeTopBarVisible } from './bridge.js';
import { showToast } from './toast.js';
import { setZoom, currentZoom, updateZoomLabel, bindPinchZoom } from './zoom.js';
import { renderPages, ensureLongPreview, disposeObservers } from './render.js';
import { saveImages } from './save.js';
import { loadProgress, saveProgress } from './progress-store.js';
import { buildLayout, buildPages, computeInfo, buildInfoItems, collectNotes } from '../layout.js';
import { injectStyle } from './styles.js';
import { OVERLAY_HTML } from './template.js';

/* ========================== 主题状态 ========================== */

const themeState = {
  themeKey: 'morning',
  cornerKey: 'round',
  isRandom: false
};

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

  // 主题面板
  view.themeBtn = overlay.querySelector('[data-act="theme"]');
  view.themePanel = overlay.querySelector('[data-role="theme-panel"]');
  view.themeList = overlay.querySelector('[data-role="theme-list"]');
  view.cornerList = overlay.querySelector('[data-role="corner-list"]');
  view.randomList = overlay.querySelector('[data-role="random-list"]');
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

/* ========================== 主题列表 ========================== */

function buildThemeLists() {
  if (!view.themeList || !view.cornerList || !view.randomList) return;

  // 主题列表（不含随机）
  view.themeList.innerHTML = '';
  Object.keys(THEMES).forEach(function (key) {
    const t = THEMES[key];
    const item = document.createElement('div');
    item.className = 'smsi-theme-item' + (themeState.themeKey === key && !themeState.isRandom ? ' smsi-selected' : '');
    item.dataset.role = 'theme-item';
    item.dataset.key = key;

    // 色块预览
    const swatch = document.createElement('div');
    swatch.className = 'smsi-theme-swatch';
    swatch.style.background = 'linear-gradient(135deg, ' + t.bg + ', ' + t.noteFill + ', ' + t.noteFillAlt + ')';

    const label = document.createElement('span');
    label.textContent = t.name;

    item.appendChild(swatch);
    item.appendChild(label);
    view.themeList.appendChild(item);
  });

  // 圆角列表（三选一）
  view.cornerList.innerHTML = '';
  Object.keys(CORNER_STYLES).forEach(function (key) {
    const c = CORNER_STYLES[key];
    const item = document.createElement('div');
    item.className = 'smsi-corner-item' + (themeState.cornerKey === key ? ' smsi-selected' : '');
    item.dataset.role = 'corner-item';
    item.dataset.key = key;

    const preview = document.createElement('div');
    preview.className = 'smsi-corner-preview';
    preview.style.borderRadius = c.gridRadius + 'px';

    const label = document.createElement('span');
    label.textContent = c.name;

    item.appendChild(preview);
    item.appendChild(label);
    view.cornerList.appendChild(item);
  });

  // 随机列表（单独）
  view.randomList.innerHTML = '';
  const randomItem = document.createElement('div');
  randomItem.className = 'smsi-theme-item smsi-random' + (themeState.isRandom ? ' smsi-selected' : '');
  randomItem.dataset.role = 'random-item';

  const randomSwatch = document.createElement('div');
  randomSwatch.className = 'smsi-theme-swatch smsi-rainbow';
  randomSwatch.style.background = 'conic-gradient(from 0deg, #F87171, #FBBF24, #34D399, #38BDF8, #A78BFA, #F472B6, #F87171)';

  const randomLabel = document.createElement('span');
  randomLabel.textContent = RANDOM_STYLE.name;

  randomItem.appendChild(randomSwatch);
  randomItem.appendChild(randomLabel);
  view.randomList.appendChild(randomItem);
}

/* ========================== 点击事件 ========================== */

function onOverlayClick(ev) {
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

  // 主题选择
  const themeItem = target.closest('[data-role="theme-item"]');
  if (themeItem) {
    applyTheme(themeItem.dataset.key);
    return;
  }

  // 圆角选择
  const cornerItem = target.closest('[data-role="corner-item"]');
  if (cornerItem) {
    applyCorner(cornerItem.dataset.key);
    return;
  }

  // 随机样式
  const randomItem = target.closest('[data-role="random-item"]');
  if (randomItem) {
    applyRandom();
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

/* ========================== 主题应用 ========================== */

function toggleThemePanel(force) {
  if (!view.themePanel) return;
  const show = force !== undefined ? force : !view.themePanel.classList.contains('smsi-show');
  view.themePanel.classList.toggle('smsi-show', show);
}

function applyTheme(key) {
  themeState.themeKey = key;
  themeState.isRandom = false;
  view.style = getThemeStyle(key, themeState.cornerKey);

  buildThemeLists();
  refreshAllPages();
  showToast('已切换主题：' + (THEMES[key] ? THEMES[key].name : key), 'success');
}

function applyCorner(key) {
  themeState.cornerKey = key;
  if (themeState.isRandom) {
    view.style = getThemeStyle('random', key);
  } else {
    view.style = getThemeStyle(themeState.themeKey, key);
  }

  buildThemeLists();
  refreshAllPages();
  showToast('已切换圆角：' + (CORNER_STYLES[key] ? CORNER_STYLES[key].name : key), 'success');
}

function applyRandom() {
  themeState.isRandom = true;
  view.style = getThemeStyle('random', themeState.cornerKey);

  buildThemeLists();
  refreshAllPages();
  showToast('已应用：' + RANDOM_STYLE.name, 'success');
}

function refreshAllPages() {
  // 清除已渲染标记，强制重绘
  for (let i = 0; i < view.pages.length; i++) {
    const page = view.pages[i];
    if (page) page._rendered = false;
  }
  view.longCanvas = null;
  renderPages();
  if (getActiveTab() === 'long') {
    ensureLongPreview();
  }
}

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
  const overlay = ensureOverlay();
  overlay.style.display = 'flex';
  document.body.style.overflow = 'hidden';
  requestAnimationFrame(function () {
    overlay.classList.add('smsi-show');
  });
  setNativeTopBarVisible(false);
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

function togglePageSelection(index) {
  if (index < 0 || index >= view.selected.length) return;
  view.selected[index] = !view.selected[index];
  updateSelectUI();
}

function toggleSelectAll() {
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

  // 恢复主题
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

  return true;
}

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

function switchKeyCount(rows) {
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
