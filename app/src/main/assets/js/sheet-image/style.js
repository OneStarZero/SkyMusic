/**
 * SkyMusic · 谱面图片样式常量与颜色工具
 */

export const STYLE_DEFAULTS = {
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

export const FONT_STACK = '"PingFang SC","Noto Sans SC","Microsoft YaHei",system-ui,sans-serif';
export const WATERMARK_TEXT = 'Bilibili@_OneStar';
export const APP_NAME = 'SkyMusic';

/** 单页位图像素上限（与原生端保持一致） */
export const MAX_PAGE_PIXELS = 24000000;
/** 长图像素上限 */
export const MAX_LONG_PIXELS = 120000000;

export function hexToRgb(hex) {
  let h = String(hex == null ? '' : hex).trim();
  if (h.charAt(0) === '#') h = h.slice(1);
  if (h.length === 3) {
    h = h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
  }
  if (h.length !== 6 || /[^0-9a-fA-F]/.test(h)) return [255, 255, 255];
  return [
    parseInt(h.slice(0, 2), 16),
    parseInt(h.slice(2, 4), 16),
    parseInt(h.slice(4, 6), 16)
  ];
}

export function rgba(hex, alpha) {
  const rgb = hexToRgb(hex);
  const a = Math.max(0, Math.min(1, Number(alpha)));
  return 'rgba(' + rgb[0] + ',' + rgb[1] + ',' + rgb[2] + ',' + a + ')';
}

export function mixHex(from, to, t) {
  const A = hexToRgb(from);
  const B = hexToRgb(to);
  const k = Math.max(0, Math.min(1, Number(t)));
  const r = Math.round(A[0] + (B[0] - A[0]) * k);
  const g = Math.round(A[1] + (B[1] - A[1]) * k);
  const b = Math.round(A[2] + (B[2] - A[2]) * k);
  return 'rgb(' + r + ',' + g + ',' + b + ')';
}

export function clampInt(v, min, max, def) {
  const n = parseInt(v, 10);
  if (!Number.isFinite(n)) return def;
  return Math.max(min, Math.min(max, n));
}

export function formatMs(ms) {
  const t = Math.max(0, Math.round(Number(ms) || 0));
  const m = Math.floor(t / 60000);
  const s = Math.floor((t % 60000) / 1000);
  return m + ':' + (s < 10 ? '0' + s : s);
}

export function formatDuration(ms) {
  const t = Math.max(0, Math.round(Number(ms) || 0));
  const m = Math.floor(t / 60000);
  const s = Math.floor((t % 60000) / 1000);
  const cs = Math.floor((t % 1000) / 10);
  return m + ':' + (s < 10 ? '0' + s : s) + '.' + (cs < 10 ? '0' + cs : cs);
}

export function todayStamp() {
  const d = new Date();
  const p = n => (n < 10 ? '0' + n : String(n));
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}

/** 文件名净化：只替换文件系统真正不允许的字符，保留 "||" */
export function sanitizeFileName(name) {
  let s = String(name == null ? '' : name).trim();
  if (!s) return '未命名乐谱';
  s = s.replace(/[\\/:*?"<>\r\n\t]/g, '_');
  s = s.replace(/\s+/g, ' ').trim();
  if (s.length > 60) s = s.substring(0, 60).trim();
  if (!s || s === '.' || s === '..') return '未命名乐谱';
  return s;
}