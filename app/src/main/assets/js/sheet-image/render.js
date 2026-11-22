import {
  FONT_STACK, WATERMARK_TEXT, APP_NAME, rgba, mixHex, formatMs, getThemeStyle
} from './style.js';
import { gridColsFor, gridRowsFor, measurePage } from './layout.js';

// === 绘图辅助函数 ===
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

function drawShape(ctx, x, y, w, h, r, shape) {
  if (shape === 'circle') {
    ctx.beginPath();
    ctx.arc(x + w/2, y + h/2, w/2, 0, Math.PI * 2);
  } else if (shape === 'hexagon') {
    const cx = x + w/2, cy = y + h/2;
    const rad = w/2;
    ctx.beginPath();
    for (let i = 0; i < 6; i++) {
      const ang = (Math.PI / 3) * i - Math.PI / 6;
      const px = cx + rad * Math.cos(ang);
      const py = cy + rad * Math.sin(ang);
      if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
    }
    ctx.closePath();
  } else if (shape === 'diamond') {
    const cx = x + w/2, cy = y + h/2;
    ctx.beginPath();
    ctx.moveTo(cx, cy - h/2);
    ctx.lineTo(cx + w/2, cy);
    ctx.lineTo(cx, cy + h/2);
    ctx.lineTo(cx - w/2, cy);
    ctx.closePath();
  } else {
    roundRectPath(ctx, x, y, w, h, r);
  }
}

// === 终极商用防伪水印 (高频振铃点阵，一压缩必现马赛克) ===
function paintStealthWatermark(ctx, width, height, pageIndex) {
  const key = "SkyMusic_Protect_AntiTheft";
  const data = [...key].map(c => c.charCodeAt(0));
  
  ctx.save();
  // 使用极细的线和高频网格，肉眼看不出，但压缩必炸
  ctx.globalAlpha = 0.08; 
  
  let dataIdx = 0;
  for (let i = 0; i < 800; i++) {
    // 避开中心排版区域，在边缘和背景铺设高频纹理
    const x = (data[dataIdx % data.length] * 19 + i * 17) % width;
    const y = (data[(dataIdx+1) % data.length] * 23 + i * 11 + pageIndex * 40) % height;
    
    ctx.fillStyle = i % 3 === 0 ? '#FFFFFF' : (i % 3 === 1 ? '#000000' : '#FF0000');
    // 画 1x1 或 2x1 的像素点
    ctx.fillRect(x, y, 1.5, 1.5);
    dataIdx++;
  }
  ctx.restore();
}

// === 背景样式 ===
function paintBgPattern(ctx, width, height, st) {
  if (st.bgPattern === 'none') return;
  ctx.save();
  ctx.strokeStyle = rgba(st.gridLine, 0.05);
  ctx.fillStyle = rgba(st.gridLine, 0.05);
  ctx.lineWidth = 1;
  
  if (st.bgPattern === 'grid') {
    const step = 40;
    for(let x=0; x<width; x+=step) { ctx.beginPath(); ctx.moveTo(x,0); ctx.lineTo(x,height); ctx.stroke(); }
    for(let y=0; y<height; y+=step) { ctx.beginPath(); ctx.moveTo(0,y); ctx.lineTo(width,y); ctx.stroke(); }
  } else if (st.bgPattern === 'dots') {
    const step = 30;
    for(let x=0; x<width; x+=step) {
      for(let y=0; y<height; y+=step) {
        ctx.beginPath(); ctx.arc(x, y, 1.5, 0, Math.PI*2); ctx.fill();
      }
    }
  } else if (st.bgPattern === 'diagonal') {
    const step = 30;
    for(let i=-width; i<height+width; i+=step) {
      ctx.beginPath(); ctx.moveTo(i, 0); ctx.lineTo(i+height, height); ctx.stroke();
    }
  }
  ctx.restore();
}

// === 装饰元素 ===
function paintDecorations(ctx, width, height, st) {
  if (st.decoType === 'none') return;
  ctx.save();
  ctx.fillStyle = rgba(st.gridLine, 0.1);
  
  let seed = 20260101 + width;
  const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; };
  const count = 50;

  for (let i = 0; i < count; i++) {
    const x = rnd() * width;
    const y = rnd() * height;
    
    if (st.decoType === 'stars') {
      const r = rnd() * 1.5 + 0.5;
      ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI*2); ctx.fill();
    } else if (st.decoType === 'snow') {
      // 简单雪花(用十字模拟)
      ctx.fillRect(x-1, y-4, 2, 8);
      ctx.fillRect(x-4, y-1, 8, 2);
    } else if (st.decoType === 'bubbles') {
      const r = rnd() * 6 + 2;
      ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI*2);
      ctx.strokeStyle = rgba(st.gridLine, 0.15);
      ctx.stroke();
    } else if (st.decoType === 'geometric') {
      ctx.beginPath();
      ctx.moveTo(x, y-3); ctx.lineTo(x+3, y+2); ctx.lineTo(x-3, y+2);
      ctx.closePath(); ctx.fill();
    }
  }
  ctx.restore();
}

// === 水印 ===
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

      drawShape(ctx, bx, by, cell, cell, st.gridRadius, st.noteShape);
      
      if (isActive) {
        const t = gRows > 1 ? r / (gRows - 1) : 0;
        ctx.fillStyle = mixHex(st.noteFill, st.noteFillAlt, t);
        ctx.fill();
        if (st.noteStrokeAlpha > 0) {
          ctx.strokeStyle = rgba(st.noteStroke, st.noteStrokeAlpha);
          ctx.lineWidth = 1;
          ctx.stroke();
        }
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

    // 值超长截断
    const maxValueW = colW - labelW - 16;
    let value = item.value;
    if (ctx.measureText(value).width > maxValueW) {
      while (value.length > 1 && ctx.measureText(value + '…').width > maxValueW) {
        value = value.substring(0, value.length - 1);
      }
      value += '…';
    }
    ctx.fillText(value, ix + labelW + 8, iy);
  }

  // 分割线
  const lineY = y + rowCount * rowH + 6;
  ctx.strokeStyle = rgba(st.dividerColor, st.dividerAlpha);
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(x, lineY);
  ctx.lineTo(x + width, lineY);
  ctx.stroke();

  ctx.textBaseline = 'top';
}

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

  // 1. 底层渐变背景
  const bgGrad = ctx.createLinearGradient(0, 0, 0, m.height);
  bgGrad.addColorStop(0, st.bg);
  bgGrad.addColorStop(1, st.bgGlow);
  ctx.fillStyle = bgGrad;
  ctx.fillRect(0, 0, m.width, m.height);

  // 2. 背景图案
  paintBgPattern(ctx, m.width, m.height, st);

  // 3. 装饰元素
  paintDecorations(ctx, m.width, m.height, st);

  // 4. 视觉水印
  paintWatermark(ctx, m.width, m.height, st);

  // 5. 隐形防伪水印
  paintStealthWatermark(ctx, m.width, m.height, pageIndex);

  // 6. 标题 (支持左中右对齐)
  let titleX = m.width / 2;
  ctx.textAlign = 'center';
  if (st.titleAlign === 'left') { titleX = st.pad; ctx.textAlign = 'left'; }
  else if (st.titleAlign === 'right') { titleX = m.width - st.pad; ctx.textAlign = 'right'; }

  ctx.font = '700 ' + st.titleSize + 'px ' + FONT_STACK;
  ctx.fillStyle = st.titleColor;
  ctx.fillText(meta.title, titleX, st.pad);

  ctx.textAlign = 'left';
  ctx.font = '400 ' + st.metaSize + 'px ' + FONT_STACK;
  ctx.fillStyle = st.metaColor;
  ctx.fillText((pageIndex + 1) + ' / ' + pageCount, st.pad, st.pad + st.titleSize * 1.4 + 10);

  // 7. 信息面板
  const infoTop = st.pad + m.headerH;
  if (st.showInfoPanel && infoItems.length) {
    drawInfoPanel(ctx, st.pad, infoTop, m.bodyW, infoItems, st);
  }

  // 8. 网格绘制
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

    if (st.showTime) {
      ctx.textAlign = 'center';
      ctx.font = '400 ' + st.timeSize + 'px ' + FONT_STACK;
      ctx.fillStyle = rgba(st.timeColor, st.timeAlpha);
      ctx.fillText(formatMs(col.t), gx + m.gridW / 2, gy + m.gridH + 8);
    }
  }

  // 9. 页脚
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

export function previewScaleFor(rows, st, infoItemCount) {
  const m = measurePage(rows, st, infoItemCount);
  let s = Math.min(window.devicePixelRatio || 1, 2);
  const px = m.width * m.height * s * s;
  if (px > 24000000) s = Math.sqrt(24000000 / (m.width * m.height));
  return Math.max(0.35, s);
}