/**
 * SkyMusic · 谱面图片预览层
 * 负责：DOM 构建、勾选、缩放、保存调度（App 走原生 / 浏览器走 zip）
 */

import {
  STYLE_DEFAULTS,
  FONT_STACK,
  MAX_LONG_PIXELS,
  clampInt,
  sanitizeFileName
} from './style.js';

import {
  collectNotes,
  buildLayout,
  buildPages,
  measurePage,
  computeInfo,
  buildInfoItems
} from './layout.js';

import { drawPageToCanvas, previewScaleFor } from './render.js';
import { createZipWriter, canvasToBytes, mergeCanvasesToLong } from './zip.js';

/* ========================== 模块状态 ========================== */

const view = {
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
  zoom: 1,
  busy: false,

  overlay: null,
  titleEl: null,
  pagesHost: null,
  zoomLabel: null,
  key15Btn: null,
  key9Btn: null,
  selectAllSwitch: null,
  selectCountEl: null,
  mergeSwitch: null
};

/* ========================== Toast ========================== */

let toast = function (message, type) {
  let box = document.getElementById('smsi-fallback-toast');
  if (!box) {
    box = document.createElement('div');
    box.id = 'smsi-fallback-toast';
    box.style.cssText = 'position:fixed;left:50%;bottom:92px;transform:translateX(-50%);z-index:100001;' +
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

import('./../ui.js')
  .then(function (m) {
    if (m && typeof m.showToast === 'function') toast = m.showToast;
  })
  .catch(function () { /* 使用内置兜底 */ });

/* ========================== 桥接 ========================== */

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

/* ========================== 样式注入 ========================== */

const OVERLAY_CSS = [
  '.smsi-overlay{position:fixed;inset:0;z-index:99999;display:flex;flex-direction:column;',
  'background:radial-gradient(120% 80% at 50% 0%,rgba(30,27,75,.55),rgba(8,12,24,.985) 60%);',
  'opacity:0;transition:opacity .24s ease;font-family:' + FONT_STACK + ';}',
  '.smsi-overlay.smsi-show{opacity:1;}',

  '.smsi-head{display:flex;align-items:center;gap:12px;padding:14px 16px;flex:0 0 auto;',
  'border-bottom:1px solid rgba(125,211,252,.10);background:rgba(11,17,32,.55);}',
  '.smsi-title{font-size:14px;font-weight:700;color:#F8FAFC;overflow:hidden;',
  'text-overflow:ellipsis;white-space:nowrap;flex:1 1 auto;letter-spacing:.3px;}',
  '.smsi-tools{display:flex;align-items:center;gap:6px;flex:0 0 auto;}',

  '.smsi-btn{appearance:none;border:1px solid rgba(125,211,252,.18);background:rgba(30,41,59,.42);',
  'color:#CBD5E1;font-size:12.5px;line-height:1;padding:8px 11px;border-radius:11px;cursor:pointer;',
  'transition:background .18s ease,border-color .18s ease,color .18s ease,transform .12s ease;}',
  '.smsi-btn:active{transform:scale(.94);}',
  '.smsi-btn.smsi-on{border-color:rgba(125,211,252,.62);background:rgba(125,211,252,.16);color:#7DD3FC;',
  'box-shadow:0 0 0 1px rgba(125,211,252,.12) inset;}',
  '.smsi-btn.smsi-icon{padding:8px 10px;font-size:14px;}',
  '.smsi-btn.smsi-close{border-color:rgba(248,113,113,.22);color:#F87171;}',
  '.smsi-btn.smsi-close:active{background:rgba(248,113,113,.14);}',

  '.smsi-zoom{font-size:11.5px;color:#7DD3FC;min-width:42px;text-align:center;',
  'font-variant-numeric:tabular-nums;letter-spacing:.4px;}',

  '.smsi-body{flex:1 1 auto;overflow:auto;padding:18px 16px 24px;-webkit-overflow-scrolling:touch;}',
  '.smsi-pages{display:flex;flex-direction:column;align-items:center;gap:20px;}',

  '.smsi-page{position:relative;border-radius:16px;overflow:hidden;background:#0B1120;',
  'box-shadow:0 14px 40px rgba(0,0,0,.55),0 0 0 1px rgba(125,211,252,.10);',
  'transition:box-shadow .2s ease,opacity .2s ease;}',
  '.smsi-page.smsi-unselected{opacity:.42;box-shadow:0 6px 18px rgba(0,0,0,.4),0 0 0 1px rgba(148,163,184,.10);}',
  '.smsi-canvas{display:block;width:100%;height:auto;}',

  '.smsi-pick{position:absolute;top:10px;right:10px;width:30px;height:30px;border-radius:50%;',
  'display:flex;align-items:center;justify-content:center;cursor:pointer;',
  'background:rgba(11,17,32,.72);border:1.5px solid rgba(125,211,252,.45);',
  'color:transparent;font-size:15px;font-weight:700;line-height:1;',
  'transition:background .18s ease,border-color .18s ease,color .18s ease,transform .12s ease;}',
  '.smsi-pick:active{transform:scale(.9);}',
  '.smsi-page:not(.smsi-unselected) .smsi-pick{background:rgba(125,211,252,.9);',
  'border-color:rgba(125,211,252,.9);color:#0B1120;}',

  '.smsi-pageno{position:absolute;top:12px;left:12px;font-size:11px;color:#CBD5E1;',
  'background:rgba(11,17,32,.72);border:1px solid rgba(148,163,184,.18);',
  'padding:3px 9px;border-radius:9px;font-variant-numeric:tabular-nums;}',

  '.smsi-hint{font-size:11px;color:rgba(148,163,184,.55);text-align:center;',
  'padding:0 16px 10px;letter-spacing:.3px;}',

  '.smsi-foot{display:flex;flex-direction:column;gap:10px;padding:12px 16px calc(14px + env(safe-area-inset-bottom));',
  'flex:0 0 auto;border-top:1px solid rgba(125,211,252,.10);background:rgba(11,17,32,.72);}',

  '.smsi-optrow{display:flex;align-items:center;justify-content:space-between;gap:12px;}',
  '.smsi-optlabel{font-size:12.5px;color:#CBD5E1;display:flex;align-items:center;gap:8px;}',
  '.smsi-optlabel small{color:rgba(148,163,184,.7);font-size:11px;}',

  '.smsi-switch{position:relative;width:44px;height:25px;border-radius:13px;flex:0 0 auto;',
  'background:rgba(148,163,184,.22);border:1px solid rgba(148,163,184,.22);cursor:pointer;',
  'transition:background .22s ease,border-color .22s ease;}',
  '.smsi-switch::after{content:"";position:absolute;top:2px;left:2px;width:19px;height:19px;',
  'border-radius:50%;background:#CBD5E1;transition:transform .22s cubic-bezier(.34,1.56,.64,1),background .22s ease;}',
  '.smsi-switch.smsi-on{background:rgba(125,211,252,.28);border-color:rgba(125,211,252,.6);}',
  '.smsi-switch.smsi-on::after{transform:translateX(19px);background:#7DD3FC;}',

  '.smsi-actions{display:flex;gap:12px;align-items:center;justify-content:center;}',
  '.smsi-actions .smsi-btn{flex:1;max-width:180px;padding:13px 18px;font-size:14px;font-weight:600;border-radius:14px;}',
  '.smsi-btn.smsi-primary{border:none;color:#0B1120;',
  'background:linear-gradient(135deg,#7DD3FC,#A78BFA);',
  'box-shadow:0 6px 20px rgba(125,211,252,.28);}',
  '.smsi-btn.smsi-primary:active{box-shadow:0 3px 12px rgba(125,211,252,.22);}'
].join('');

function injectStyle() {
  if (document.getElementById('smsi-style')) return;
  const styleEl = document.createElement('style');
  styleEl.id = 'smsi-style';
  styleEl.textContent = OVERLAY_CSS;
  document.head.appendChild(styleEl);
}

/* ========================== DOM 构建 ========================== */

function ensureOverlay() {
  if (view.overlay) return view.overlay;
  injectStyle();

  const overlay = document.createElement('div');
  overlay.className = 'smsi-overlay';
  overlay.innerHTML = [
    '<div class="smsi-head">',
    '  <div class="smsi-title"></div>',
    '  <div class="smsi-tools">',
    '    <button class="smsi-btn" data-act="key15" type="button">15 键</button>',
    '    <button class="smsi-btn" data-act="key9" type="button">9 键</button>',
    '    <button class="smsi-btn smsi-icon" data-act="zoom-out" type="button">−</button>',
    '    <span class="smsi-zoom">100%</span>',
    '    <button class="smsi-btn smsi-icon" data-act="zoom-in" type="button">+</button>',
    '    <button class="smsi-btn smsi-icon smsi-close" data-act="close" type="button">✕</button>',
    '  </div>',
    '</div>',
    '<div class="smsi-body"><div class="smsi-pages"></div></div>',
    '<div class="smsi-hint">保存目录：SkyMusic-OneStar/谱面名称/</div>',
    '<div class="smsi-foot">',
    '  <div class="smsi-optrow">',
    '    <div class="smsi-optlabel">',
    '      <span>全选</span>',
    '      <small data-role="select-count">0 / 0</small>',
    '    </div>',
    '    <div class="smsi-switch smsi-on" data-act="select-all" role="switch"></div>',
    '  </div>',
    '  <div class="smsi-optrow">',
    '    <div class="smsi-optlabel">',
    '      <span>同时保存完整长图</span>',
    '      <small>所有页上下拼接</small>',
    '    </div>',
    '    <div class="smsi-switch" data-act="merge-long" role="switch"></div>',
    '  </div>',
    '  <div class="smsi-actions">',
    '    <button class="smsi-btn" data-act="cancel" type="button">取消</button>',
    '    <button class="smsi-btn smsi-primary" data-act="save" type="button">保存图片</button>',
    '  </div>',
    '</div>'
  ].join('');

  overlay.addEventListener('click', onOverlayClick);
  document.body.appendChild(overlay);

  view.overlay = overlay;
  view.titleEl = overlay.querySelector('.smsi-title');
  view.pagesHost = overlay.querySelector('.smsi-pages');
  view.zoomLabel = overlay.querySelector('.smsi-zoom');
  view.key15Btn = overlay.querySelector('[data-act="key15"]');
  view.key9Btn = overlay.querySelector('[data-act="key9"]');
  view.selectAllSwitch = overlay.querySelector('.smsi-switch[data-act="select-all"]');
  view.selectCountEl = overlay.querySelector('[data-role="select-count"]');
  view.mergeSwitch = overlay.querySelector('.smsi-switch[data-act="merge-long"]');
  return overlay;
}

function onOverlayClick(ev) {
  const target = ev.target;
  if (!target || !target.getAttribute) return;

  const pick = target.closest ? target.closest('.smsi-pick') : null;
  if (pick) {
    const wrap = pick.closest('.smsi-page');
    if (wrap && wrap.dataset.pageIndex !== undefined) {
      togglePageSelection(parseInt(wrap.dataset.pageIndex, 10));
    }
    return;
  }

  const act = target.getAttribute('data-act');
  if (!act) return;

  switch (act) {
    case 'close':
    case 'cancel':
      closeOverlay();
      break;
    case 'zoom-in':
      setZoom(view.zoom + 0.15);
      break;
    case 'zoom-out':
      setZoom(view.zoom - 0.15);
      break;
    case 'key15':
      switchKeyCount(15);
      break;
    case 'key9':
      switchKeyCount(9);
      break;
    case 'select-all':
      toggleSelectAll();
      break;
    case 'merge-long':
      toggleMergeLong();
      break;
    case 'save':
      saveImages();
      break;
    default:
      break;
  }
}

/* ========================== 开关 / 缩放 ========================== */

function openOverlay() {
  const overlay = ensureOverlay();
  overlay.style.display = 'flex';
  requestAnimationFrame(function () {
    overlay.classList.add('smsi-show');
  });
  setNativeTopBarVisible(false);
}

function closeOverlay() {
  if (!view.overlay) return;
  const overlay = view.overlay;
  overlay.classList.remove('smsi-show');
  setTimeout(function () {
    overlay.style.display = 'none';
    if (view.pagesHost) view.pagesHost.innerHTML = '';
    view.pages = [];
    view.selected = [];
  }, 240);
  setNativeTopBarVisible(true);
}

function setZoom(z) {
  view.zoom = Math.max(0.4, Math.min(2.5, Number(z) || 1));
  if (view.zoomLabel) view.zoomLabel.textContent = Math.round(view.zoom * 100) + '%';
  for (let i = 0; i < view.pages.length; i++) {
    const page = view.pages[i];
    if (!page._wrap) continue;
    const m = measurePage(view.rows, view.style, view.infoItems.length);
    page._wrap.style.width = Math.round(m.width * view.zoom) + 'px';
  }
}

/* ========================== 勾选 ========================== */

function updateSelectUI() {
  const total = view.pages.length;
  const count = view.selected.filter(Boolean).length;

  if (view.selectCountEl) {
    view.selectCountEl.textContent = count + ' / ' + total;
  }

  if (view.selectAllSwitch) {
    view.selectAllSwitch.classList.toggle('smsi-on', total > 0 && count === total);
  }

  for (let i = 0; i < view.pages.length; i++) {
    const page = view.pages[i];
    if (!page._wrap) continue;
    page._wrap.classList.toggle('smsi-unselected', !view.selected[i]);
  }
}

function togglePageSelection(index) {
  if (index < 0 || index >= view.selected.length) return;
  view.selected[index] = !view.selected[index];
  updateSelectUI();
}

function toggleSelectAll() {
  const total = view.pages.length;
  const count = view.selected.filter(Boolean).length;
  const next = !(total > 0 && count === total);
  view.selected = new Array(total).fill(next);
  updateSelectUI();
}

function toggleMergeLong() {
  view.mergeLong = !view.mergeLong;
  if (view.mergeSwitch) {
    view.mergeSwitch.classList.toggle('smsi-on', view.mergeLong);
  }
}

/* ========================== 渲染 ========================== */

function renderPages() {
  const host = view.pagesHost;
  if (!host) return;
  host.innerHTML = '';

  const meta = {
    rows: view.rows,
    title: view.title,
    infoItems: view.infoItems
  };

  view.pages.forEach(function (page, index) {
    const wrap = document.createElement('div');
    wrap.className = 'smsi-page';
    wrap.dataset.pageIndex = String(index);

    const m = measurePage(view.rows, view.style, meta.infoItems.length);
    wrap.style.width = Math.round(m.width * view.zoom) + 'px';

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

    host.appendChild(wrap);

    page._wrap = wrap;
    page._canvas = canvas;
  });

  updateSelectUI();

  let index = 0;
  const total = view.pages.length;
  const step = function () {
    const page = view.pages[index];
    if (!page) return;
    try {
      const scale = previewScaleFor(view.rows, view.style, meta.infoItems.length);
      drawPageToCanvas(page._canvas, page, index, total, meta, view.style, scale);
    } catch (err) {
      console.error('[sheet-image] 渲染第 ' + (index + 1) + ' 页失败', err);
    }
    index += 1;
    if (index < total) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}

function rebuild() {
  const notes = collectNotes(view.raw, view.rows);
  if (!notes.length) return false;

  view.layout = buildLayout(notes, view.rows, view.tol);
  view.pages = buildPages(view.layout, view.style);
  view.info = computeInfo(view.layout, view.rows, view.tol);
  view.infoItems = buildInfoItems(
    view.info, view.rows, view.bpm, view.model, view.author, view.transcriber
  );
  view.selected = new Array(view.pages.length).fill(true);

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
    toast('当前谱面没有可绘制到 ' + rows + ' 键范围内的音符', 'error');
    view.rows = rows === 15 ? 9 : 15;
    rebuild();
  }
  renderPages();
}

/* ========================== 保存 ========================== */

function buildPayload() {
  const selectedPages = [];
  for (let i = 0; i < view.pages.length; i++) {
    if (!view.selected[i]) continue;
    const page = view.pages[i];
    selectedPages.push({
      index: i,
      columns: page.columns.map(function (col) {
        return { t: Math.round(col.t || 0), rows: col.rows.slice() };
      })
    });
  }

  return {
    version: 6,
    songName: view.title,
    rows: view.rows,
    style: view.style,
    mergeLong: view.mergeLong,
    totalPages: view.pages.length,
    infoItems: view.infoItems,
    pages: selectedPages
  };
}

function saveImages() {
  if (view.busy) return;

  const count = view.selected.filter(Boolean).length;
  if (count === 0) {
    toast('请至少选择一页', 'error');
    return;
  }

  const bridge = getBridge();
  const payload = buildPayload();

  if (bridge && typeof bridge.saveSheetImage === 'function') {
    view.busy = true;
    toast('正在生成图片…', 'info');

    setTimeout(function () {
      let raw = '';
      try {
        raw = bridge.saveSheetImage(JSON.stringify(payload));
      } catch (err) {
        view.busy = false;
        toast('保存失败：' + (err && err.message ? err.message : err), 'error');
        return;
      }
      view.busy = false;

      let res = null;
      try {
        res = typeof raw === 'string' ? JSON.parse(raw) : raw;
      } catch (err) {
        toast('保存失败：返回数据异常', 'error');
        return;
      }

      if (res && res.ok) {
        const files = (res.files && res.files.length) || 1;
        toast('已保存 ' + files + ' 张图片到 ' + (res.dir || 'SkyMusic-OneStar'), 'success');
        closeOverlay();
      } else {
        toast('保存失败：' + ((res && res.error) || '未知错误'), 'error');
      }
    }, 60);
    return;
  }

  exportZipInBrowser();
}

async function exportZipInBrowser() {
  view.busy = true;
  toast('正在打包 zip…', 'info');

  try {
    const fileNameBase = sanitizeFileName(view.title);
    const zip = createZipWriter();
    const selectedCanvases = [];

    for (let i = 0; i < view.pages.length; i++) {
      if (!view.selected[i]) continue;
      const page = view.pages[i];
      if (!page || !page._canvas) continue;

      const name = i === 0 ? fileNameBase + '.png' : fileNameBase + '-' + (i + 1) + '.png';
      const bytes = await canvasToBytes(page._canvas);
      zip.addFile(name, bytes);
      selectedCanvases.push(page._canvas);
    }

    if (view.mergeLong && selectedCanvases.length > 0) {
      const gapPx = Math.round(view.style.pad * 0.6);
      const longCanvas = mergeCanvasesToLong(
        selectedCanvases, gapPx, view.style.bg, MAX_LONG_PIXELS
      );
      if (longCanvas) {
        const bytes = await canvasToBytes(longCanvas);
        zip.addFile(fileNameBase + '-Full.png', bytes);
      }
    }

    const blob = zip.finish();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileNameBase + '.zip';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(function () { URL.revokeObjectURL(url); }, 6000);

    view.busy = false;
    toast('已打包 ' + (selectedCanvases.length + (view.mergeLong ? 1 : 0)) + ' 张图片', 'success');
    closeOverlay();
  } catch (err) {
    view.busy = false;
    toast('打包失败：' + (err && err.message ? err.message : err), 'error');
  }
}

/* ========================== 对外入口 ========================== */

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
  view.zoom = 1;
  view.mergeLong = false;

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

  if (view.mergeSwitch) view.mergeSwitch.classList.remove('smsi-on');

  const ok = rebuild();
  if (!ok) {
    toast('没有可绘制的音符，请先完成转谱', 'error');
    if (view.titleEl) view.titleEl.textContent = view.title;
    if (view.pagesHost) view.pagesHost.innerHTML = '';
    return;
  }

  if (view.titleEl) view.titleEl.textContent = view.title;
  setZoom(1);
  renderPages();
}