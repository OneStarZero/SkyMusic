/**
 * SkyMusic · 预览层 Toast
 */

let toast = function (message, type) {
  let box = document.getElementById('smsi-fallback-toast');
  if (!box) {
    box = document.createElement('div');
    box.id = 'smsi-fallback-toast';
    box.style.cssText = 'position:fixed;left:50%;top:30px;transform:translateX(-50%);z-index:100001;' +
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

import('../../ui.js')
  .then(function (m) {
    if (m && typeof m.showToast === 'function') toast = m.showToast;
  })
  .catch(function () { /* 使用内置兜底 */ });

export function showToast(message, type) {
  toast(message, type);
}
