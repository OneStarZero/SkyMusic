import { view, DEFAULT_ZOOM, setActiveTab, getActiveTab } from './state.js';
import { setNativeTopBarVisible } from './bridge.js';
import { showToast } from './toast.js';
import { setZoom, currentZoom, updateZoomLabel, bindPinchZoom } from './zoom.js';
import { renderPages, ensureLongPreview, disposeObservers } from './render.js';
import { saveImages } from './save.js';
import { loadProgress, saveProgress } from './progress-store.js';
import { buildLayout, buildPages, computeInfo, buildInfoItems, collectNotes } from '../layout.js';
import { injectStyle } from './styles.js';
import { OVERLAY_HTML } from './template.js';
import { getThemeStyle, THEMES } from '../style.js';

let userForceRound = null; 
let currentThemeKey = 'morning';

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
  view.btnTheme = overlay.querySelector('[data-act="theme"]');
  view.themePicker = overlay.querySelector('[data-role="theme-picker"]');
  view.themeList = overlay.querySelector('[data-role="theme-list"]');

  // 在主题弹窗 head 里动态加一个圆角/直角开关
  const themeHead = overlay.querySelector('.smsi-theme-head');
  const toggleBtn = document.createElement('button');
  toggleBtn.className = 'smsi-btn';
  toggleBtn.style.cssText = 'font-size:12px; margin-left:auto; margin-right:12px; padding:4px 8px;';
  toggleBtn.textContent = '切换直角/圆角';
  toggleBtn.onclick = () => {
    if (userForceRound === null) userForceRound = false;
    else if (userForceRound === false) userForceRound = true;
    else userForceRound = null;
    
    applyTheme(currentThemeKey, userForceRound);
    showToast(`样式已切换: ${userForceRound === null ? '跟随主题' : userForceRound ? '强制圆角' : '强制直角'}`);
  };
  themeHead.insertBefore(toggleBtn, themeHead.querySelector('[data-act="close-theme"]'));

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

function onOverlayClick(ev) {
  const target = ev.target;
  if (!target || !target.closest) return;

  if (target.classList.contains('smsi-theme-picker')) {
    closeThemePicker();
    return;
  }

  const pick = target.closest('.smsi-pick');
  if (pick) {
    const wrap = pick.closest('.smsi-page');
    if (wrap && wrap.dataset.pageIndex !== undefined) {
      togglePageSelection(parseInt(wrap.dataset.pageIndex, 10));
    }
    return;
  }

  const selectAllSwitch = target.closest('[data-act="select-all"]');
  if (selectAllSwitch) {
    toggleSelectAll();
    return;
  }

  const actEl = target.closest('[data-act]');
  if (!actEl) return;
  const act = actEl.getAttribute('data-act');

  switch (act) {
    case 'close':
    case 'cancel':
      closeOverlay();
      break;
    case 'zoom-in': setZoom(currentZoom() + 0.15); break;
    case 'zoom-out': setZoom(currentZoom() - 0.15); break;
    case 'zoom-reset': setZoom(DEFAULT_ZOOM); break;
    case 'key15': switchKeyCount(15); break;
    case 'key9': switchKeyCount(9); break;
    case 'tab-pages': switchTab('pages'); break;
    case 'tab-long': switchTab('long'); break;
    case 'save': saveImages(false, closeOverlay); break;
    case 'save-long': saveImages(true, closeOverlay); break;
    case 'theme': openThemePicker(); break;
    case 'close-theme': closeThemePicker(); break;
    default: break;
  }
}

function openThemePicker() {
  renderThemeList();
  view.themePicker.classList.remove('smsi-hidden');
  void view.themePicker.offsetWidth;
  view.themePicker.classList.add('smsi-show');
}

function closeThemePicker() {
  view.themePicker.classList.remove('smsi-show');
  setTimeout(() => {
    view.themePicker.classList.add('smsi-hidden');
  }, 300);
}

function renderThemeList() {
  if (!view.themeList) return;
  view.themeList.innerHTML = '';

  const randomBtn = document.createElement('div');
  randomBtn.className = 'smsi-theme-item random';
  randomBtn.innerHTML = '<span>✨ 独立随机生成 (全随机风格)</span>';
  randomBtn.onclick = () => { currentThemeKey = 'random'; applyTheme('random', userForceRound); };
  view.themeList.appendChild(randomBtn);

  Object.keys(THEMES).forEach(key => {
    const theme = THEMES[key];
    const item = document.createElement('div');
    item.className = 'smsi-theme-item';
    
    const swatch = document.createElement('div');
    swatch.className = 'smsi-theme-swatch';
    swatch.style.background = `linear-gradient(135deg, ${theme.bg}, ${theme.noteFill})`;
    
    const name = document.createElement('span');
    name.textContent = theme.name;

    item.appendChild(swatch);
    item.appendChild(name);
    item.onclick = () => { currentThemeKey = key; applyTheme(key, userForceRound); };
    view.themeList.appendChild(item);
  });
}

function applyTheme(themeKey, forceRound = null) {
  closeThemePicker();
  view.style = getThemeStyle(themeKey, forceRound);
  
  requestAnimationFrame(() => {
    rebuild();
    renderPages();
    if (getActiveTab() === 'long') ensureLongPreview();
    
    showToast(`已切换至 ${themeKey === 'random' ? '随机幻彩' : THEMES[themeKey].name}`);
  });
}

export function switchTab(tab) {
  setActiveTab(tab);
  if (view.tabPagesBtn) view.tabPagesBtn.classList.toggle('smsi-active', tab === 'pages');
  if (view.tabLongBtn) view.tabLongBtn.classList.toggle('smsi-active', tab === 'long');
  if (view.bodyPages) view.bodyPages.classList.toggle('smsi-active', tab === 'pages');
  if (view.bodyLong) view.bodyLong.classList.toggle('smsi-active', tab === 'long');
  if (view.selectAllRow) view.selectAllRow.classList.toggle('smsi-hidden', tab !== 'pages');
  if (view.btnSave) view.btnSave.classList.toggle('smsi-hidden', tab !== 'pages');
  if (view.btnSaveLong) view.btnSaveLong.classList.toggle('smsi-hidden', tab !== 'long');

  if (tab === 'long') requestAnimationFrame(() => ensureLongPreview());
  updateZoomLabel();
}

export function openOverlay() {
  const overlay = ensureOverlay();
  overlay.style.display = 'flex';
  document.body.style.overflow = 'hidden';
  requestAnimationFrame(() => overlay.classList.add('smsi-show'));
  setNativeTopBarVisible(false);
}

export function closeOverlay() {
  if (!view.overlay) return;
  const overlay = view.overlay;
  overlay.classList.remove('smsi-show');
  document.body.style.overflow = '';
  disposeObservers();
  setTimeout(() => {
    overlay.style.display = 'none';
    if (view.pagesHost) view.pagesHost.innerHTML = '';
    if (view.longHost) view.longHost.innerHTML = '';
    view.pages = [];
    view.selected = [];
    view.longCanvas = null;
  }, 220);
  setNativeTopBarVisible(true);
}

export function updateSelectUI() {
  const total = view.pages.length;
  const count = view.selected.filter(Boolean).length;
  if (view.selectCountEl) view.selectCountEl.textContent = count + ' / ' + total;
  if (view.selectAllSwitch) view.selectAllSwitch.classList.toggle('smsi-on', total > 0 && count === total);
  for (let i = 0; i < view.pages.length; i++) {
    if (!view.pages[i]._wrap) continue;
    view.pages[i]._wrap.classList.toggle('smsi-unselected', !view.selected[i]);
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
  const next = !(view.selected.filter(Boolean).length === total);
  view.selected = new Array(total).fill(next);
  updateSelectUI();
}

function persistProgress() {
  if (!view.title) return;
  saveProgress(view.title, { rows: view.rows, selected: view.selected.slice(), updatedAt: Date.now() });
}

export function restoreProgress() {
  const saved = loadProgress(view.title);
  if (!saved) return false;
  if (saved.rows === view.rows && Array.isArray(saved.selected) && saved.selected.length === view.pages.length) {
    view.selected = saved.selected.map(Boolean);
  } else {
    view.selected = new Array(view.pages.length).fill(true);
  }
  return true;
}

export function rebuild() {
  const notes = collectNotes(view.raw, view.rows);
  if (!notes.length) return false;
  view.layout = buildLayout(notes, view.rows, view.tol);
  view.pages = buildPages(view.layout, view.style);
  view.info = computeInfo(view.layout, view.rows, view.tol);
  view.infoItems = buildInfoItems(view.info, view.rows, view.bpm, view.model, view.author, view.transcriber);
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