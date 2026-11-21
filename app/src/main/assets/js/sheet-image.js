/**
 * SkyMusic 0.0.9 · 谱面图片生成模块
 * ------------------------------------------------------------------
 * 把 MIDI 转谱结果绘制成「Sky Studio 风格方块谱」：
 *   · 每个时间点 = 一个独立小键盘网格（15 键 5×3 / 9 键 3×3，横向）
 *   · 横向 5 个、纵向 6 个，每页 30 个
 *   · 每个小网格下方标注该时间点的时间戳
 *   · 晨岛治愈风配色 + 平铺水印 + logo + 软件名
 *   · 支持逐页勾选保存、可选输出完整长图
 *   · 浏览器端自动打包为 zip 下载
 * ------------------------------------------------------------------
 * 与原生 SheetImageExporter.kt 共用同一份 style 参数，保证所见即所得。
 */

/* ========================== 样式常量 ========================== */

const STYLE_DEFAULTS = {
  // —— 颜色（晨岛治愈风） ——
  bg: '#0B1120',
  bgGlow: '#1E1B4B',
  gridLine: '#7DD3FC',
  gridLineAlpha: 0.22,
  noteFill: '#7DD3FC',
  noteFillAlt: '#A78BFA',
  noteStroke: '#FFFFFF',
  noteStrokeAlpha: 0.40,
  titleColor: '#F8FAFC',
  metaColor: '#94A3B8',
  timeColor: '#CBD5E1',
  timeAlpha: 0.72,
  watermarkColor: '#7DD3FC',
  watermarkAlpha: 0.055,
  logoColor: '#7DD3FC',
  infoLabelColor: '#94A3B8',
  infoValueColor: '#E2E8F0',
  dividerColor: '#7DD3FC',
  dividerAlpha: 0.14,

  // —— 几何 ——
  cell: 22,
  cellGap: 4,
  gridRadius: 5,
  cols: 5,
  rowsPerPage: 6,
  gridGapX: 26,
  gridGapY: 30,
  pad: 40,

  // —— 字号 ——
  titleSize: 30,
  metaSize: 14,
  timeSize: 13,
  watermarkSize: 13,
  logoSize: 15,
  infoSize: 12,

  // —— 开关 ——
  showStars: true,
  showWatermark: true,
  showInfoPanel: true
};

const FONT_STACK = '"PingFang SC","Noto Sans SC","Microsoft YaHei",system-ui,sans-serif';
const WATERMARK_TEXT = 'Bilibili@_OneStar';
const APP_NAME = 'SkyMusic';

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
  mergeLong: false,
  zoom: 1,
  overlay: null,
  pagesHost: null,
  zoomLabel: null,
  busy: false,
  info: null
};

/* ========================== 工具函数 ========================== */

function clampInt(v, min, max, def) {
  const n = parseInt(v, 10);
  if (!Number.isFinite(n)) return def;
  return Math.max(min, Math.min(max, n));
}

function hexToRgb(hex) {
  let h = String(hex == null ? '' : hex).trim();
  if (h.charAt(0) === '#') h = h.slice(1);
  if (h.length === 3) h = h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
  if (h.length !== 6 || /[^0-9a-fA-F]/.test(h)) return [255, 255, 255];
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
}

function rgba(hex, alpha) {
  const rgb = hexToRgb(hex);
  const a = Math.max(0, Math.min(1, Number(alpha)));
  return 'rgba(' + rgb[0] + ',' + rgb[1] + ',' + rgb[2] + ',' + a + ')';
}

function mixHex(from, to, t) {
  const A = hexToRgb(from);
  const B = hexToRgb(to);
  const k = Math.max(0, Math.min(1, Number(t)));
  const r = Math.round(A[0] + (B[0] - A[0]) * k);
  const g = Math.round(A[1] + (B[1] - A[1]) * k);
  const b = Math.round(A[2] + (B[2] - A[2]) * k);
  return 'rgb(' + r + ',' + g + ',' + b + ')';
}

function formatMs(ms) {
  const t = Math.max(0, Math.round(Number(ms) || 0));
  const m = Math.floor(t / 60000);
  const s = Math.floor((t % 60000) / 1000);
  return m + ':' + (s < 10 ? '0' + s : s);
}

function formatDuration(ms) {
  const t = Math.max(0, Math.round(Number(ms) || 0));
  const m = Math.floor(t / 60000);
  const s = Math.floor((t % 60000) / 1000);
  const cs = Math.floor((t % 1000) / 10);
  return m + ':' + (s < 10 ? '0' + s : s) + '.' + (cs < 10 ? '0' + cs : cs);
}

function gridColsFor(rows) {
  return rows === 9 ? 3 : 5;
}

function gridRowsFor(rows) {
  return 3;
}

function todayStamp() {
  const d = new Date();
  const p = n => (n < 10 ? '0' + n : String(n));
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}

/* ========================== Toast 兜底 ========================== */

let toast = function (message, type) {
  let box = document.getElementById('smsi-fallback-toast');
  if (!box) {
    box = document.createElement('div');
    box.id = 'smsi-fallback-toast';
    box.style.cssText = 'position:fixed;left:50%;bottom:92px;transform:translateX(-50%);z-index:10001;' +
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

import('./ui.js')
  .then(function (m) {
    if (m && typeof m.showToast === 'function') toast = m.showToast;
  })
  .catch(function () { /* 使用内置兜底 */ });

/* ========================== 布局计算 ========================== */

function keyToRow(key, rows) {
  if (typeof key === 'number' && Number.isFinite(key)) {
    const idx = Math.round(key);
    return idx >= 0 && idx < rows ? idx : -1;
  }
  if (key === null || key === undefined) return -1;
  const m = /^1Key(-?\d+)$/.exec(String(key).trim());
  if (!m) return -1;
  const idx = parseInt(m[1], 10);
  if (!Number.isFinite(idx) || idx < 0 || idx >= rows) return -1;
  return idx;
}

function collectNotes(data, rows) {
  if (Array.isArray(data)) data = data[0] || {};
  const src = (data && (data.songNotes || data.notes)) || [];
  const out = [];
  for (let i = 0; i < src.length; i++) {
    const n = src[i];
    if (!n) continue;
    const row = keyToRow(n.key, rows);
    if (row < 0) continue;
    const t = Number(n.time);
    out.push({ t: Number.isFinite(t) ? t : 0, r: row });
  }
  return out;
}

function buildLayout(notes, rows, toleranceMs) {
  const tol = Number.isFinite(toleranceMs) && toleranceMs >= 0 ? toleranceMs : 25;
  const sorted = notes.slice().sort(function (a, b) {
    return (a.t - b.t) || (a.r - b.r);
  });

  const columns = [];
  let cur = null;

  for (let i = 0; i < sorted.length; i++) {
    const n = sorted[i];
    if (cur && n.t - cur.t <= tol) {
      if (cur.rows.indexOf(n.r) < 0) cur.rows.push(n.r);
    } else {
      cur = { t: n.t, rows: [n.r] };
      columns.push(cur);
    }
  }

  for (let i = 0; i < columns.length; i++) {
    columns[i].rows.sort(function (a, b) { return a - b; });
  }

  return { rows: rows, columns: columns };
}

function buildPages(layout, st) {
  const cols = layout.columns;
  const perPage = Math.max(1, Math.round(st.cols) * Math.round(st.rowsPerPage));
  const pages = [];

  for (let start = 0; start < cols.length; start += perPage) {
    pages.push({ columns: cols.slice(start, start + perPage) });
  }

  if (pages.length === 0) pages.push({ columns: [] });
  return pages;
}

/**
 * 计算一页画布尺寸（CSS 像素）
 * 关键修正：infoH 必须按「实际行数 × 行高」计算，否则信息会压到正文上
 */
function measurePage(page, rows, st, infoItemCount) {
  const gCols = gridColsFor(rows);
  const gRows = gridRowsFor(rows);
  const gridW = gCols * st.cell + (gCols - 1) * st.cellGap;
  const gridH = gRows * st.cell + (gRows - 1) * st.cellGap;
  const timeH = st.timeSize * 2.2;

  // 标题区：标题 + 页码
  const headerH = st.titleSize * 1.4 + 10 + st.metaSize * 1.5 + 26;

  // 信息面板：两列布局，行数 = ceil(条目数 / 2)
  let infoH = 0;
  if (st.showInfoPanel && infoItemCount > 0) {
    const infoRows = Math.ceil(infoItemCount / 2);
    const infoRowH = st.infoSize * 2.0;
    infoH = infoRows * infoRowH + 18; // 18 = 上下留白 + 分割线
  }

  const footerH = st.watermarkSize * 2.6;

  const bodyW = st.cols * gridW + (st.cols - 1) * st.gridGapX;
  const bodyH = st.rowsPerPage * (gridH + timeH) + (st.rowsPerPage - 1) * st.gridGapY;

  return {
    headerH: headerH,
    infoH: infoH,
    footerH: footerH,
    bodyW: bodyW,
    bodyH: bodyH,
    gridW: gridW,
    gridH: gridH,
    timeH: timeH,
    gCols: gCols,
    gRows: gRows,
    width: st.pad * 2 + bodyW,
    height: st.pad * 2 + headerH + infoH + bodyH + footerH
  };
}

/* ========================== 谱面信息 ========================== */

function computeInfo(layout, rows, bpm, tol) {
  const cols = layout.columns;
  let noteCount = 0;
  let chordCount = 0;
  let maxChord = 0;
  const keyUsage = new Array(rows).fill(0);

  for (let i = 0; i < cols.length; i++) {
    const c = cols[i];
    noteCount += c.rows.length;
    if (c.rows.length > 1) chordCount += 1;
    if (c.rows.length > maxChord) maxChord = c.rows.length;
    for (let k = 0; k < c.rows.length; k++) {
      const r = c.rows[k];
      if (r >= 0 && r < rows) keyUsage[r] += 1;
    }
  }

  const firstMs = cols.length ? cols[0].t : 0;
  const lastMs = cols.length ? cols[cols.length - 1].t : 0;
  const durationMs = Math.max(0, lastMs - firstMs);
  const density = durationMs > 0 ? (cols.length / (durationMs / 1000)) : 0;

  let topKey = -1;
  let topCount = 0;
  for (let r = 0; r < rows; r++) {
    if (keyUsage[r] > topCount) { topCount = keyUsage[r]; topKey = r; }
  }

  return {
    noteCount: noteCount,
    columnCount: cols.length,
    durationMs: durationMs,
    firstMs: firstMs,
    lastMs: lastMs,
    chordCount: chordCount,
    maxChord: maxChord,
    keyUsage: keyUsage,
    density: density,
    topKey: topKey,
    topCount: topCount,
    tol: tol
  };
}

function buildInfoItems(info, rows, bpm, model, author, transcriber) {
  const items = [];

  items.push({ label: '键位', value: rows + ' 键' });
  items.push({ label: 'BPM', value: bpm ? String(Math.round(bpm)) : '—' });
  items.push({ label: '音符总数', value: String(info.noteCount) });
  items.push({ label: '时间点', value: String(info.columnCount) });
  items.push({ label: '和弦数', value: String(info.chordCount) });
  items.push({ label: '最大和弦', value: info.maxChord + ' 音' });
  items.push({ label: '时长', value: formatDuration(info.durationMs) });
  items.push({ label: '起始', value: formatMs(info.firstMs) });
  items.push({ label: '结束', value: formatMs(info.lastMs) });
  items.push({ label: '密度', value: info.density.toFixed(2) + ' 列/秒' });
  items.push({ label: '合并窗口', value: info.tol + ' ms' });
  items.push({ label: '模型', value: model || '—' });
  items.push({ label: '作者', value: author || '—' });
  items.push({ label: '制谱', value: transcriber || '—' });
  items.push({ label: '生成日期', value: todayStamp() });

  if (info.topKey >= 0) {
    items.push({ label: '最常用键', value: '1Key' + info.topKey + '（' + info.topCount + ' 次）' });
  }

  return items;
}

/* ========================== 绘制 ========================== */

function roundRectPath(ctx, x, y, w, h, r) {
  const rr = Math.max(0, Math.min(r, w / 2, h / 2));
  ctx.beginPath();
  ctx.moveTo(x + rr, y);
  ctx.arcTo(x + w, y, x + w, y + h, rr);
  ctx.arcTo(x + w, y + h, x, y + h, rr);
  ctx.arcTo(x, y + h, x, y, rr);
  ctx.arcTo(x, y, x + w, y, rr);
  ctx.closePath();
}

function paintStars(ctx, width, height) {
  const area = width * Math.min(height, 900);
  const count = Math.max(12, Math.min(220, Math.round(area / 4200)));
  let seed = 20260101;
  const rnd = function () {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff;
    return seed / 0x7fffffff;
  };
  for (let i = 0; i < count; i++) {
    const x = rnd() * width;
    const y = rnd() * Math.min(height, 900);
    const r = rnd() * 1.1 + 0.35;
    const a = rnd() * 0.22 + 0.03;
    ctx.fillStyle = 'rgba(226,232,240,' + a.toFixed(3) + ')';
    ctx.beginPath();
    ctx.arc(x, y, r, 0, Math.PI * 2);
    ctx.fill();
  }
}

function paintWatermark(ctx, width, height, st) {
  if (!st.showWatermark) return;

  const text = WATERMARK_TEXT;
  const fontSize = st.watermarkSize;
  const stepX = fontSize * 11;
  const stepY = fontSize * 7;

  ctx.save();
  ctx.font = '500 ' + fontSize + 'px ' + FONT_STACK;
  ctx.fillStyle = rgba(st.watermarkColor, st.watermarkAlpha);
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';

  ctx.translate(width / 2, height / 2);
  ctx.rotate(-Math.PI / 4);

  const diag = Math.sqrt(width * width + height * height);
  const halfW = diag / 2 + stepX;
  const halfH = diag / 2 + stepY;

  let rowIndex = 0;
  for (let y = -halfH; y <= halfH; y += stepY) {
    const offset = (rowIndex % 2 === 0) ? 0 : stepX / 2;
    for (let x = -halfW + offset; x <= halfW; x += stepX) {
      ctx.fillText(text, x, y);
    }
    rowIndex += 1;
  }

  ctx.restore();
}

function drawKeyGrid(ctx, x, y, rows, st, activeRows) {
  const gCols = gridColsFor(rows);
  const gRows = gridRowsFor(rows);
  const cell = st.cell;
  const gap = st.cellGap;

  for (let r = 0; r < gRows; r++) {
    for (let c = 0; c < gCols; c++) {
      const bx = x + c * (cell + gap);
      const by = y + r * (cell + gap);
      const cellIndex = r * gCols + c;
      const isActive = activeRows && activeRows.indexOf(cellIndex) >= 0;

      roundRectPath(ctx, bx, by, cell, cell, st.gridRadius);
      if (isActive) {
        const t = gRows > 1 ? r / (gRows - 1) : 0;
        ctx.fillStyle = mixHex(st.noteFill, st.noteFillAlt, t);
        ctx.fill();
        ctx.strokeStyle = rgba(st.noteStroke, st.noteStrokeAlpha);
        ctx.lineWidth = 1;
        ctx.stroke();
      } else {
        ctx.strokeStyle = rgba(st.gridLine, st.gridLineAlpha);
        ctx.lineWidth = 1.2;
        ctx.stroke();
      }
    }
  }
}

function drawLogo(ctx, x, y, size, st) {
  const cx = x + size / 2;
  const cy = y + size / 2;
  const r = size / 2;

  ctx.strokeStyle = rgba(st.logoColor, 0.85);
  ctx.lineWidth = 1.6;
  ctx.beginPath();
  ctx.arc(cx, cy, r - 1, 0, Math.PI * 2);
  ctx.stroke();

  ctx.fillStyle = st.logoColor;
  ctx.beginPath();
  const spikes = 4;
  const outer = r * 0.62;
  const inner = r * 0.24;
  for (let i = 0; i < spikes * 2; i++) {
    const rad = (Math.PI / spikes) * i - Math.PI / 2;
    const dist = i % 2 === 0 ? outer : inner;
    const px = cx + Math.cos(rad) * dist;
    const py = cy + Math.sin(rad) * dist;
    if (i === 0) ctx.moveTo(px, py);
    else ctx.lineTo(px, py);
  }
  ctx.closePath();
  ctx.fill();
}

/** 信息面板：两列网格，绘制在标题正下方 */
function drawInfoPanel(ctx, x, y, width, items, st) {
  if (!st.showInfoPanel || !items || !items.length) return;

  const colCount = 2;
  const rowCount = Math.ceil(items.length / colCount);
  const colW = width / colCount;
  const rowH = st.infoSize * 2.0;

  ctx.textBaseline = 'middle';

  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    const col = i % colCount;
    const row = Math.floor(i / colCount);
    const ix = x + col * colW;
    const iy = y + row * rowH + rowH / 2;

    ctx.textAlign = 'left';
    ctx.font = '400 ' + st.infoSize + 'px ' + FONT_STACK;
    ctx.fillStyle = rgba(st.infoLabelColor, 0.85);
    ctx.fillText(item.label, ix, iy);

    const labelW = ctx.measureText(item.label).width;

    ctx.font = '600 ' + st.infoSize + 'px ' + FONT_STACK;
    ctx.fillStyle = st.infoValueColor;
    ctx.fillText(item.value, ix + labelW + 8, iy);
  }

  const lineY = y + rowCount * rowH + 6;
  ctx.strokeStyle = rgba(st.dividerColor, st.dividerAlpha);
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(x, lineY);
  ctx.lineTo(x + width, lineY);
  ctx.stroke();

  ctx.textBaseline = 'top';
}

function drawPageToCanvas(canvas, page, pageIndex, pageCount, meta, st, scale) {
  const rows = meta.rows;
  const infoItems = meta.infoItems || [];
  const m = measurePage(page, rows, st, infoItems.length);
  const px = Math.max(0.35, Number(scale) || 1);

  canvas.width = Math.max(1, Math.round(m.width * px));
  canvas.height = Math.max(1, Math.round(m.height * px));

  const ctx = canvas.getContext('2d');
  ctx.setTransform(px, 0, 0, px, 0, 0);
  ctx.clearRect(0, 0, m.width, m.height);
  ctx.textBaseline = 'top';

  // —— 背景 ——
  const bgGrad = ctx.createLinearGradient(0, 0, 0, m.height);
  bgGrad.addColorStop(0, st.bg);
  bgGrad.addColorStop(1, st.bgGlow);
  ctx.fillStyle = bgGrad;
  ctx.fillRect(0, 0, m.width, m.height);

  if (st.showStars) paintStars(ctx, m.width, m.height);
  paintWatermark(ctx, m.width, m.height, st);

  // —— 标题（居中） ——
  ctx.textAlign = 'center';
  ctx.font = '700 ' + st.titleSize + 'px ' + FONT_STACK;
  ctx.fillStyle = st.titleColor;
  ctx.fillText(meta.title, m.width / 2, st.pad);

  // —— 页码（左对齐） ——
  ctx.textAlign = 'left';
  ctx.font = '400 ' + st.metaSize + 'px ' + FONT_STACK;
  ctx.fillStyle = st.metaColor;
  ctx.fillText((pageIndex + 1) + ' / ' + pageCount, st.pad, st.pad + st.titleSize * 1.4 + 10);

  // —— 信息面板（标题正下方，独立占位，不覆盖正文） ——
  const infoTop = st.pad + m.headerH;
  if (st.showInfoPanel && infoItems.length) {
    drawInfoPanel(ctx, st.pad, infoTop, m.bodyW, infoItems, st);
  }

  // —— 正文（严格从信息面板下方开始） ——
  const bodyTop = infoTop + m.infoH;
  const bodyLeft = st.pad;

  for (let i = 0; i < page.columns.length; i++) {
    const col = page.columns[i];
    const gx = bodyLeft + (i % st.cols) * (m.gridW + st.gridGapX);
    const gy = bodyTop + Math.floor(i / st.cols) * (m.gridH + m.timeH + st.gridGapY);

    const activeCells = [];
    for (let k = 0; k < col.rows.length; k++) {
      const r = col.rows[k];
      if (r < 0 || r >= rows) continue;
      const gridRow = Math.floor(r / m.gCols);
      const gridCol = r % m.gCols;
      if (gridRow < m.gRows) activeCells.push(gridRow * m.gCols + gridCol);
    }

    drawKeyGrid(ctx, gx, gy, rows, st, activeCells);

    ctx.textAlign = 'center';
    ctx.font = '400 ' + st.timeSize + 'px ' + FONT_STACK;
    ctx.fillStyle = rgba(st.timeColor, st.timeAlpha);
    ctx.fillText(formatMs(col.t), gx + m.gridW / 2, gy + m.gridH + 8);
  }

  // —— 页脚 ——
  const footY = st.pad + m.headerH + m.infoH + m.bodyH + m.footerH / 2;
  const logoSize = st.logoSize * 1.6;
  const nameW = 90;
  const totalW = logoSize + 8 + nameW;
  const startX = (m.width - totalW) / 2;

  drawLogo(ctx, startX, footY - logoSize / 2, logoSize, st);

  ctx.textAlign = 'left';
  ctx.textBaseline = 'middle';
  ctx.font = '600 ' + st.logoSize + 'px ' + FONT_STACK;
  ctx.fillStyle = st.logoColor;
  ctx.fillText(APP_NAME, startX + logoSize + 8, footY);

  ctx.textAlign = 'left';
  ctx.textBaseline = 'top';
}
/* ========================== 预览层 ========================== */

const OVERLAY_CSS = [
  '.smsi-overlay{position:fixed;inset:0;z-index:9990;display:flex;flex-direction:column;',
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

  overlay.addEventListener('click', function (ev) {
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
  });

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

function openOverlay() {
  const overlay = ensureOverlay();
  overlay.style.display = 'flex';
  requestAnimationFrame(function () {
    overlay.classList.add('smsi-show');
  });
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
}

function setZoom(z) {
  view.zoom = Math.max(0.4, Math.min(2.5, Number(z) || 1));
  if (view.zoomLabel) view.zoomLabel.textContent = Math.round(view.zoom * 100) + '%';
  for (let i = 0; i < view.pages.length; i++) {
    const page = view.pages[i];
    if (!page._wrap) continue;
    const m = measurePage(page, view.rows, view.style, view.infoItems ? view.infoItems.length : 0);
    page._wrap.style.width = Math.round(m.width * view.zoom) + 'px';
  }
}

/* ========================== 勾选逻辑 ========================== */

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

function previewScaleFor(page) {
  const m = measurePage(page, view.rows, view.style, view.infoItems ? view.infoItems.length : 0);
  let s = Math.min(window.devicePixelRatio || 1, 2);
  const cap = 24000000;
  const px = m.width * m.height * s * s;
  if (px > cap) s = Math.sqrt(cap / (m.width * m.height));
  return Math.max(0.35, s);
}

function renderPages() {
  const host = view.pagesHost;
  if (!host) return;
  host.innerHTML = '';

  const meta = {
    rows: view.rows,
    title: view.title,
    infoItems: view.infoItems || []
  };

  view.pages.forEach(function (page, index) {
    const wrap = document.createElement('div');
    wrap.className = 'smsi-page';
    wrap.dataset.pageIndex = String(index);

    const m = measurePage(page, view.rows, view.style, meta.infoItems.length);
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
      drawPageToCanvas(page._canvas, page, index, total, meta, view.style, previewScaleFor(page));
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
  view.info = computeInfo(view.layout, view.rows, view.bpm, view.tol);
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
    version: 5,
    songName: view.title,
    rows: view.rows,
    style: view.style,
    mergeLong: view.mergeLong,
    totalPages: view.pages.length,
    infoItems: view.infoItems || [],
    pages: selectedPages
  };
}

function getBridge() {
  return window.AndroidBridge || window.Android || window.SkyMusicBridge || null;
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

  // 浏览器端：打包 zip
  exportZipInBrowser();
}

/* ========================== 浏览器端 ZIP 导出 ========================== */

/** 无压缩 ZIP 写入器（store 模式，PNG 本身已压缩，无需再 deflate） */
function createZipWriter() {
  const encoder = new TextEncoder();
  const chunks = [];
  const central = [];
  let offset = 0;

  // CRC32 查表
  const crcTable = (function () {
    const table = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) {
        c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
      }
      table[n] = c >>> 0;
    }
    return table;
  })();

  function crc32(bytes) {
    let c = 0xFFFFFFFF;
    for (let i = 0; i < bytes.length; i++) {
      c = crcTable[(c ^ bytes[i]) & 0xFF] ^ (c >>> 8);
    }
    return (c ^ 0xFFFFFFFF) >>> 0;
  }

  function dosTime(d) {
    const time = ((d.getHours() & 0x1F) << 11) |
                 ((d.getMinutes() & 0x3F) << 5) |
                 ((Math.floor(d.getSeconds() / 2)) & 0x1F);
    const date = (((d.getFullYear() - 1980) & 0x7F) << 9) |
                 (((d.getMonth() + 1) & 0x0F) << 5) |
                 (d.getDate() & 0x1F);
    return { time: time, date: date };
  }

  function addFile(name, bytes) {
    const nameBytes = encoder.encode(name);
    const crc = crc32(bytes);
    const now = dosTime(new Date());

    // 本地文件头
    const local = new Uint8Array(30 + nameBytes.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true);
    lv.setUint16(6, 0x0800, true); // UTF-8 文件名
    lv.setUint16(8, 0, true);      // store
    lv.setUint16(10, now.time, true);
    lv.setUint16(12, now.date, true);
    lv.setUint32(14, crc, true);
    lv.setUint32(18, bytes.length, true);
    lv.setUint32(22, bytes.length, true);
    lv.setUint16(26, nameBytes.length, true);
    lv.setUint16(28, 0, true);
    local.set(nameBytes, 30);

    chunks.push(local, bytes);

    // 中央目录记录
    const cd = new Uint8Array(46 + nameBytes.length);
    const cv = new DataView(cd.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 20, true);
    cv.setUint16(6, 20, true);
    cv.setUint16(8, 0x0800, true);
    cv.setUint16(10, 0, true);
    cv.setUint16(12, now.time, true);
    cv.setUint16(14, now.date, true);
    cv.setUint32(16, crc, true);
    cv.setUint32(20, bytes.length, true);
    cv.setUint32(24, bytes.length, true);
    cv.setUint16(28, nameBytes.length, true);
    cv.setUint16(30, 0, true);
    cv.setUint16(32, 0, true);
    cv.setUint16(34, 0, true);
    cv.setUint16(36, 0, true);
    cv.setUint32(38, 0, true);
    cv.setUint32(42, offset, true);
    cd.set(nameBytes, 46);

    central.push(cd);
    offset += local.length + bytes.length;
  }

  function finish() {
    const cdStart = offset;
    let cdSize = 0;
    for (let i = 0; i < central.length; i++) {
      chunks.push(central[i]);
      cdSize += central[i].length;
    }

    const end = new Uint8Array(22);
    const ev = new DataView(end.buffer);
    ev.setUint32(0, 0x06054b50, true);
    ev.setUint16(4, 0, true);
    ev.setUint16(6, 0, true);
    ev.setUint16(8, central.length, true);
    ev.setUint16(10, central.length, true);
    ev.setUint32(12, cdSize, true);
    ev.setUint32(16, cdStart, true);
    ev.setUint16(20, 0, true);
    chunks.push(end);

    return new Blob(chunks, { type: 'application/zip' });
  }

  return { addFile: addFile, finish: finish };
}

function canvasToBytes(canvas) {
  return new Promise(function (resolve, reject) {
    canvas.toBlob(function (blob) {
      if (!blob) { reject(new Error('canvas 转 PNG 失败')); return; }
      const reader = new FileReader();
      reader.onload = function () { resolve(new Uint8Array(reader.result)); };
      reader.onerror = function () { reject(new Error('读取 PNG 数据失败')); };
      reader.readAsArrayBuffer(blob);
    }, 'image/png');
  });
}

/** 把多张 canvas 上下拼接成一张长图 canvas */
function mergeCanvasesToLong(canvases, gapPx) {
  if (!canvases.length) return null;

  let maxW = 0;
  let totalH = 0;
  for (let i = 0; i < canvases.length; i++) {
    if (canvases[i].width > maxW) maxW = canvases[i].width;
    totalH += canvases[i].height;
  }
  totalH += gapPx * (canvases.length - 1);

  // 像素上限保护
  const MAX_LONG_PIXELS = 120000000;
  let scale = 1;
  if (maxW * totalH > MAX_LONG_PIXELS) {
    scale = Math.sqrt(MAX_LONG_PIXELS / (maxW * totalH));
  }

  const outW = Math.max(1, Math.round(maxW * scale));
  const outH = Math.max(1, Math.round(totalH * scale));

  const out = document.createElement('canvas');
  out.width = outW;
  out.height = outH;
  const ctx = out.getContext('2d');

  ctx.fillStyle = view.style.bg;
  ctx.fillRect(0, 0, outW, outH);

  let y = 0;
  for (let i = 0; i < canvases.length; i++) {
    const c = canvases[i];
    const dw = c.width * scale;
    const dh = c.height * scale;
    const dx = (outW - dw) / 2;
    ctx.drawImage(c, dx, y, dw, dh);
    y += dh + gapPx * scale;
  }

  return out;
}

async function exportZipInBrowser() {
  view.busy = true;
  toast('正在打包 zip…', 'info');

  try {
    const fileNameBase = (view.title || '未命名乐谱').replace(/[\\/:*?"<>|]/g, '_').trim() || '未命名乐谱';
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

    // 长图（名字带 Full）
    if (view.mergeLong && selectedCanvases.length > 0) {
      const gapPx = Math.round(view.style.pad * 0.6);
      const longCanvas = mergeCanvasesToLong(selectedCanvases, gapPx);
      if (longCanvas) {
        const longName = fileNameBase + '-Full.png';
        const bytes = await canvasToBytes(longCanvas);
        zip.addFile(longName, bytes);
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

function openSheetImagePreview(data, options) {
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

export { openSheetImagePreview, buildLayout as buildSheetLayout, buildPages as buildSheetPages };
