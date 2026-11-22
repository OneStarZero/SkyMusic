/**
 * SkyMusic · 谱面图片预览层（合并版）
 * ------------------------------------------------------------------
 * 分区：
 *   1. 状态        view / activeTab / DEFAULT_ZOOM
 *   2. 工具        getBridge / showToast / showProgress / 进度记忆
 *   3. 样式        OVERLAY_CSS / injectStyle
 *   4. 模板        OVERLAY_HTML
 *   5. 渲染        renderPages / renderPageAt / ensureLongPreview / LOD
 *   6. 缩放        currentZoom / setZoom / applyPageZoom / applyLongZoom / bindPinchZoom
 *   7. 保存        buildPayload / saveImages / exportInBrowser
 *   8. 主题        themeState / buildThemeLists / applyTheme / applyCorner / applyRandom
 *   9. 勾选        updateSelectUI / togglePageSelection / toggleSelectAll
 *  10. 开关        switchTab / openOverlay / closeOverlay / toggleThemePanel
 *  11. 重建        rebuild / switchKeyCount
 *  12. 事件        onOverlayClick
 *  13. 入口        ensureOverlay / openSheetImagePreview
 */

import { STYLE_DEFAULTS, THEMES, CORNER_STYLES, RANDOM_STYLE, getThemeStyle, MAX_LONG_PIXELS, sanitizeFileName, FONT_STACK } from './style.js';
import { measurePage, buildLayout, buildPages, computeInfo, buildInfoItems, collectNotes } from './layout.js';
import { drawPageToCanvas, previewScaleFor } from './render.js';
import { createZipWriter, canvasToBytes, buildLongPngBytes } from './zip.js';

/* ==================================================================
 * 1. 状态
 * ================================================================== */

export const DEFAULT_ZOOM = 0.5;

export const view = {
  raw: null,
  title: '未命名乐谱',
  author: '',
  model: '',
  transcriber: '',
  rows: 15,
  tol: 25,
  bpm: 0,
  style: STYLE_DEFAULTS,
  layout: null,
  pages: [],
  selected: [],
  info: null,
  infoItems: [],
  mergeLong: false,
  busy: false,

  customCols: 8,
  customRows: 15,

  pageZoom: DEFAULT_ZOOM,
  longZoom: DEFAULT_ZOOM,

  overlay: null,
  titleEl: null,
  pagesHost: null,
  longHost: null,
  longCanvas: null,
  zoomLabel: null,
  key15Btn: null,
  key9Btn: null,
  selectAllRow: null,
  selectAllSwitch: null,
  selectCountEl: null,
  progressBar: null,
  progressFill: null,
  progressText: null,
  tabPagesBtn: null,
  tabLongBtn: null,
  bodyPages: null,
  bodyLong: null,
  btnSave: null,
  btnSaveLong: null,
  themeBtn: null,
  themePanel: null,
  themeList: null,
  cornerList: null,
  themeCloseBtn: null,
  rowsValEl: null,
  colsValEl: null
};

let activeTab = 'pages';
function setActiveTab(tab) { activeTab = tab; }
function getActiveTab() { return activeTab; }

/* ==================================================================
 * 2. 工具
 * ================================================================== */

function getBridge() {
  return window.AndroidBridge || window.Android || window.SkyMusicBridge || null;
}

function setNativeTopBarVisible(visible) {
  try {
    const bridge = getBridge();
    if (bridge && typeof bridge.setTopBarVisible === 'function') {
      bridge.setTopBarVisible(visible);
    }
  } catch (e) { /* 忽略 */ }
}

let toastImpl = function (message, type) {
  let box = document.getElementById('smsi-fallback-toast');
  if (!box) {
    box = document.createElement('div');
    box.id = 'smsi-fallback-toast';
    box.style.cssText = 'position:fixed;left:50%;top:30px;transform:translateX(-50%);z-index:100001;' +
      'padding:10px 18px;border-radius:12px;font-size:13px;color:#E2E8F0;' +
      'background:rgba(15,23,42,.94);border:1px solid rgba(148,163,184,.22);' +
      'box-shadow:0 10px 28px rgba(0,0,0,.4);pointer-events:none;transition:opacity .3s ease;';
    document.body.appendChild(box);
  }
  box.textContent = message;
  box.style.opacity = '1';
  clearTimeout(box._timer);
  box._timer = setTimeout(function () { box.style.opacity = '0'; }, 2600);
  box.style.borderColor = type === 'error' ? 'rgba(248,113,113,.45)' : 'rgba(148,163,184,.22)';
};

import('../ui.js')
  .then(function (m) {
    if (m && typeof m.showToast === 'function') toastImpl = m.showToast;
  })
  .catch(function () { /* 使用内置兜底 */ });

function showToast(message, type) {
  toastImpl(message, type);
}

function showProgress(text, percent) {
  if (!view.progressBar) return;
  view.progressBar.classList.add('smsi-show');
  if (view.progressText) view.progressText.textContent = text;
  if (view.progressFill) view.progressFill.style.width = Math.max(0, Math.min(100, percent)) + '%';
}

function hideProgress() {
  if (!view.progressBar) return;
  view.progressBar.classList.remove('smsi-show');
  if (view.progressFill) view.progressFill.style.width = '0%';
}

window.onSheetImageProgress = function (text, percent) {
  showProgress(text, percent);
};

const PROGRESS_KEY = 'skymusic_sheet_image_progress_v3';

function loadProgress(songName) {
  try {
    const raw = localStorage.getItem(PROGRESS_KEY);
    if (!raw) return null;
    const all = JSON.parse(raw);
    return all && all[songName] ? all[songName] : null;
  } catch (e) {
    return null;
  }
}

function saveProgress(songName, data) {
  try {
    const raw = localStorage.getItem(PROGRESS_KEY);
    const all = raw ? JSON.parse(raw) : {};
    all[songName] = data;
    const keys = Object.keys(all);
    if (keys.length > 30) {
      keys.slice(0, keys.length - 30).forEach(k => delete all[k]);
    }
    localStorage.setItem(PROGRESS_KEY, JSON.stringify(all));
  } catch (e) { /* 忽略 */ }
}

/* ==================================================================
 * 3. 样式
 * ================================================================== */

const OVERLAY_CSS = [
  '.smsi-overlay{position:fixed;inset:0;z-index:99999;display:flex;align-items:center;justify-content:center;',
  'background:rgba(8,12,24,.9);',
  'opacity:0;transition:opacity .2s ease;font-family:' + FONT_STACK + ';',
  'overscroll-behavior:contain;touch-action:none;',
  'will-change:opacity;}',
  '.smsi-overlay.smsi-show{opacity:1;}',

  '.smsi-window{width:min(92vw,760px);height:min(88vh,900px);display:flex;flex-direction:column;',
  'background:#0F172A;',
  'border:1px solid rgba(125,211,252,.12);border-radius:22px;overflow:hidden;',
  'box-shadow:0 24px 60px rgba(0,0,0,.6);',
  'transform:scale(.97);transition:transform .2s ease;',
  'contain:layout style;}',
  '.smsi-overlay.smsi-show .smsi-window{transform:scale(1);}',

  '.smsi-head{display:flex;align-items:center;gap:10px;padding:13px 16px;flex:0 0 auto;',
  'border-bottom:1px solid rgba(125,211,252,.06);background:#0F172A;}',
  '.smsi-title{font-size:13.5px;font-weight:700;color:#F8FAFC;overflow:hidden;',
  'text-overflow:ellipsis;white-space:nowrap;flex:1 1 auto;letter-spacing:.3px;}',
  '.smsi-tools{display:flex;align-items:center;gap:5px;flex:0 0 auto;}',

  '.smsi-btn{appearance:none;border:1px solid rgba(125,211,252,.14);',
  'background:rgba(30,41,59,.5);',
  'color:#CBD5E1;font-size:12px;line-height:1;padding:7px 10px;border-radius:10px;cursor:pointer;',
  'transition:background .15s ease,border-color .15s ease,color .15s ease;',
  'position:relative;}',
  '.smsi-btn:active{background:rgba(125,211,252,.15);}',
  '.smsi-btn.smsi-on{border-color:rgba(125,211,252,.55);',
  'background:rgba(125,211,252,.15);color:#7DD3FC;}',
  '.smsi-btn.smsi-icon{padding:7px 9px;font-size:13px;}',
  '.smsi-btn.smsi-close{border-color:rgba(248,113,113,.2);color:#F87171;}',

  '.smsi-tabs{display:flex;gap:6px;padding:10px 16px 0;flex:0 0 auto;}',
  '.smsi-tab{flex:1;padding:9px 12px;border-radius:11px;border:1px solid rgba(125,211,252,.1);',
  'background:rgba(30,41,59,.35);',
  'color:#94A3B8;font-size:12.5px;font-weight:600;cursor:pointer;',
  'text-align:center;transition:background .15s ease,color .15s ease,border-color .15s ease;}',
  '.smsi-tab.smsi-active{border-color:rgba(125,211,252,.5);',
  'background:rgba(125,211,252,.14);color:#7DD3FC;}',

  '.smsi-body{flex:1 1 auto;overflow:hidden;position:relative;contain:layout style;}',
  '.smsi-pane{position:absolute;inset:0;overflow:auto;padding:14px;-webkit-overflow-scrolling:touch;',
  'display:none;overscroll-behavior:contain;touch-action:pan-x pan-y;',
  'contain:layout style paint;}',
  '.smsi-pane.smsi-active{display:block;}',

  '.smsi-pages{display:flex;flex-direction:column;align-items:center;gap:16px;}',
  '.smsi-page{position:relative;border-radius:14px;overflow:hidden;background:#0B1120;',
  'box-shadow:0 8px 24px rgba(0,0,0,.45);',
  'transition:opacity .15s ease;',
  'contain:layout paint style;}',
  '.smsi-page.smsi-unselected{opacity:.4;}',
  '.smsi-canvas{display:block;width:100%;height:auto;}',

  '.smsi-pick{position:absolute;top:8px;right:8px;width:28px;height:28px;border-radius:50%;',
  'display:flex;align-items:center;justify-content:center;cursor:pointer;',
  'background:rgba(11,17,32,.7);border:1.5px solid rgba(125,211,252,.4);',
  'color:transparent;font-size:14px;font-weight:700;line-height:1;',
  'transition:background .15s ease,border-color .15s ease,color .15s ease;}',
  '.smsi-page:not(.smsi-unselected) .smsi-pick{background:rgba(125,211,252,.85);',
  'border-color:rgba(125,211,252,.85);color:#0B1120;}',

  '.smsi-pageno{position:absolute;top:10px;left:10px;font-size:10.5px;color:#CBD5E1;',
  'background:rgba(11,17,32,.7);border:1px solid rgba(148,163,184,.15);',
  'padding:2px 8px;border-radius:8px;font-variant-numeric:tabular-nums;}',

  '.smsi-long-wrap{display:flex;justify-content:center;align-items:flex-start;}',
  '.smsi-long-slot{position:relative;overflow:hidden;background:#0B1120;contain:layout paint style;}',
  '.smsi-long-canvas{display:block;border-radius:12px;}',
  '.smsi-long-empty{color:#64748B;font-size:12.5px;text-align:center;padding:40px 20px;}',

  '.smsi-zoombar{display:flex;align-items:center;justify-content:center;gap:8px;',
  'padding:8px 16px;flex:0 0 auto;border-top:1px solid rgba(125,211,252,.05);',
  'background:#0B1120;}',
  '.smsi-zoom{font-size:11px;color:#7DD3FC;min-width:40px;text-align:center;',
  'font-variant-numeric:tabular-nums;}',

  '.smsi-foot{display:flex;flex-direction:column;gap:8px;padding:10px 16px calc(12px + env(safe-area-inset-bottom));',
  'flex:0 0 auto;border-top:1px solid rgba(125,211,252,.06);background:#0B1120;}',

  '.smsi-optrow{display:flex;align-items:center;justify-content:space-between;gap:10px;}',
  '.smsi-optrow.smsi-hidden{display:none;}',
  '.smsi-optlabel{font-size:12px;color:#CBD5E1;display:flex;align-items:center;gap:7px;}',
  '.smsi-optlabel small{color:rgba(148,163,184,.7);font-size:10.5px;}',

  '.smsi-switch{position:relative;width:40px;height:23px;border-radius:12px;flex:0 0 auto;',
  'background:rgba(148,163,184,.2);border:1px solid rgba(148,163,184,.2);cursor:pointer;',
  'transition:background .18s ease,border-color .18s ease;}',
  '.smsi-switch::after{content:"";position:absolute;top:2px;left:2px;width:17px;height:17px;',
  'border-radius:50%;background:#CBD5E1;transition:transform .18s ease,background .18s ease;}',
  '.smsi-switch.smsi-on{background:rgba(125,211,252,.25);border-color:rgba(125,211,252,.55);}',
  '.smsi-switch.smsi-on::after{transform:translateX(17px);background:#7DD3FC;}',

  '.smsi-progress{display:none;flex-direction:column;gap:5px;}',
  '.smsi-progress.smsi-show{display:flex;}',
  '.smsi-progress-track{height:4px;border-radius:2px;background:rgba(148,163,184,.15);overflow:hidden;}',
  '.smsi-progress-fill{height:100%;width:0%;border-radius:2px;',
  'background:linear-gradient(90deg,#7DD3FC,#A78BFA);',
  'transition:width .15s ease;}',
  '.smsi-progress-text{font-size:10.5px;color:#94A3B8;text-align:center;}',

  '.smsi-actions{display:flex;gap:8px;align-items:center;}',
  '.smsi-actions .smsi-btn{flex:1;padding:12px 10px;font-size:13px;font-weight:600;border-radius:12px;}',
  '.smsi-actions .smsi-btn.smsi-hidden{display:none;}',
  '.smsi-actions .smsi-btn.smsi-random-inline{flex:0 0 auto;padding:12px 14px;font-size:16px;',
  'border-color:rgba(167,139,250,.3);color:#C4B5FD;',
  'background:rgba(167,139,250,.12);}',
  '.smsi-btn.smsi-primary{border:none;color:#0B1120;',
  'background:linear-gradient(135deg,#7DD3FC,#A78BFA);}',
  '.smsi-btn.smsi-long{border:none;color:#0B1120;',
  'background:linear-gradient(135deg,#A78BFA,#C4B5FD);}',

  '.smsi-theme-panel{position:absolute;top:0;right:0;bottom:0;width:min(82%,320px);',
  'background:#0B1120;border-left:1px solid rgba(125,211,252,.12);',
  'transform:translateX(100%);transition:transform .22s ease;',
  'display:flex;flex-direction:column;z-index:10;overflow-y:auto;',
  'box-shadow:-8px 0 24px rgba(0,0,0,.5);contain:layout style;}',
  '.smsi-theme-panel.smsi-show{transform:translateX(0);}',

  '.smsi-theme-head{display:flex;align-items:center;justify-content:space-between;',
  'padding:13px 16px;border-bottom:1px solid rgba(125,211,252,.08);',
  'font-size:13px;font-weight:700;color:#F8FAFC;flex:0 0 auto;background:#0F172A;}',

  '.smsi-theme-section{padding:12px 16px;}',
  '.smsi-theme-label{font-size:11.5px;color:#94A3B8;margin-bottom:8px;font-weight:600;letter-spacing:.5px;}',

  '.smsi-theme-list{display:flex;flex-direction:column;gap:8px;}',
  '.smsi-theme-item{display:flex;align-items:center;gap:10px;padding:8px 12px;',
  'border-radius:12px;border:1px solid rgba(125,211,252,.08);',
  'background:rgba(30,41,59,.45);',
  'cursor:pointer;transition:border-color .15s ease,background .15s ease;',
  'font-size:12px;color:#CBD5E1;}',
  '.smsi-theme-item.smsi-selected{border-color:rgba(125,211,252,.65);',
  'background:rgba(125,211,252,.14);color:#7DD3FC;}',

  '.smsi-theme-swatch{position:relative;width:34px;height:34px;flex:0 0 auto;}',
  '.smsi-swatch-dot{position:absolute;border-radius:50%;display:block;}',
  '.smsi-swatch-bg{width:34px;height:34px;left:0;top:0;',
  'box-shadow:0 0 0 1px rgba(255,255,255,.12) inset;}',
  '.smsi-swatch-fill{width:22px;height:22px;left:6px;top:6px;',
  'box-shadow:0 0 0 1px rgba(0,0,0,.25) inset;}',
  '.smsi-swatch-alt{width:12px;height:12px;left:11px;top:11px;',
  'box-shadow:0 0 0 1px rgba(0,0,0,.25) inset;}',

  '.smsi-theme-name{flex:1 1 auto;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}',

  '.smsi-corner-row{display:flex;gap:8px;}',
  '.smsi-corner-item{flex:1;display:flex;flex-direction:column;align-items:center;gap:6px;padding:8px;',
  'border-radius:10px;border:1px solid rgba(125,211,252,.08);',
  'background:rgba(30,41,59,.45);',
  'cursor:pointer;transition:border-color .15s ease,background .15s ease;font-size:11px;color:#CBD5E1;}',
  '.smsi-corner-item.smsi-selected{border-color:rgba(125,211,252,.65);',
  'background:rgba(125,211,252,.14);color:#7DD3FC;}',
  '.smsi-corner-preview{width:32px;height:32px;background:rgba(125,211,252,.35);',
  'border:1.5px solid rgba(125,211,252,.55);display:flex;align-items:center;justify-content:center;}',

  '.smsi-stepper{display:flex;align-items:center;gap:10px;}',
  '.smsi-stepper-val{flex:1;text-align:center;font-size:13px;font-weight:700;color:#7DD3FC;',
  'font-variant-numeric:tabular-nums;}'
].join('');

function injectStyle() {
  if (document.getElementById('smsi-style')) return;
  const styleEl = document.createElement('style');
  styleEl.id = 'smsi-style';
  styleEl.textContent = OVERLAY_CSS;
  document.head.appendChild(styleEl);
}

/* ==================================================================
 * 4. 模板
 * ================================================================== */

const OVERLAY_HTML = [
  '<div class="smsi-window">',
  '  <div class="smsi-head">',
  '    <div class="smsi-title"></div>',
  '    <div class="smsi-tools">',
  '      <button class="smsi-btn" data-act="key15" type="button">15 键</button>',
  '      <button class="smsi-btn" data-act="key9" type="button">9 键</button>',
  '      <button class="smsi-btn smsi-icon" data-act="theme" type="button">🎨</button>',
  '      <button class="smsi-btn smsi-icon smsi-close" data-act="close" type="button">✕</button>',
  '    </div>',
  '  </div>',
  '  <div class="smsi-tabs">',
  '    <div class="smsi-tab smsi-active" data-act="tab-pages">分页预览</div>',
  '    <div class="smsi-tab" data-act="tab-long">长图预览</div>',
  '  </div>',
  '  <div class="smsi-body">',
  '    <div class="smsi-pane smsi-active" data-pane="pages"><div class="smsi-pages"></div></div>',
  '    <div class="smsi-pane" data-pane="long"><div class="smsi-long-wrap"></div></div>',
  '    <div class="smsi-theme-panel" data-role="theme-panel">',
  '      <div class="smsi-theme-head">',
  '        <span>主题与样式</span>',
  '        <button class="smsi-btn smsi-icon" data-act="theme-close" type="button">✕</button>',
  '      </div>',
  '      <div class="smsi-theme-section">',
  '        <div class="smsi-theme-label">主题配色</div>',
  '        <div class="smsi-theme-list" data-role="theme-list"></div>',
  '      </div>',
  '      <div class="smsi-theme-section">',
  '        <div class="smsi-theme-label">按键样式</div>',
  '        <div class="smsi-corner-row" data-role="corner-list"></div>',
  '      </div>',
  '      <div class="smsi-theme-section">',
  '        <div class="smsi-theme-label">每页行数</div>',
  '        <div class="smsi-stepper">',
  '          <button class="smsi-btn smsi-icon" data-act="rows-minus" type="button">−</button>',
  '          <span class="smsi-stepper-val" data-role="rows-val">15</span>',
  '          <button class="smsi-btn smsi-icon" data-act="rows-plus" type="button">+</button>',
  '        </div>',
  '      </div>',
  '      <div class="smsi-theme-section">',
  '        <div class="smsi-theme-label">每行列数</div>',
  '        <div class="smsi-stepper">',
  '          <button class="smsi-btn smsi-icon" data-act="cols-minus" type="button">−</button>',
  '          <span class="smsi-stepper-val" data-role="cols-val">8</span>',
  '          <button class="smsi-btn smsi-icon" data-act="cols-plus" type="button">+</button>',
  '        </div>',
  '      </div>',
  '    </div>',
  '  </div>',
  '  <div class="smsi-zoombar">',
  '    <button class="smsi-btn smsi-icon" data-act="zoom-out" type="button">−</button>',
  '    <span class="smsi-zoom">50%</span>',
  '    <button class="smsi-btn smsi-icon" data-act="zoom-in" type="button">+</button>',
  '    <button class="smsi-btn smsi-icon" data-act="zoom-reset" type="button">⟲</button>',
  '  </div>',
  '  <div class="smsi-foot">',
  '    <div class="smsi-optrow" data-role="select-all-row">',
  '      <div class="smsi-optlabel">',
  '        <span>全选</span>',
  '        <small data-role="select-count">0 / 0</small>',
  '      </div>',
  '      <div class="smsi-switch smsi-on" data-act="select-all" role="switch"></div>',
  '    </div>',
  '    <div class="smsi-progress" data-role="progress">',
  '      <div class="smsi-progress-track"><div class="smsi-progress-fill"></div></div>',
  '      <div class="smsi-progress-text">准备中…</div>',
  '    </div>',
  '    <div class="smsi-actions">',
  '      <button class="smsi-btn smsi-icon smsi-random-inline" data-act="random" type="button" title="随机样式">🎲</button>',
  '      <button class="smsi-btn" data-act="cancel" type="button">取消</button>',
  '      <button class="smsi-btn smsi-long smsi-hidden" data-act="save-long" type="button">保存长图</button>',
  '      <button class="smsi-btn smsi-primary" data-act="save" type="button">保存图片</button>',
  '    </div>',
  '  </div>',
  '</div>'
].join('');

/* ==================================================================
 * 5. 渲染
 * ================================================================== */

let pageObserver = null;
let longObserver = null;

function disposeObservers() {
  if (pageObserver) { pageObserver.disconnect(); pageObserver = null; }
  if (longObserver) { longObserver.disconnect(); longObserver = null; }
}

const LOD_HIGH = 1.0;
const LOD_MID = 0.5;
const LOD_LOW = 0.25;

function renderPages() {
  const host = view.pagesHost;
  if (!host) return;
  host.innerHTML = '';

  const meta = {
    rows: view.rows,
    title: view.title,
    infoItems: view.infoItems
  };

  const m = measurePage(view.rows, view.style, meta.infoItems.length, view.customRows, view.customCols);
  const w = Math.round(m.width * view.pageZoom) + 'px';

  const frag = document.createDocumentFragment();

  view.pages.forEach(function (page, index) {
    const wrap = document.createElement('div');
    wrap.className = 'smsi-page';
    wrap.dataset.pageIndex = String(index);
    wrap.style.width = w;

    const canvas = document.createElement('canvas');
    canvas.className = 'smsi-canvas';
    wrap.appendChild(canvas);

    const pageno = document.createElement('div');
    pageno.className = 'smsi-pageno';
    pageno.textContent = (index + 1) + ' / ' + view.pages.length;
    wrap.appendChild(pageno);

    const pick = document.createElement('div');
    pick.className = 'smsi-pick';
    pick.textContent = '✓';
    wrap.appendChild(pick);

    frag.appendChild(wrap);

    page._wrap = wrap;
    page._canvas = canvas;
    page._rendered = false;
    page._lod = 0;
  });

  host.appendChild(frag);

  const initial = Math.min(2, view.pages.length);
  for (let i = 0; i < initial; i++) {
    renderPageAt(i, meta, LOD_HIGH);
  }

  const pane = view.bodyPages;
  if (pane) {
    let ticking = false;
    pane.onscroll = function () {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(function () {
        ticking = false;
        updateLod(meta);
      });
    };
  }
}

function updateLod(meta) {
  const pane = view.bodyPages;
  if (!pane) return;

  const scrollTop = pane.scrollTop;
  const viewH = pane.clientHeight;

  for (let i = 0; i < view.pages.length; i++) {
    const page = view.pages[i];
    if (!page._wrap) continue;

    const top = page._wrap.offsetTop;
    const bottom = top + page._wrap.offsetHeight;

    let targetLod = 0;
    if (bottom > scrollTop && top < scrollTop + viewH) {
      targetLod = LOD_HIGH;
    } else if (bottom > scrollTop - viewH && top < scrollTop + viewH * 2) {
      targetLod = LOD_MID;
    } else if (bottom > scrollTop - viewH * 2 && top < scrollTop + viewH * 3) {
      targetLod = LOD_LOW;
    }

    if (targetLod > 0 && page._lod !== targetLod) {
      renderPageAt(i, meta, targetLod);
    }
  }
}

function renderPageAt(index, meta, lod) {
  const page = view.pages[index];
  if (!page || !page._canvas) return;

  const targetLod = lod || LOD_HIGH;
  if (page._rendered && page._lod === targetLod) return;

  try {
    const baseScale = previewScaleFor(view.rows, view.style, meta.infoItems.length);
    const scale = baseScale * targetLod;
    drawPageToCanvas(page._canvas, page, index, view.pages.length, meta, view.style, scale);
    page._rendered = true;
    page._lod = targetLod;
  } catch (err) {
    console.error('[sheet-image] 渲染第 ' + (index + 1) + ' 页失败', err);
  }
}

const LONG_BASE_WIDTH = 720;

function ensureLongPreview() {
  if (!view.longHost) return;
  if (!view.pages.length) return;

  const meta = {
    rows: view.rows,
    title: view.title,
    infoItems: view.infoItems
  };

  const m = measurePage(view.rows, view.style, meta.infoItems.length, view.customRows, view.customCols);
  const logicalW = m.width;
  const logicalH = m.height;
  if (!logicalW || !logicalH) return;

  const gapPx = Math.round(view.style.pad * 0.6);
  const scale = LONG_BASE_WIDTH / logicalW;
  const slotW = Math.round(logicalW * scale * view.longZoom);
  const slotH = Math.round(logicalH * scale * view.longZoom);
  const gapScaled = Math.round(gapPx * scale * view.longZoom);

  view.longHost.innerHTML = '';

  const container = document.createElement('div');
  container.style.cssText =
    'display:flex;flex-direction:column;align-items:center;' +
    'gap:' + gapScaled + 'px;' +
    'margin:0 auto;width:' + slotW + 'px;';

  const frag = document.createDocumentFragment();

  for (let i = 0; i < view.pages.length; i++) {
    const slot = document.createElement('div');
    slot.className = 'smsi-long-slot';
    slot.dataset.pageIndex = String(i);
    slot.style.cssText =
      'width:' + slotW + 'px;height:' + slotH + 'px;' +
      'border-radius:12px;background:#0B1120;' +
      'box-shadow:0 8px 24px rgba(0,0,0,.45);' +
      'overflow:hidden;';
    frag.appendChild(slot);
  }

  container.appendChild(frag);
  view.longHost.appendChild(container);

  view.longCanvas = {
    _baseWidth: LONG_BASE_WIDTH,
    _scale: scale,
    _slotW: slotW,
    _slotH: slotH,
    _container: container,
    _slots: container.children
  };

  observeLongSlots(container, meta, slotW, slotH);
}

function observeLongSlots(container, meta, slotW, slotH) {
  if (longObserver) longObserver.disconnect();

  longObserver = new IntersectionObserver(function (entries) {
    for (let i = 0; i < entries.length; i++) {
      const entry = entries[i];
      const slot = entry.target;
      const index = parseInt(slot.dataset.pageIndex, 10);
      if (isNaN(index)) continue;

      if (entry.isIntersecting) {
        renderLongSlot(slot, index, meta, slotW, slotH);
      } else {
        releaseLongSlot(slot);
      }
    }
  }, {
    root: view.bodyLong,
    rootMargin: '400px 0px',
    threshold: 0
  });

  for (let i = 0; i < container.children.length; i++) {
    longObserver.observe(container.children[i]);
  }
}

function renderLongSlot(slot, index, meta, slotW, slotH) {
  if (slot._rendered) return;

  const page = view.pages[index];
  if (!page || !page._canvas) return;

  if (!page._rendered || page._lod !== LOD_HIGH) {
    renderPageAt(index, meta, LOD_HIGH);
  }

  const src = page._canvas;
  if (!src.width || src.width <= 1) return;

  const canvas = document.createElement('canvas');
  canvas.className = 'smsi-long-canvas';
  canvas.width = slotW;
  canvas.height = slotH;
  canvas.style.cssText =
    'display:block;width:' + slotW + 'px;height:' + slotH + 'px;';

  const ctx = canvas.getContext('2d', { alpha: false });
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(src, 0, 0, src.width, src.height, 0, 0, slotW, slotH);

  slot.innerHTML = '';
  slot.appendChild(canvas);
  slot._rendered = true;
}

function releaseLongSlot(slot) {
  if (!slot._rendered) return;
  slot.innerHTML = '';
  slot._rendered = false;
}

/* ==================================================================
 * 6. 缩放
 * ================================================================== */

function currentZoom() {
  return getActiveTab() === 'long' ? view.longZoom : view.pageZoom;
}

function setZoom(z) {
  const next = Math.max(0.2, Math.min(3, Number(z) || DEFAULT_ZOOM));
  if (getActiveTab() === 'long') {
    view.longZoom = next;
    applyLongZoom();
  } else {
    view.pageZoom = next;
    applyPageZoom();
  }
  updateZoomLabel();
}

function updateZoomLabel() {
  if (view.zoomLabel) {
    view.zoomLabel.textContent = Math.round(currentZoom() * 100) + '%';
  }
}

function applyPageZoom() {
  const m = measurePage(view.rows, view.style, view.infoItems.length, view.customRows, view.customCols);
  const w = Math.round(m.width * view.pageZoom) + 'px';
  for (let i = 0; i < view.pages.length; i++) {
    const page = view.pages[i];
    if (!page._wrap) continue;
    page._wrap.style.width = w;
  }
}

function applyLongZoom() {
  if (!view.longCanvas || !view.longCanvas._container) return;
  const container = view.longCanvas._container;
  const baseW = view.longCanvas._baseWidth;
  const scale = view.longCanvas._scale;
  const slots = view.longCanvas._slots;

  const gapPx = Math.round(view.style.pad * 0.6);
  container.style.width = Math.round(baseW * view.longZoom) + 'px';
  container.style.gap = Math.round(gapPx * scale * view.longZoom) + 'px';

  for (let i = 0; i < slots.length; i++) {
    const slot = slots[i];
    const index = parseInt(slot.dataset.pageIndex, 10);
    if (isNaN(index)) continue;

    const page = view.pages[index];
    if (!page || !page._canvas) continue;

    const c = page._canvas;
    const w = Math.round(c.width * scale * view.longZoom);
    const h = Math.round(c.height * scale * view.longZoom);

    slot.style.width = w + 'px';
    slot.style.height = h + 'px';

    const inner = slot.firstElementChild;
    if (inner) {
      inner.style.width = w + 'px';
      inner.style.height = h + 'px';
    }
  }
}

function bindPinchZoom(el, kind) {
  if (!el) return;
  let startDist = 0;
  let startZoom = 1;

  el.addEventListener('touchstart', function (e) {
    if (e.touches.length === 2) {
      startDist = touchDistance(e.touches[0], e.touches[1]);
      startZoom = kind === 'long' ? view.longZoom : view.pageZoom;
    }
  }, { passive: true });

  el.addEventListener('touchmove', function (e) {
    if (e.touches.length === 2 && startDist > 0) {
      e.preventDefault();
      const dist = touchDistance(e.touches[0], e.touches[1]);
      const ratio = dist / startDist;
      const next = Math.max(0.2, Math.min(3, startZoom * ratio));
      if (kind === 'long') {
        view.longZoom = next;
        applyLongZoom();
      } else {
        view.pageZoom = next;
        applyPageZoom();
      }
      updateZoomLabel();
    }
  }, { passive: false });

  el.addEventListener('touchend', function (e) {
    if (e.touches.length < 2) startDist = 0;
  }, { passive: true });
}

function touchDistance(a, b) {
  const dx = a.clientX - b.clientX;
  const dy = a.clientY - b.clientY;
  return Math.sqrt(dx * dx + dy * dy);
}

/* ==================================================================
 * 7. 保存
 * ================================================================== */

function renderPageToExportCanvas(index, meta) {
  const page = view.pages[index];
  if (!page) return null;

  const canvas = document.createElement('canvas');
  const baseScale = previewScaleFor(view.rows, view.style, meta.infoItems.length);

  try {
    drawPageToCanvas(canvas, page, index, view.pages.length, meta, view.style, baseScale);
  } catch (err) {
    console.error('[sheet-image] 导出第 ' + (index + 1) + ' 页失败', err);
    return null;
  }

  if (!canvas.width || canvas.width <= 1) return null;
  return canvas;
}

function buildPayload(longOnly) {
  const selectedPages = [];
  for (let i = 0; i < view.pages.length; i++) {
    if (!longOnly && !view.selected[i]) continue;
    const page = view.pages[i];
    selectedPages.push({
      index: i,
      columns: page.columns.map(function (col) {
        return { t: Math.round(col.t || 0), rows: col.rows.slice() };
      })
    });
  }

  return {
    version: 9,
    songName: view.title,
    rows: view.rows,
    cols: view.customCols,
    rowsPerPage: view.customRows,
    style: view.style,
    mergeLong: longOnly ? true : view.mergeLong,
    longOnly: Boolean(longOnly),
    totalPages: view.pages.length,
    infoItems: view.infoItems,
    pages: selectedPages
  };
}

function saveImages(longOnly, onClose) {
  if (view.busy) return;
  view.busy = true;

  if (!longOnly) {
    const count = view.selected.filter(Boolean).length;
    if (count === 0) {
      view.busy = false;
      showToast('请至少选择一页', 'error');
      return;
    }
  }

  const bridge = getBridge();
  const payload = buildPayload(longOnly);

  if (bridge && typeof bridge.saveSheetImage === 'function') {
    showProgress(longOnly ? '正在生成长图…' : '正在生成图片…', 5);

    setTimeout(function () {
      let raw = '';
      try {
        raw = bridge.saveSheetImage(JSON.stringify(payload));
      } catch (err) {
        view.busy = false;
        hideProgress();
        showToast('保存失败：' + (err && err.message ? err.message : err), 'error');
        return;
      }
      view.busy = false;
      hideProgress();

      let res = null;
      try {
        res = typeof raw === 'string' ? JSON.parse(raw) : raw;
      } catch (err) {
        showToast('保存失败：返回数据异常', 'error');
        return;
      }

      if (res && res.ok) {
        const files = (res.files && res.files.length) || 1;
        showToast('已保存 ' + files + ' 张图片到 ' + (res.dir || 'SkyMusic-OneStar'), 'success');
        if (onClose) onClose();
      } else {
        showToast('保存失败：' + ((res && res.error) || '未知错误'), 'error');
      }
    }, 60);
    return;
  }

  exportInBrowser(longOnly, onClose);
}

async function exportInBrowser(longOnly, onClose) {
  try {
    const fileNameBase = sanitizeFileName(view.title);

    const meta = {
      rows: view.rows,
      title: view.title,
      infoItems: view.infoItems
    };

    if (longOnly) {
      showProgress('正在准备长图…', 5);

      const canvases = [];
      for (let i = 0; i < view.pages.length; i++) {
        const c = renderPageToExportCanvas(i, meta);
        if (c) canvases.push(c);
        if (i % 4 === 3) await nextTick();
      }
      if (!canvases.length) throw new Error('没有可用的页面');

      const gapPx = Math.round(view.style.pad * 0.6);
      const parts = await buildLongPngBytes(
        canvases, gapPx, view.style.bg, MAX_LONG_PIXELS,
        function (p) { showProgress('正在生成长图 ' + p + '%', p); }
      );

      releaseCanvases(canvases);

      if (parts.length === 1) {
        const blob = new Blob([parts[0].bytes], { type: 'image/png' });
        downloadBlob(blob, fileNameBase + '-Full.png');
      } else {
        const zip = createZipWriter();
        for (let i = 0; i < parts.length; i++) {
          zip.addFile(fileNameBase + '-Full-' + parts[i].part + '.png', parts[i].bytes);
        }
        const blob = zip.finish();
        downloadBlob(blob, fileNameBase + '-Full.zip');
      }

      showProgress('完成', 100);
      setTimeout(hideProgress, 600);
      view.busy = false;
      showToast('长图已保存（' + parts.length + ' 张）', 'success');
      if (onClose) onClose();
      return;
    }

    const targets = [];
    for (let i = 0; i < view.pages.length; i++) {
      if (view.selected[i]) targets.push(i);
    }
    if (!targets.length) throw new Error('没有选中任何页面');

    const total = targets.length;
    const zip = createZipWriter();
    const selectedCanvases = [];

    for (let k = 0; k < total; k++) {
      const i = targets[k];

      showProgress('正在打包 ' + (k + 1) + ' / ' + total, Math.round((k / total) * 60));

      const canvas = renderPageToExportCanvas(i, meta);
      if (!canvas) continue;

      const name = i === 0 ? fileNameBase + '.png' : fileNameBase + '-' + (i + 1) + '.png';
      const bytes = await canvasToBytes(canvas);
      zip.addFile(name, bytes);
      selectedCanvases.push(canvas);

      await nextTick();
    }

    if (view.mergeLong && selectedCanvases.length > 0) {
      const gapPx = Math.round(view.style.pad * 0.6);
      const parts = await buildLongPngBytes(
        selectedCanvases, gapPx, view.style.bg, MAX_LONG_PIXELS,
        function (p) { showProgress('正在生成长图 ' + p + '%', 60 + Math.round(p * 0.3)); }
      );
      for (let i = 0; i < parts.length; i++) {
        const name = parts.length === 1
          ? fileNameBase + '-Full.png'
          : fileNameBase + '-Full-' + parts[i].part + '.png';
        zip.addFile(name, parts[i].bytes);
      }
    }

    showProgress('正在生成 zip…', 94);
    const blob = zip.finish();
    downloadBlob(blob, fileNameBase + '.zip');

    releaseCanvases(selectedCanvases);

    showProgress('完成', 100);
    setTimeout(hideProgress, 600);
    view.busy = false;
    showToast('已打包 ' + (selectedCanvases.length + (view.mergeLong ? 1 : 0)) + ' 张图片', 'success');
    if (onClose) onClose();
  } catch (err) {
    view.busy = false;
    hideProgress();
    showToast('导出失败：' + (err && err.message ? err.message : err), 'error');
  }
}

function releaseCanvases(list) {
  for (let i = 0; i < list.length; i++) {
    const c = list[i];
    if (c) {
      c.width = 0;
      c.height = 0;
    }
  }
  list.length = 0;
}

function downloadBlob(blob, fileName) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(function () { URL.revokeObjectURL(url); }, 6000);
}

function nextTick() {
  return new Promise(function (resolve) {
    setTimeout(resolve, 0);
  });
}

/* ==================================================================
 * 8. 主题
 * ================================================================== */

const themeState = {
  themeKey: 'morning',
  cornerKey: 'round',
  isRandom: false
};

function buildThemeLists() {
  if (!view.themeList || !view.cornerList) return;
  buildThemeList();
  buildCornerList();
}

function buildThemeList() {
  const host = view.themeList;
  host.innerHTML = '';

  const keys = Object.keys(THEMES);
  const frag = document.createDocumentFragment();

  for (let i = 0; i < keys.length; i++) {
    const key = keys[i];
    const t = THEMES[key];
    if (!t) continue;

    const item = document.createElement('div');
    item.className = 'smsi-theme-item' + (themeState.themeKey === key && !themeState.isRandom ? ' smsi-selected' : '');
    item.dataset.role = 'theme-item';
    item.dataset.key = key;

    const swatch = document.createElement('div');
    swatch.className = 'smsi-theme-swatch';

    const c1 = document.createElement('span');
    c1.className = 'smsi-swatch-dot smsi-swatch-bg';
    c1.style.background = t.bg || '#0B1120';

    const c2 = document.createElement('span');
    c2.className = 'smsi-swatch-dot smsi-swatch-fill';
    c2.style.background = t.noteFill || '#7DD3FC';

    const c3 = document.createElement('span');
    c3.className = 'smsi-swatch-dot smsi-swatch-alt';
    c3.style.background = t.noteFillAlt || t.noteFill || '#A78BFA';

    swatch.appendChild(c1);
    swatch.appendChild(c2);
    swatch.appendChild(c3);

    const label = document.createElement('span');
    label.className = 'smsi-theme-name';
    label.textContent = t.name || key;

    item.appendChild(swatch);
    item.appendChild(label);
    frag.appendChild(item);
  }

  host.appendChild(frag);
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

function applyTheme(key) {
  themeState.themeKey = key;
  themeState.isRandom = false;

  const corner = CORNER_STYLES[themeState.cornerKey] || CORNER_STYLES.round;
  view.style = getThemeStyle(key, themeState.cornerKey);
  view.style.noteShape = corner.noteShape;

  buildThemeLists();
  refreshAllPages();
  showToast('已切换主题：' + (THEMES[key] ? THEMES[key].name : key), 'success');
}

function applyCorner(key) {
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

function applyRandom() {
  themeState.isRandom = true;
  view.style = getThemeStyle('random', themeState.cornerKey);

  buildThemeLists();
  refreshAllPages();
  showToast('已应用：' + RANDOM_STYLE.name, 'success');
}

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

function restoreTheme(saved) {
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

/* ==================================================================
 * 9. 勾选
 * ================================================================== */

function updateSelectUI() {
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

function persistProgress() {
  if (!view.title) return;
  saveProgress(view.title, {
    rows: view.rows,
    selected: view.selected.slice(),
    themeKey: themeState.themeKey,
    cornerKey: themeState.cornerKey,
    isRandom: themeState.isRandom,
    customCols: view.customCols,
    customRows: view.customRows,
    updatedAt: Date.now()
  });
}

function restoreProgress() {
  const saved = loadProgress(view.title);
  if (!saved) return false;

  if (saved.rows === view.rows && Array.isArray(saved.selected) &&
      saved.selected.length === view.pages.length) {
    view.selected = saved.selected.map(Boolean);
  } else {
    view.selected = new Array(view.pages.length).fill(true);
  }

  if (typeof saved.customCols === 'number') view.customCols = saved.customCols;
  if (typeof saved.customRows === 'number') view.customRows = saved.customRows;

  restoreTheme(saved);

  return true;
}

/* ==================================================================
 * 10. 开关
 * ================================================================== */

function switchTab(tab) {
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

function openOverlay() {
  const overlay = view.overlay;
  if (!overlay) return;

  overlay.style.display = 'flex';
  overlay.style.opacity = '0';
  void overlay.offsetHeight;
  requestAnimationFrame(function () {
    overlay.classList.add('smsi-show');
    overlay.style.opacity = '';
  });
}

function closeOverlay() {
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

function toggleThemePanel(force) {
  if (!view.themePanel) return;
  const show = force !== undefined ? force : !view.themePanel.classList.contains('smsi-show');
  view.themePanel.classList.toggle('smsi-show', show);
}

/* ==================================================================
 * 11. 重建
 * ================================================================== */

function rebuild() {
  const notes = collectNotes(view.raw, view.rows);
  if (!notes.length) return false;

  view.layout = buildLayout(notes, view.rows, view.tol);
  view.pages = buildPages(view.layout, view.style, view.customRows, view.customCols);
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

function updateStepperUI() {
  if (view.rowsValEl) view.rowsValEl.textContent = String(view.customRows);
  if (view.colsValEl) view.colsValEl.textContent = String(view.customCols);
}

/* ==================================================================
 * 12. 事件
 * ================================================================== */

function onOverlayClick(ev) {
  const target = ev.target;
  if (!target || !target.closest) return;

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

  const themeItem = target.closest('[data-role="theme-item"]');
  if (themeItem) {
    applyTheme(themeItem.dataset.key);
    return;
  }

  const cornerItem = target.closest('[data-role="corner-item"]');
  if (cornerItem) {
    applyCorner(cornerItem.dataset.key);
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
    case 'rows-minus':
      view.customRows = Math.max(6, view.customRows - 1);
      updateStepperUI();
      rebuild();
      renderPages();
      updateSelectUI();
      break;
    case 'rows-plus':
      view.customRows = Math.min(20, view.customRows + 1);
      updateStepperUI();
      rebuild();
      renderPages();
      updateSelectUI();
      break;
    case 'cols-minus':
      view.customCols = Math.max(3, view.customCols - 1);
      updateStepperUI();
      rebuild();
      renderPages();
      updateSelectUI();
      break;
    case 'cols-plus':
      view.customCols = Math.min(12, view.customCols + 1);
      updateStepperUI();
      rebuild();
      renderPages();
      updateSelectUI();
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

/* ==================================================================
 * 13. 入口
 * ================================================================== */

function ensureOverlay() {
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
  view.themeList = overlay.querySelector('[data-role="theme-list"]');
  view.cornerList = overlay.querySelector('[data-role="corner-list"]');
  view.themeCloseBtn = overlay.querySelector('[data-act="theme-close"]');
  view.rowsValEl = overlay.querySelector('[data-role="rows-val"]');
  view.colsValEl = overlay.querySelector('[data-role="cols-val"]');

  buildThemeLists();
  updateStepperUI();

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

/* ==================================================================
 * 对外入口
 * ================================================================== */

/**
 * 打开谱面图片预览
 * @param {Object} sheet 谱面数据（songNotes 等）
 * @param {Object} opts { songName, author, model, transcriber, bpm, keyCount, toleranceMs }
 */
export function openSheetImagePreview(sheet, opts) {
  opts = opts || {};

  view.raw = sheet;
  view.title = opts.songName || sheet.name || '未命名乐谱';
  view.author = opts.author || sheet.author || '';
  view.model = opts.model || sheet.model || '';
  view.transcriber = opts.transcriber || sheet.transcribedBy || '';
  view.bpm = opts.bpm || sheet.bpm || 0;
  view.rows = opts.keyCount === 9 ? 9 : 15;
  view.tol = typeof opts.toleranceMs === 'number' ? opts.toleranceMs : 25;
  view.mergeLong = false;
  view.busy = false;
  view.pageZoom = DEFAULT_ZOOM;
  view.longZoom = DEFAULT_ZOOM;
  view.customCols = 8;
  view.customRows = 15;

  ensureOverlay();

  const ok = rebuild();
  if (!ok) {
    showToast('没有可绘制的音符', 'error');
    return;
  }

  restoreProgress();
  updateStepperUI();
  switchTab('pages');
  renderPages();
  updateSelectUI();
  updateZoomLabel();

  document.body.style.overflow = 'hidden';
  setNativeTopBarVisible(false);
  openOverlay();
}
