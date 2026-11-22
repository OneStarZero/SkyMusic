/**
 * SkyMusic · App 端原生试听控制
 * 依赖：window.AndroidBridge（MainActivity 注入）
 */

let currentState = 'idle';
let currentProgress = 0;
let currentTotal = 0;

function getBridge() {
  return window.AndroidBridge || null;
}

/** 写日志到原生 */
function logToNative(level, message) {
  try {
    if (window.SkyLogBridge && typeof window.SkyLogBridge.log === 'function') {
      window.SkyLogBridge.log(level, message);
    }
  } catch (_) {}
}

export function isPreviewSupported() {
  const bridge = getBridge();
  return !!(bridge && typeof bridge.playPreview === 'function');
}

export function playPreview(notes, totalMs) {
  const bridge = getBridge();
  if (!bridge || typeof bridge.playPreview !== 'function') {
    logToNative('WARN', 'playPreview: 不支持原生试听');
    return { ok: false, error: '当前环境不支持原生试听' };
  }
  if (!notes || !notes.length) {
    logToNative('WARN', 'playPreview: 没有音符');
    return { ok: false, error: '没有可播放的音符' };
  }

  const payload = JSON.stringify({
    notes: notes.map(function (n) {
      return {
        midi: n.midi | 0,
        startMs: Math.max(0, n.startMs | 0),
        durationMs: Math.max(50, n.durationMs | 0),
        velocity: typeof n.velocity === 'number' ? n.velocity : 0.8
      };
    }),
    totalMs: Math.max(100, totalMs | 0)
  });

  try {
    const ok = bridge.playPreview(payload);
    logToNative('INFO', 'playPreview: ' + notes.length + ' 音符, ' + totalMs + 'ms, ok=' + ok);
    return { ok: !!ok };
  } catch (err) {
    logToNative('ERROR', 'playPreview 异常: ' + (err && err.message ? err.message : err));
    return { ok: false, error: err && err.message ? err.message : String(err) };
  }
}

export function pausePreview() {
  const bridge = getBridge();
  if (bridge && typeof bridge.pausePreview === 'function') bridge.pausePreview();
}

export function resumePreview() {
  const bridge = getBridge();
  if (bridge && typeof bridge.resumePreview === 'function') bridge.resumePreview();
}

export function stopPreview() {
  const bridge = getBridge();
  if (bridge && typeof bridge.stopPreview === 'function') bridge.stopPreview();
}

/** 跳转到指定毫秒 */
export function seekPreview(ms) {
  const bridge = getBridge();
  if (bridge && typeof bridge.seekPreview === 'function') {
    bridge.seekPreview(Math.max(0, ms | 0));
  }
}

export function isPlaying() { return currentState === 'playing'; }
export function isPaused() { return currentState === 'paused'; }
export function isActive() { return currentState === 'playing' || currentState === 'paused'; }
export function getProgress() { return currentProgress; }
export function getTotal() { return currentTotal; }
export function getState() { return currentState; }

export function formatTime(ms) {
  const t = Math.max(0, Math.round(ms / 1000));
  const m = Math.floor(t / 60);
  const s = t % 60;
  return m + ':' + (s < 10 ? '0' + s : s);
}

/** 原生回调入口 */
window.onPreviewState = function (state, progressMs, totalMs) {
  currentState = state;
  currentProgress = progressMs;
  currentTotal = totalMs;

  logToNative('DEBUG', 'onPreviewState: ' + state + ' ' + progressMs + '/' + totalMs);

  window.dispatchEvent(new CustomEvent('skymusic:preview-state', {
    detail: { state: state, progressMs: progressMs, totalMs: totalMs }
  }));
};
