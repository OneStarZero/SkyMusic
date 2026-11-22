/**
 * SkyMusic · 谱面图片样式常量与颜色工具
 */

export const THEMES = {
  warm: { name: '暖棕', bg: '#1A1614', bgGlow: '#2A2420', gridLine: '#E8C88A', noteFill: '#E8C88A', noteFillAlt: '#C9A96E', logoColor: '#E8C88A', watermarkColor: '#E8C88A' },
  morning: { name: '晨岛蓝', bg: '#0B1120', bgGlow: '#1E1B4B', gridLine: '#7DD3FC', noteFill: '#7DD3FC', noteFillAlt: '#A78BFA', logoColor: '#7DD3FC', watermarkColor: '#7DD3FC' },
  mono: { name: '极简黑白', bg: '#111111', bgGlow: '#000000', gridLine: '#FFFFFF', noteFill: '#FFFFFF', noteFillAlt: '#AAAAAA', logoColor: '#FFFFFF', watermarkColor: '#FFFFFF' },
  vivid: { name: '高对比紫', bg: '#1A0B2E', bgGlow: '#090512', gridLine: '#C084FC', noteFill: '#C084FC', noteFillAlt: '#F472B6', logoColor: '#F472B6', watermarkColor: '#C084FC' },
  cyberpunk: { name: '赛博朋克', bg: '#09090b', bgGlow: '#18181b', gridLine: '#FDE047', noteFill: '#FDE047', noteFillAlt: '#EC4899', logoColor: '#EC4899', watermarkColor: '#FDE047' },
  forest: { name: '雨林绿', bg: '#061A14', bgGlow: '#0F2E24', gridLine: '#34D399', noteFill: '#34D399', noteFillAlt: '#A3E635', logoColor: '#34D399', watermarkColor: '#34D399' },
  sunset: { name: '霞谷落日', bg: '#2B0F15', bgGlow: '#451A03', gridLine: '#FB923C', noteFill: '#FB923C', noteFillAlt: '#FDE047', logoColor: '#FB923C', watermarkColor: '#FB923C' },
  ocean: { name: '深海幽蓝', bg: '#020617', bgGlow: '#0F172A', gridLine: '#38BDF8', noteFill: '#38BDF8', noteFillAlt: '#818CF8', logoColor: '#38BDF8', watermarkColor: '#38BDF8' }
};

export const CORNER_STYLES = {
  round: { name: '圆角', gridRadius: 5, noteShape: 'square' },
  square: { name: '直角', gridRadius: 0, noteShape: 'square' },
  skystudio: { name: 'SkyStudio', gridRadius: 6, noteShape: 'skystudio' }
};

export const RANDOM_STYLE = {
  name: '随机幻彩',
  key: 'random'
};

export const STYLE_DEFAULTS = {
  bg: '#0B1120', bgGlow: '#1E1B4B', gridLine: '#7DD3FC', gridLineAlpha: 0.22,
  noteFill: '#7DD3FC', noteFillAlt: '#A78BFA', noteStroke: '#FFFFFF', noteStrokeAlpha: 0.40,
  titleColor: '#F8FAFC', metaColor: '#94A3B8', timeColor: '#CBD5E1', timeAlpha: 0.72,
  watermarkColor: '#7DD3FC', watermarkAlpha: 0.055, logoColor: '#7DD3FC', infoLabelColor: '#94A3B8',
  infoValueColor: '#E2E8F0', dividerColor: '#7DD3FC', dividerAlpha: 0.14,

  cell: 22, cellGap: 4, gridRadius: 5, cols: 5, rowsPerPage: 6, gridGapX: 26, gridGapY: 30, pad: 40,
  titleSize: 30, metaSize: 14, timeSize: 13, watermarkSize: 13, logoSize: 15, infoSize: 12,

  showStars: true, showWatermark: true, showInfoPanel: true, showTime: true,

  decoType: 'none',
  bgPattern: 'none',
  noteShape: 'square',
  titleAlign: 'center'
};

export const FONT_STACK = '"PingFang SC","Noto Sans SC","Microsoft YaHei",system-ui,sans-serif';
export const WATERMARK_TEXT = 'Bilibili@_OneStar';
export const APP_NAME = 'SkyMusic';
export const MAX_PAGE_PIXELS = 24000000;
export const MAX_LONG_PIXELS = 80000000;

export function getThemeStyle(themeKey, cornerKey = 'round') {
  let t = THEMES[themeKey];

  if (themeKey === 'random') {
    const h1 = Math.floor(Math.random() * 360);
    const h2 = (h1 + 120 + Math.random() * 120) % 360;
    const h3 = (h1 + 240 + Math.random() * 120) % 360;

    const cellSize = [18, 20, 22, 24, 26, 28, 30][Math.floor(Math.random() * 7)];
    const gapSize = [2, 3, 4, 5, 6, 8][Math.floor(Math.random() * 6)];
    const padSize = [28, 32, 36, 40, 44, 48, 52][Math.floor(Math.random() * 7)];

    const decoTypes = ['stars', 'snow', 'bubbles', 'geometric', 'none'];
    const bgPatterns = ['none', 'grid', 'dots', 'diagonal'];
    const noteShapes = ['square', 'circle', 'hexagon', 'diamond'];
    const titleAligns = ['center', 'left', 'right'];

    const sat1 = 60 + Math.random() * 30;
    const sat2 = 60 + Math.random() * 30;
    const light1 = 55 + Math.random() * 15;
    const light2 = 55 + Math.random() * 15;

    t = {
      name: '随机幻彩',
      bg: `hsl(${h1}, ${sat1 * 0.6}%, ${4 + Math.random() * 4}%)`,
      bgGlow: `hsl(${h1}, ${sat1 * 0.5}%, ${10 + Math.random() * 6}%)`,
      gridLine: `hsl(${h1}, ${sat1}%, ${light1}%)`,
      noteFill: `hsl(${h1}, ${sat1}%, ${light1}%)`,
      noteFillAlt: `hsl(${h2}, ${sat2}%, ${light2}%)`,
      logoColor: `hsl(${h3}, 90%, 70%)`,
      watermarkColor: `hsl(${h1}, ${sat1}%, ${light1}%)`,

      cell: cellSize,
      cellGap: gapSize,
      pad: padSize,
      gridLineAlpha: 0.1 + Math.random() * 0.3,
      noteStrokeAlpha: Math.random() > 0.5 ? 0 : 0.4,
      showTime: Math.random() > 0.2,
      showWatermark: true,

      decoType: decoTypes[Math.floor(Math.random() * decoTypes.length)],
      bgPattern: bgPatterns[Math.floor(Math.random() * bgPatterns.length)],
      noteShape: noteShapes[Math.floor(Math.random() * noteShapes.length)],
      titleAlign: titleAligns[Math.floor(Math.random() * titleAligns.length)]
    };
  }

  if (!t) t = THEMES.morning;

  const corner = CORNER_STYLES[cornerKey] || CORNER_STYLES.round;
  const finalRadius = corner.gridRadius;

  return Object.assign({}, STYLE_DEFAULTS, {
    bg: t.bg || STYLE_DEFAULTS.bg,
    bgGlow: t.bgGlow || STYLE_DEFAULTS.bgGlow,
    gridLine: t.gridLine || STYLE_DEFAULTS.gridLine,
    noteFill: t.noteFill || STYLE_DEFAULTS.noteFill,
    noteFillAlt: t.noteFillAlt || STYLE_DEFAULTS.noteFillAlt,
    logoColor: t.logoColor || STYLE_DEFAULTS.logoColor,
    watermarkColor: t.watermarkColor || STYLE_DEFAULTS.watermarkColor,
    dividerColor: t.gridLine || STYLE_DEFAULTS.dividerColor,

    cell: t.cell || STYLE_DEFAULTS.cell,
    cellGap: t.cellGap || STYLE_DEFAULTS.cellGap,
    pad: t.pad || STYLE_DEFAULTS.pad,
    gridRadius: finalRadius,
    gridLineAlpha: t.gridLineAlpha !== undefined ? t.gridLineAlpha : STYLE_DEFAULTS.gridLineAlpha,
    noteStrokeAlpha: t.noteStrokeAlpha !== undefined ? t.noteStrokeAlpha : STYLE_DEFAULTS.noteStrokeAlpha,
    showTime: t.showTime !== undefined ? t.showTime : STYLE_DEFAULTS.showTime,
    showWatermark: true,

    decoType: t.decoType || STYLE_DEFAULTS.decoType,
    bgPattern: t.bgPattern || STYLE_DEFAULTS.bgPattern,
    noteShape: t.noteShape || STYLE_DEFAULTS.noteShape,
    titleAlign: t.titleAlign || STYLE_DEFAULTS.titleAlign
  });
}


export function hexToRgb(hex) {
  let h = String(hex == null ? '' : hex).trim();
  if (h.startsWith('hsl')) {
    const m = h.match(/hsl\((\d+),\s*(\d+)%,\s*(\d+)%\)/);
    if (m) {
      const hh = parseInt(m[1]) / 360, ss = parseInt(m[2]) / 100, ll = parseInt(m[3]) / 100;
      let r, g, b;
      if (ss === 0) { r = g = b = ll; }
      else {
        const hue2rgb = (p, q, t) => {
          if (t < 0) t += 1; if (t > 1) t -= 1;
          if (t < 1 / 6) return p + (q - p) * 6 * t;
          if (t < 1 / 2) return q;
          if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
          return p;
        };
        const q = ll < 0.5 ? ll * (1 + ss) : ll + ss - ll * ss;
        const p = 2 * ll - q;
        r = hue2rgb(p, q, hh + 1 / 3); g = hue2rgb(p, q, hh); b = hue2rgb(p, q, hh - 1 / 3);
      }
      return [Math.round(r * 255), Math.round(g * 255), Math.round(b * 255)];
    }
  }
  if (h.charAt(0) === '#') h = h.slice(1);
  if (h.length === 3) h = h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
  if (h.length !== 6 || /[^0-9a-fA-F]/.test(h)) return [255, 255, 255];
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
}

export function rgba(hex, alpha) {
  const rgb = hexToRgb(hex);
  return 'rgba(' + rgb[0] + ',' + rgb[1] + ',' + rgb[2] + ',' + Math.max(0, Math.min(1, Number(alpha))) + ')';
}

export function mixHex(from, to, t) {
  const A = hexToRgb(from), B = hexToRgb(to), k = Math.max(0, Math.min(1, Number(t)));
  return 'rgb(' + Math.round(A[0] + (B[0] - A[0]) * k) + ',' + Math.round(A[1] + (B[1] - A[1]) * k) + ',' + Math.round(A[2] + (B[2] - A[2]) * k) + ')';
}

export function clampInt(v, min, max, def) {
  const n = parseInt(v, 10);
  return Number.isFinite(n) ? Math.max(min, Math.min(max, n)) : def;
}

export function formatMs(ms) {
  const t = Math.max(0, Math.round(Number(ms) || 0));
  return Math.floor(t / 60000) + ':' + (Math.floor((t % 60000) / 1000) < 10 ? '0' : '') + Math.floor((t % 60000) / 1000);
}

export function formatDuration(ms) {
  const t = Math.max(0, Math.round(Number(ms) || 0));
  const cs = Math.floor((t % 1000) / 10);
  return formatMs(t) + '.' + (cs < 10 ? '0' + cs : cs);
}

export function todayStamp() {
  const d = new Date();
  return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
}

export function sanitizeFileName(name) {
  let s = String(name == null ? '' : name).trim();
  if (!s) return '未命名乐谱';
  s = s.replace(/[\\/:*?"<>\r\n\t]/g, '_').replace(/\s+/g, ' ').trim();
  if (s.length > 60) s = s.substring(0, 60).trim();
  return (!s || s === '.' || s === '..') ? '未命名乐谱' : s;
}
