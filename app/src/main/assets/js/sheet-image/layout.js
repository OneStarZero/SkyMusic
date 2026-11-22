/**
 * SkyMusic · 谱面图片布局计算
 */

import { formatMs, formatDuration, todayStamp } from './style.js';

export function gridColsFor(rows) {
  return rows === 9 ? 3 : 5;
}

export function gridRowsFor() {
  return 3;
}

export function keyToRow(key, rows) {
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

export function collectNotes(data, rows) {
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

export function buildLayout(notes, rows, toleranceMs) {
  const tol = Number.isFinite(toleranceMs) && toleranceMs >= 0 ? toleranceMs : 25;
  const sorted = notes.slice().sort((a, b) => (a.t - b.t) || (a.r - b.r));

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
    columns[i].rows.sort((a, b) => a - b);
  }

  return { rows: rows, columns: columns };
}

/** 按「每页 cols × rowsPerPage 个时间点」分页 */
export function buildPages(layout, st, rowsPerPage, cols) {
  const c = layout.columns;
  const rpp = rowsPerPage || st.rowsPerPage || 6;
  const cc = cols || st.cols || 5;
  const perPage = Math.max(1, Math.round(cc) * Math.round(rpp));
  const pages = [];

  for (let start = 0; start < c.length; start += perPage) {
    pages.push({ columns: c.slice(start, start + perPage) });
  }

  if (pages.length === 0) pages.push({ columns: [] });
  return pages;
}

/**
 * 计算一页画布尺寸（CSS 像素）
 * 与原生 SheetImageExporter.kt 的 measure() 严格一致
 */
export function measurePage(rows, st, infoItemCount, rowsPerPage, cols) {
  const rpp = rowsPerPage || st.rowsPerPage || 6;
  const cc = cols || st.cols || 5;

  const gCols = gridColsFor(rows);
  const gRows = gridRowsFor(rows);
  const gridW = gCols * st.cell + (gCols - 1) * st.cellGap;
  const gridH = gRows * st.cell + (gRows - 1) * st.cellGap;
  const timeH = st.timeSize * 2.2;

  const headerH = st.titleSize * 1.4 + 10 + st.metaSize * 1.5 + 26;

  let infoH = 0;
  if (st.showInfoPanel && infoItemCount > 0) {
    const infoRows = Math.ceil(infoItemCount / 2);
    const infoRowH = st.infoSize * 2.0;
    infoH = infoRows * infoRowH + 28;
  }

  const footerH = st.watermarkSize * 2.6;

  const bodyW = cc * gridW + (cc - 1) * st.gridGapX;
  const bodyH = rpp * (gridH + timeH) + (rpp - 1) * st.gridGapY;

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

export function computeInfo(layout, rows, tol) {
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

export function buildInfoItems(info, rows, bpm, model, author, transcriber) {
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
  } else {
    items.push({ label: '最常用键', value: '—' });
  }

  return items;
}
