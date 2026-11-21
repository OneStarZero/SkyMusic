/**
 * SkyMusic · 分页渲染 + 长图渲染（懒加载）
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
  });

  host.appendChild(frag);

  observePages(meta);
}

function observePages(meta) {
  if (pageObserver) pageObserver.disconnect();

  pageObserver = new IntersectionObserver(function (entries) {
    for (let i = 0; i < entries.length; i++) {
      const entry = entries[i];
      if (!entry.isIntersecting) continue;
      const index = parseInt(entry.target.dataset.pageIndex, 10);
      if (!isNaN(index)) renderPageAt(index, meta);
    }
  }, {
    root: view.bodyPages,
    rootMargin: '200px 0px',
    threshold: 0
  });

  for (let i = 0; i < view.pages.length; i++) {
    const page = view.pages[i];
    if (page._wrap) pageObserver.observe(page._wrap);
  }
}

export function renderPageAt(index, meta) {
  const page = view.pages[index];
  if (!page || !page._canvas || page._rendered) return;
  try {
    const scale = previewScaleFor(view.rows, view.style, meta.infoItems.length);
    drawPageToCanvas(page._canvas, page, index, view.pages.length, meta, view.style, scale);
    page._rendered = true;
  } catch (err) {
    console.error('[sheet-image] 渲染第 ' + (index + 1) + ' 页失败', err);
  }
}

/* ========================== 长图渲染（懒加载） ========================== */

export function ensureLongPreview() {
  if (!view.longHost) return;
  if (!view.pages.length) return;

  const meta = {
    rows: view.rows,
    title: view.title,
    infoItems: view.infoItems
  };

  const gapPx = Math.round(view.style.pad * 0.6);
  let maxW = 0;
  for (let i = 0; i < view.pages.length; i++) {
    const page = view.pages[i];
    if (!page || !page._canvas) continue;
    if (page._canvas.width > maxW) maxW = page._canvas.width;
  }
  if (maxW === 0) return;

  const baseW = Math.min(maxW, 720);
  const scale = baseW / maxW;

  view.longHost.innerHTML = '';

  const container = document.createElement('div');
  container.style.cssText =
    'display:flex;flex-direction:column;align-items:center;' +
    'gap:' + Math.round(gapPx * scale * view.longZoom) + 'px;' +
    'margin:0 auto;width:' + Math.round(baseW * view.longZoom) + 'px;';

  for (let i = 0; i < view.pages.length; i++) {
    const page = view.pages[i];
    if (!page || !page._canvas) continue;

    const c = page._canvas;
    const w = Math.round(c.width * scale * view.longZoom);
    const h = Math.round(c.height * scale * view.longZoom);

    const slot = document.createElement('div');
    slot.className = 'smsi-long-slot';
    slot.dataset.pageIndex = String(i);
    slot.style.cssText =
      'width:' + w + 'px;height:' + h + 'px;' +
      'border-radius:12px;background:#0B1120;' +
      'box-shadow:0 10px 30px rgba(0,0,0,.5),0 0 0 1px rgba(125,211,252,.08);' +
      'overflow:hidden;';

    container.appendChild(slot);
  }

  view.longHost.appendChild(container);

  view.longCanvas = {
    _baseWidth: baseW,
    _scale: scale,
    _container: container,
    _slots: container.children
  };

  observeLongSlots(container, meta, scale);
}

function observeLongSlots(container, meta, scale) {
  if (longObserver) longObserver.disconnect();

  longObserver = new IntersectionObserver(function (entries) {
    for (let i = 0; i < entries.length; i++) {
      const entry = entries[i];
      const slot = entry.target;
      const index = parseInt(slot.dataset.pageIndex, 10);
      if (isNaN(index)) continue;

      if (entry.isIntersecting) {
        renderLongSlot(slot, index, meta, scale);
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

function renderLongSlot(slot, index, meta, scale) {
  if (slot._rendered) return;

  const page = view.pages[index];
  if (!page || !page._canvas) return;

  if (!page._rendered) {
    renderPageAt(index, meta);
  }

  const c = page._canvas;
  const w = Math.round(c.width * scale * view.longZoom);
  const h = Math.round(c.height * scale * view.longZoom);

  const canvas = document.createElement('canvas');
  canvas.className = 'smsi-long-canvas';
  canvas.width = c.width;
  canvas.height = c.height;
  canvas.style.cssText =
    'display:block;width:' + w + 'px;height:' + h + 'px;';

  const ctx = canvas.getContext('2d');
  ctx.drawImage(c, 0, 0);

  slot.innerHTML = '';
  slot.appendChild(canvas);
  slot._rendered = true;
}

function releaseLongSlot(slot) {
  if (!slot._rendered) return;
  slot.innerHTML = '';
  slot._rendered = false;
}
