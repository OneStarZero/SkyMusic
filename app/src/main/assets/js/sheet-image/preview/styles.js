/**
 * SkyMusic · 预览层样式
 */

import { FONT_STACK } from '../style.js';

export const OVERLAY_CSS = [
  /* ========================== 遮罩与窗口 ========================== */
  '.smsi-overlay{position:fixed;inset:0;z-index:99999;display:flex;align-items:center;justify-content:center;',
  'background:rgba(8,12,24,.72);opacity:0;transition:opacity .22s ease;font-family:' + FONT_STACK + ';',
  'overscroll-behavior:contain;touch-action:none;}',
  '.smsi-overlay.smsi-show{opacity:1;}',

  '.smsi-window{width:min(92vw,760px);height:min(88vh,900px);display:flex;flex-direction:column;',
  'background:linear-gradient(180deg,rgba(15,23,42,.98),rgba(11,17,32,.99));',
  'border:1px solid rgba(125,211,252,.16);border-radius:20px;overflow:hidden;',
  'box-shadow:0 24px 64px rgba(0,0,0,.6),0 0 0 1px rgba(125,211,252,.06) inset;',
  'transform:scale(.96);transition:transform .22s cubic-bezier(.34,1.56,.64,1);}',
  '.smsi-overlay.smsi-show .smsi-window{transform:scale(1);}',

  /* ========================== 顶栏 ========================== */
  '.smsi-head{display:flex;align-items:center;gap:10px;padding:12px 14px;flex:0 0 auto;',
  'border-bottom:1px solid rgba(125,211,252,.10);background:rgba(11,17,32,.6);}',
  '.smsi-title{font-size:13.5px;font-weight:700;color:#F8FAFC;overflow:hidden;',
  'text-overflow:ellipsis;white-space:nowrap;flex:1 1 auto;letter-spacing:.3px;}',
  '.smsi-tools{display:flex;align-items:center;gap:5px;flex:0 0 auto;}',

  /* ========================== 按钮 ========================== */
  '.smsi-btn{appearance:none;border:1px solid rgba(125,211,252,.18);background:rgba(30,41,59,.42);',
  'color:#CBD5E1;font-size:12px;line-height:1;padding:7px 10px;border-radius:10px;cursor:pointer;',
  'transition:background .16s ease,border-color .16s ease,color .16s ease,transform .1s ease;',
  'position:relative;overflow:hidden;}',
  '.smsi-btn::after{content:"";position:absolute;inset:0;border-radius:inherit;',
  'background:radial-gradient(circle,rgba(125,211,252,.3),transparent 70%);',
  'opacity:0;transition:opacity .3s ease;}',
  '.smsi-btn:active::after{opacity:1;}',
  '.smsi-btn:active{transform:scale(.94);}',
  '.smsi-btn.smsi-on{border-color:rgba(125,211,252,.62);background:rgba(125,211,252,.16);color:#7DD3FC;}',
  '.smsi-btn.smsi-icon{padding:7px 9px;font-size:13px;}',
  '.smsi-btn.smsi-close{border-color:rgba(248,113,113,.22);color:#F87171;}',

  /* ========================== 标签页 ========================== */
  '.smsi-tabs{display:flex;gap:6px;padding:10px 14px 0;flex:0 0 auto;}',
  '.smsi-tab{flex:1;padding:9px 12px;border-radius:11px;border:1px solid rgba(125,211,252,.14);',
  'background:rgba(30,41,59,.32);color:#94A3B8;font-size:12.5px;font-weight:600;cursor:pointer;',
  'text-align:center;transition:all .18s ease;}',
  '.smsi-tab.smsi-active{border-color:rgba(125,211,252,.55);background:rgba(125,211,252,.14);color:#7DD3FC;}',

  /* ========================== 内容区 ========================== */
  '.smsi-body{flex:1 1 auto;overflow:hidden;position:relative;}',
  '.smsi-pane{position:absolute;inset:0;overflow:auto;padding:14px;-webkit-overflow-scrolling:touch;',
  'display:none;overscroll-behavior:contain;touch-action:pan-x pan-y;',
  'opacity:0;transform:translateX(12px);transition:opacity .22s ease,transform .22s ease;}',
  '.smsi-pane.smsi-active{display:block;opacity:1;transform:translateX(0);}',

  /* ========================== 分页预览 ========================== */
  '.smsi-pages{display:flex;flex-direction:column;align-items:center;gap:16px;}',
  '.smsi-page{position:relative;border-radius:14px;overflow:hidden;background:#0B1120;',
  'box-shadow:0 10px 30px rgba(0,0,0,.5),0 0 0 1px rgba(125,211,252,.08);',
  'transition:box-shadow .2s ease,opacity .2s ease,transform .2s ease;',
  'contain:layout paint style;',
  'animation:smsiPageIn .3s cubic-bezier(.34,1.56,.64,1) both;}',
  '@keyframes smsiPageIn{from{opacity:0;transform:translateY(12px) scale(.98);}',
  'to{opacity:1;transform:translateY(0) scale(1);}}',
  '.smsi-page.smsi-unselected{opacity:.4;}',
  '.smsi-canvas{display:block;width:100%;height:auto;}',

  '.smsi-pick{position:absolute;top:8px;right:8px;width:28px;height:28px;border-radius:50%;',
  'display:flex;align-items:center;justify-content:center;cursor:pointer;',
  'background:rgba(11,17,32,.72);border:1.5px solid rgba(125,211,252,.45);',
  'color:transparent;font-size:14px;font-weight:700;line-height:1;',
  'transition:background .18s ease,border-color .18s ease,color .18s ease,transform .18s cubic-bezier(.34,1.56,.64,1);}',
  '.smsi-pick:active{transform:scale(.85);}',
  '.smsi-page:not(.smsi-unselected) .smsi-pick{background:rgba(125,211,252,.9);',
  'border-color:rgba(125,211,252,.9);color:#0B1120;transform:scale(1.05);}',

  '.smsi-pageno{position:absolute;top:10px;left:10px;font-size:10.5px;color:#CBD5E1;',
  'background:rgba(11,17,32,.72);border:1px solid rgba(148,163,184,.18);',
  'padding:2px 8px;border-radius:8px;font-variant-numeric:tabular-nums;}',

  /* ========================== 长图预览 ========================== */
  '.smsi-long-wrap{display:flex;justify-content:center;align-items:flex-start;}',
  '.smsi-long-slot{position:relative;overflow:hidden;background:#0B1120;',
  'animation:smsiLongIn .35s cubic-bezier(.34,1.56,.64,1) both;}',
  '.smsi-long-canvas{display:block;border-radius:12px;}',
  '@keyframes smsiLongIn{from{opacity:0;transform:translateY(16px);}',
  'to{opacity:1;transform:translateY(0);}}',
  '.smsi-long-empty{color:#64748B;font-size:12.5px;text-align:center;padding:40px 20px;}',

  /* ========================== 缩放条 ========================== */
  '.smsi-zoombar{display:flex;align-items:center;justify-content:center;gap:8px;',
  'padding:8px 14px;flex:0 0 auto;border-top:1px solid rgba(125,211,252,.08);}',
  '.smsi-zoom{font-size:11px;color:#7DD3FC;min-width:40px;text-align:center;',
  'font-variant-numeric:tabular-nums;}',

  /* ========================== 底部操作区 ========================== */
  '.smsi-foot{display:flex;flex-direction:column;gap:8px;padding:10px 14px calc(12px + env(safe-area-inset-bottom));',
  'flex:0 0 auto;border-top:1px solid rgba(125,211,252,.10);background:rgba(11,17,32,.72);}',

  '.smsi-optrow{display:flex;align-items:center;justify-content:space-between;gap:10px;}',
  '.smsi-optrow.smsi-hidden{display:none;}',
  '.smsi-optlabel{font-size:12px;color:#CBD5E1;display:flex;align-items:center;gap:7px;}',
  '.smsi-optlabel small{color:rgba(148,163,184,.7);font-size:10.5px;}',

  /* ========================== 开关 ========================== */
  '.smsi-switch{position:relative;width:40px;height:23px;border-radius:12px;flex:0 0 auto;',
  'background:rgba(148,163,184,.22);border:1px solid rgba(148,163,184,.22);cursor:pointer;',
  'transition:background .2s ease,border-color .2s ease;}',
  '.smsi-switch::after{content:"";position:absolute;top:2px;left:2px;width:17px;height:17px;',
  'border-radius:50%;background:#CBD5E1;transition:transform .2s cubic-bezier(.34,1.56,.64,1),background .2s ease;}',
  '.smsi-switch.smsi-on{background:rgba(125,211,252,.28);border-color:rgba(125,211,252,.6);}',
  '.smsi-switch.smsi-on::after{transform:translateX(17px);background:#7DD3FC;}',

  /* ========================== 进度条 ========================== */
  '.smsi-progress{display:none;flex-direction:column;gap:5px;}',
  '.smsi-progress.smsi-show{display:flex;}',
  '.smsi-progress-track{height:4px;border-radius:2px;background:rgba(148,163,184,.18);overflow:hidden;}',
  '.smsi-progress-fill{height:100%;width:0%;border-radius:2px;',
  'background:linear-gradient(90deg,#7DD3FC,#A78BFA);',
  'transition:width .18s ease;',
  'box-shadow:0 0 8px rgba(125,211,252,.5);}',
  '.smsi-progress-text{font-size:10.5px;color:#94A3B8;text-align:center;}',

  /* ========================== 操作按钮 ========================== */
  '.smsi-actions{display:flex;gap:8px;align-items:center;}',
  '.smsi-actions .smsi-btn{flex:1;padding:12px 10px;font-size:13px;font-weight:600;border-radius:12px;}',
  '.smsi-actions .smsi-btn.smsi-hidden{display:none;}',
  '.smsi-btn.smsi-primary{border:none;color:#0B1120;',
  'background:linear-gradient(135deg,#7DD3FC,#A78BFA);',
  'box-shadow:0 5px 16px rgba(125,211,252,.26);}',
  '.smsi-btn.smsi-long{border:none;color:#0B1120;',
  'background:linear-gradient(135deg,#A78BFA,#C4B5FD);',
  'box-shadow:0 5px 16px rgba(167,139,250,.26);}',

  /* ========================== 主题面板 ========================== */
  '.smsi-theme-panel{position:absolute;top:0;right:0;bottom:0;width:min(82%,320px);',
  'background:rgba(11,17,32,.98);border-left:1px solid rgba(125,211,252,.14);',
  'transform:translateX(100%);transition:transform .24s cubic-bezier(.34,1.56,.64,1);',
  'display:flex;flex-direction:column;z-index:10;overflow-y:auto;',
  'box-shadow:-8px 0 24px rgba(0,0,0,.4);}',
  '.smsi-theme-panel.smsi-show{transform:translateX(0);}',

  '.smsi-theme-head{display:flex;align-items:center;justify-content:space-between;',
  'padding:12px 14px;border-bottom:1px solid rgba(125,211,252,.10);',
  'font-size:13px;font-weight:700;color:#F8FAFC;flex:0 0 auto;}',

  '.smsi-theme-section{padding:12px 14px;}',
  '.smsi-theme-label{font-size:11.5px;color:#94A3B8;margin-bottom:8px;font-weight:600;letter-spacing:.5px;}',

  '.smsi-theme-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:8px;}',
  '.smsi-theme-item{display:flex;flex-direction:column;align-items:center;gap:6px;padding:8px;',
  'border-radius:10px;border:1px solid rgba(125,211,252,.10);background:rgba(30,41,59,.32);',
  'cursor:pointer;transition:all .16s ease;font-size:11px;color:#CBD5E1;}',
  '.smsi-theme-item:active{transform:scale(.95);}',
  '.smsi-theme-item.smsi-selected{border-color:rgba(125,211,252,.7);',
  'background:rgba(125,211,252,.14);color:#7DD3FC;}',
  '.smsi-theme-swatch{width:100%;height:28px;border-radius:6px;}',
  '.smsi-theme-swatch.smsi-rainbow{animation:rainbowSpin 3s linear infinite;}',
  '@keyframes rainbowSpin{to{filter:hue-rotate(360deg);}}',

  '.smsi-corner-row{display:flex;gap:8px;}',
  '.smsi-corner-item{flex:1;display:flex;flex-direction:column;align-items:center;gap:6px;padding:8px;',
  'border-radius:10px;border:1px solid rgba(125,211,252,.10);background:rgba(30,41,59,.32);',
  'cursor:pointer;transition:all .16s ease;font-size:11px;color:#CBD5E1;}',
  '.smsi-corner-item:active{transform:scale(.95);}',
  '.smsi-corner-item.smsi-selected{border-color:rgba(125,211,252,.7);',
  'background:rgba(125,211,252,.14);color:#7DD3FC;}',
  '.smsi-corner-preview{width:32px;height:32px;background:rgba(125,211,252,.4);',
  'border:1.5px solid rgba(125,211,252,.6);}'
].join('');

export function injectStyle() {
  if (document.getElementById('smsi-style')) return;
  const styleEl = document.createElement('style');
  styleEl.id = 'smsi-style';
  styleEl.textContent = OVERLAY_CSS;
  document.head.appendChild(styleEl);
}
