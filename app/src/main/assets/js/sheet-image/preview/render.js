/**
 * SkyMusic · 分页渲染 + 长图渲染（LOD 懒加载）
 */

import { view } from './state.js';
import { measurePage } from '../layout.js';
import { drawPageToCanvas, previewScaleFor } from '../render.js';

/* ========================== 观察器 ========================== */

let pageObserver = null;
let longObserver = null;

export function disposeObservers() {
  if (pageObserver) { pageObserver.disconnect(); pageObserver = null; }
  if (longObserver) { longObserver.disconnect(); longObserver = null; }
}

/* ========================== LOD 常量 ========================== */

const LOD_HIGH = 1.0;
const LOD_MID = 0.5;
const LOD_LOW = 0.25;

/* ========================== 分页渲染 ========================== */

export function renderPages() {
  const host = view.pagesHost;
  if (!host) return;
  host.innerHTML = '';

  const meta = {
    rows: view.rows,
    title: view.title,
    infoItems: view.infoItems
  };

  const m = measurePage(view.rows, view.style, meta.infoItems.length);
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

export function renderPageAt(index, meta, lod) {
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

/* ========================== 长图渲染 ========================== */

const LONG_BASE_WIDTH = 720;

export function ensureLongPreview() {
  if (!view.longHost) return;
  if (!view.pages.length) return;

  const meta = {
    rows: view.rows,
    title: view.title,
    infoItems: view.infoItems
  };

  const m = measurePage(view.rows, view.style, meta.infoItems.length);
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

/**
 * 渲染单个长图槽位。
 * 关键：先确保分页 canvas 是 HIGH 分辨率，再直接复制像素。
 * 不调用 drawPageToCanvas，避免临时 canvas 尺寸计算错误。
 */
function renderLongSlot(slot, index, meta, slotW, slotH) {
  if (slot._rendered) return;

  const page = view.pages[index];
  if (!page || !page._canvas) return;

  // 1. 确保分页 canvas 是 HIGH 分辨率
  if (!page._rendered || page._lod !== LOD_HIGH) {
    renderPageAt(index, meta, LOD_HIGH);
  }

  const src = page._canvas;
  if (!src.width || src.width <= 1) return;

  // 2. 槽位 canvas：按 slotW / slotH 缩放绘制
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
