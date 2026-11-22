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

/** 是否支持原生试听 */
export function isPreviewSupported() {
  const bridge = getBridge();
  return !!(bridge && typeof bridge.playPreview === 'function');
}

/**
 * 播放
 * @param {Array} notes [{midi, startMs, durationMs, velocity}]
 * @param {number} totalMs 总时长
 */
export function playPreview(notes, totalMs) {
  const bridge = getBridge();
  if (!bridge || typeof bridge.playPreview !== 'function') {
    return { ok: false, error: '当前环境不支持原生试听' };
  }
  if (!notes || !notes.length) {
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
    return { ok: !!ok };
  } catch (err) {
    return { ok: false, error: err && err.message ? err.message : String(err) };
  }
}

/** 暂停 */
export function pausePreview() {
  const bridge = getBridge();
  if (bridge && typeof bridge.pausePreview === 'function') {
    bridge.pausePreview();
  }
}

/** 恢复 */
export function resumePreview() {
  const bridge = getBridge();
  if (bridge && typeof bridge.resumePreview === 'function') {
    bridge.resumePreview();
  }
}

/** 停止 */
export function stopPreview() {
  const bridge = getBridge();
  if (bridge && typeof bridge.stopPreview === 'function') {
    bridge.stopPreview();
  }
}

/** 是否正在播放（不含暂停） */
export function isPlaying() {
  return currentState === 'playing';
}

/** 是否暂停 */
export function isPaused() {
  return currentState === 'paused';
}

/** 是否活跃（播放或暂停） */
export function isActive() {
  return currentState === 'playing' || currentState === 'paused';
}

/** 当前进度（毫秒） */
export function getProgress() {
  return currentProgress;
}

/** 当前总时长（毫秒） */
export function getTotal() {
  return currentTotal;
}

/** 当前状态 */
export function getState() {
  return currentState;
}

/** 格式化时间 mm:ss */
export function formatTime(ms) {
  const t = Math.max(0, Math.round(ms / 1000));
  const m = Math.floor(t / 60);
  const s = t % 60;
  return m + ':' + (s < 10 ? '0' + s : s);
}

/** 原生回调入口（MainActivity 调用） */
window.onPreviewState = function (state, progressMs, totalMs) {
  currentState = state;
  currentProgress = progressMs;
  currentTotal = totalMs;

  window.dispatchEvent(new CustomEvent('skymusic:preview-state', {
    detail: { state: state, progressMs: progressMs, totalMs: totalMs }
  }));
};
