// js/audio-player.js

export function playMidiNotes(notes) {
    // 纯网页环境不再处理合成，直接静音
    console.log("网页环境已禁用音频合成，请在 App 内使用原生试听");
}

export function stopAudio() {
    // 空函数，仅用于兼容旧代码的调用
}