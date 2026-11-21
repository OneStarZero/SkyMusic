/**
 * SkyMusic · 进度条显示
 */

import { view } from './state.js';

export function showProgress(text, percent) {
  if (!view.progressBar) return;
  view.progressBar.classList.add('smsi-show');
  if (view.progressText) view.progressText.textContent = text;
  if (view.progressFill) view.progressFill.style.width = Math.max(0, Math.min(100, percent)) + '%';
}

export function hideProgress() {
  if (!view.progressBar) return;
  view.progressBar.classList.remove('smsi-show');
  if (view.progressFill) view.progressFill.style.width = '0%';
}

/** 原生端回调进度 */
window.onSheetImageProgress = function (text, percent) {
  showProgress(text, percent);
};
