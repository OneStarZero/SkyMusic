/**
 * SkyMusic · 谱面图片 Canvas 绘制
 * 与原生 SheetImageExporter.kt 的绘制逻辑严格对齐
 */

import {
  FONT_STACK,
  WATERMARK_TEXT,
  APP_NAME,
  rgba,
  mixHex,
  formatMs
} from './style.js';

import { gridColsFor, gridRowsFor, measurePage } from './layout.js';

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
  const rnd = () => {
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
      ctx.fillText(WATERMARK_TEXT, x, y);
    }
    rowIndex += 1;
  }

  ctx.restore();
}

function drawKeyGrid(ctx, x, y, rows, st, activeCells) {
  const gCols = gridColsFor(rows);
  const gRows = gridRowsFor(rows);
  const cell = st.cell;
  const gap = st.cellGap;

  for (let r = 0; r < gRows; r++) {
    for (let c = 0; c < gCols; c++) {
      const bx = x + c * (cell + gap);
      const by = y + r * (cell + gap);
      const cellIndex = r * gCols + c;
      const isActive = activeCells && activeCells.indexOf(cellIndex) >= 0;

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

/**
 * 绘制一页到 canvas
 * @param {HTMLCanvasElement} canvas
 * @param {{columns:Array}} page
 * @param {number} pageIndex 0 基
 * @param {number} pageCount
 * @param {{rows:number,title:string,infoItems:Array}} meta
 * @param {object} st 样式
 * @param {number} scale 像素密度
 */
export function drawPageToCanvas(canvas, page, pageIndex, pageCount, meta, st, scale) {
  const rows = meta.rows;
  const infoItems = meta.infoItems || [];
  const m = measurePage(rows, st, infoItems.length);
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

  // —— 标题 ——
  ctx.textAlign = 'center';
  ctx.font = '700 ' + st.titleSize + 'px ' + FONT_STACK;
  ctx.fillStyle = st.titleColor;
  ctx.fillText(meta.title, m.width / 2, st.pad);

  // —— 页码 ——
  ctx.textAlign = 'left';
  ctx.font = '400 ' + st.metaSize + 'px ' + FONT_STACK;
  ctx.fillStyle = st.metaColor;
  ctx.fillText((pageIndex + 1) + ' / ' + pageCount, st.pad, st.pad + st.titleSize * 1.4 + 10);

  // —— 信息面板 ——
  const infoTop = st.pad + m.headerH;
  if (st.showInfoPanel && infoItems.length) {
    drawInfoPanel(ctx, st.pad, infoTop, m.bodyW, infoItems, st);
  }

  // —— 正文 ——
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

/** 计算预览用的像素密度（带像素上限保护） */
export function previewScaleFor(rows, st, infoItemCount) {
  const m = measurePage(rows, st, infoItemCount);
  let s = Math.min(window.devicePixelRatio || 1, 2);
  const px = m.width * m.height * s * s;
  if (px > 24000000) s = Math.sqrt(24000000 / (m.width * m.height));
  return Math.max(0.35, s);
}