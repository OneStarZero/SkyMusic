/**
 * SkyMusic · 保存进度记忆（localStorage）
 */

const PROGRESS_KEY = 'skymusic_sheet_image_progress_v3';

export function loadProgress(songName) {
  try {
    const raw = localStorage.getItem(PROGRESS_KEY);
    if (!raw) return null;
    const all = JSON.parse(raw);
    return all && all[songName] ? all[songName] : null;
  } catch (e) {
    return null;
  }
}

export function saveProgress(songName, data) {
  try {
    const raw = localStorage.getItem(PROGRESS_KEY);
    const all = raw ? JSON.parse(raw) : {};
    all[songName] = data;
    const keys = Object.keys(all);
    if (keys.length > 30) {
      keys.slice(0, keys.length - 30).forEach(k => delete all[k]);
    }
    localStorage.setItem(PROGRESS_KEY, JSON.stringify(all));
  } catch (e) { /* 忽略 */ }
}
