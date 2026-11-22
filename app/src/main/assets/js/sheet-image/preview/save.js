/**
 * SkyMusic · 保存逻辑（App + 浏览器）
 */

import { view } from './state.js';
import { MAX_LONG_PIXELS, sanitizeFileName } from '../style.js';
import { getBridge } from './bridge.js';
import { showToast } from './toast.js';
import { showProgress, hideProgress } from './progress.js';
import { createZipWriter, canvasToBytes, buildLongPngBytes } from '../zip.js';
import { renderPageAt } from './render.js';

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

async function exportInBrowser(longOnly, onClose) {
  try {
    const fileNameBase = sanitizeFileName(view.title);

    const meta = {
      rows: view.rows,
      title: view.title,
      infoItems: view.infoItems
    };
    for (let i = 0; i < view.pages.length; i++) {
      renderPageAt(i, meta);
    }

    if (longOnly) {
      showProgress('正在准备长图…', 5);
      const canvases = [];
      for (let i = 0; i < view.pages.length; i++) {
        const page = view.pages[i];
        if (page && page._canvas && page._canvas.width > 1) {
          canvases.push(page._canvas);
        }
      }
      if (!canvases.length) throw new Error('没有可用的页面');

      const gapPx = Math.round(view.style.pad * 0.6);
      const parts = await buildLongPngBytes(
        canvases, gapPx, view.style.bg, MAX_LONG_PIXELS,
        function (p) { showProgress('正在生成长图 ' + p + '%', p); }
      );

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
      const page = view.pages[i];
      if (!page || !page._canvas) continue;

      showProgress('正在打包 ' + (k + 1) + ' / ' + total, Math.round((k / total) * 60));

      const name = i === 0 ? fileNameBase + '.png' : fileNameBase + '-' + (i + 1) + '.png';
      const bytes = await canvasToBytes(page._canvas);
      zip.addFile(name, bytes);
      selectedCanvases.push(page._canvas);

      await nextFrame();
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

function nextFrame() {
  return new Promise(function (resolve) {
    requestAnimationFrame(function () { resolve(); });
  });
}
