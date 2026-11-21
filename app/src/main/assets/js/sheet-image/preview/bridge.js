/**
 * SkyMusic · 原生桥接
 */

export function getBridge() {
  return window.AndroidBridge || window.Android || window.SkyMusicBridge || null;
}

export function setNativeTopBarVisible(visible) {
  try {
    const bridge = getBridge();
    if (bridge && typeof bridge.setTopBarVisible === 'function') {
      bridge.setTopBarVisible(visible);
    }
  } catch (e) { /* 忽略 */ }
}
