/**
 * SkyMusic · 谱面图片生成模块（对外入口）
 * ------------------------------------------------------------------
 * 实际实现已拆分到 ./sheet-image/ 目录：
 *   style.js   样式常量与颜色工具
 *   layout.js  布局计算与统计
 *   render.js  Canvas 绘制
 *   zip.js     无依赖 ZIP 写入器
 *   preview.js 预览层 UI 与保存调度
 *
 * 对外 API 保持不变，midi-parser.js 无需改动。
 */

export { openSheetImagePreview } from './sheet-image/preview.js';
export { buildLayout as buildSheetLayout, buildPages as buildSheetPages } from './sheet-image/layout.js';