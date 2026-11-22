/**
 * SkyMusic · 保存逻辑（App + 浏览器）
 */

import { view } from './state.js';
import { MAX_LONG_PIXELS, sanitizeFileName } from '../style.js';
import { getBridge } from './bridge.js';
import { showToast } from './toast.js';
import { showProgress, hideProgress } from './progress.js';
import { createZipWriter, canvasToBytes, buildLongPngBytes } from '../zip.js';
import { drawPageToCanvas, previewScaleFor } from '../render.js';

/* ========================== 统一分辨率重绘 ========================== */

/**
 * 把指定页重绘到独立 canvas（统一 LOD_HIGH 分辨率）。
 * 不修改 page._canvas，避免污染分页预览状态。
 */
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

/* ========================== payload ========================== */

export function buildPayload(longOnly) {
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
    style: view.style,
    mergeLong: longOnly ? true : view.mergeLong,
    longOnly: Boolean(longOnly),
    totalPages: view.pages.length,
    infoItems: view.infoItems,
    pages: selectedPages
  };
}

/* ========================== 保存入口 ========================== */

export function saveImages(longOnly, onClose) {
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
        if (res.debug) {
          console.log('[SheetImage] ' + res.debug);
        }
        if (onClose) onClose();
      } else {
        showToast('保存失败：' + ((res && res.error) || '未知错误'), 'error');
      }
    }, 60);
    return;
  }

  exportInBrowser(longOnly, onClose);
}

/* ========================== 浏览器导出 ========================== */

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

      // 统一分辨率重绘所有页
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

      // 释放临时 canvas
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

    // 分页导出
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

      // 统一分辨率重绘
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

/* ========================== 工具 ========================== */

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

/** 让出主线程（比 requestAnimationFrame 更快） */
function nextTick() {
  return new Promise(function (resolve) {
    setTimeout(resolve, 0);
  });
}
