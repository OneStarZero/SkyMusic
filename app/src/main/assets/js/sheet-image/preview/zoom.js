/**
 * SkyMusic · 缩放逻辑
 */

import { view, DEFAULT_ZOOM, getActiveTab } from './state.js';
import { measurePage } from '../layout.js';

export function currentZoom() {
  return getActiveTab() === 'long' ? view.longZoom : view.pageZoom;
}

export function setZoom(z) {
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

export function updateZoomLabel() {
  if (view.zoomLabel) {
    view.zoomLabel.textContent = Math.round(currentZoom() * 100) + '%';
  }
}

export function applyPageZoom() {
  const m = measurePage(view.rows, view.style, view.infoItems.length);
  const w = Math.round(m.width * view.pageZoom) + 'px';
  for (let i = 0; i < view.pages.length; i++) {
    const page = view.pages[i];
    if (!page._wrap) continue;
    page._wrap.style.width = w;
  }
}

export function applyLongZoom() {
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

export function bindPinchZoom(el, kind) {
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
