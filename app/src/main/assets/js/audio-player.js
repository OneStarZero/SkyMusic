// js/audio-player.js
let fallbackAudioCtx = null;
let currentTimeouts = [];

export function stopAudio() {
    currentTimeouts.forEach(t => clearTimeout(t));
    currentTimeouts = [];
    if (fallbackAudioCtx) {
        fallbackAudioCtx.close();
        fallbackAudioCtx = null;
    }
}

// 播放铺面音符（使用轻量三角波，零加载延迟，绝不卡顿）
export function playNotes(notes) {
    stopAudio(); // 先停止之前的播放
    
    fallbackAudioCtx = new (window.AudioContext || window.webkitAudioContext)();
    if (fallbackAudioCtx.state === 'suspended') {
        fallbackAudioCtx.resume();
    }
    
    const startTime = fallbackAudioCtx.currentTime + 0.1;
    let maxEndTime = 0;

    notes.forEach(n => {
        // 铺面音符的 time 是毫秒，key 是 "1KeyX"
        const playTime = n.time / 1000;
        const keyIdx = parseInt(n.key.replace('1Key', ''));
        const midiPitch = [60, 62, 64, 65, 67, 69, 71, 72, 74, 76][keyIdx] || 60;
        const freq = 440 * Math.pow(2, (midiPitch - 69) / 12);

        const osc = fallbackAudioCtx.createOscillator();
        const gain = fallbackAudioCtx.createGain();
        
        // 三角波，听起来最像简单的电子琴
        osc.type = 'triangle';
        osc.frequency.value = freq;

        const noteStart = startTime + playTime;
        const duration = 0.4; // 铺面单音固定时长

        // 包络控制：快速起音，快速释放，防止声音糊在一起
        gain.gain.setValueAtTime(0, noteStart);
        gain.gain.linearRampToValueAtTime(0.3, noteStart + 0.02);
        gain.gain.exponentialRampToValueAtTime(0.001, noteStart + duration);

        osc.connect(gain);
        gain.connect(fallbackAudioCtx.destination);

        osc.start(noteStart);
        osc.stop(noteStart + duration + 0.1);

        if (playTime + duration > maxEndTime) {
            maxEndTime = playTime + duration;
        }
    });

    // 自动清理
    const timeout = setTimeout(() => {
        if (fallbackAudioCtx) {
            fallbackAudioCtx.close();
            fallbackAudioCtx = null;
        }
    }, (maxEndTime + 1) * 1000);
    currentTimeouts.push(timeout);
}