// js/audio-player.js
let audioCtx = null;
let activeOscillators = [];
let keepAliveInterval = null;

export function stopAudio() {
    // 清除保持唤醒的定时器
    if (keepAliveInterval) {
        clearInterval(keepAliveInterval);
        keepAliveInterval = null;
    }

    // 强制停止所有正在发声的振荡器
    activeOscillators.forEach(osc => {
        try { osc.stop(); } catch(e) {}
    });
    activeOscillators = [];
    
    // 关闭上下文释放资源
    if (audioCtx) {
        audioCtx.close();
        audioCtx = null;
    }
}

// 页面切到后台时，强制静音停止
document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
        stopAudio();
    }
});

export function playMidiNotes(notes) {
    stopAudio();
    
    try {
        audioCtx = new (window.AudioContext || window.webkitAudioContext)();
        if (audioCtx.state === 'suspended') audioCtx.resume();

        // 🌟 核心修复：开启一个定时器，每 1 秒强制唤醒一次音频上下文，防止被系统休眠
        keepAliveInterval = setInterval(() => {
            if (audioCtx && audioCtx.state === 'suspended') {
                audioCtx.resume();
            }
        }, 1000);

        const startTime = audioCtx.currentTime + 0.1;
        let lastPlayedTime = -1;

        notes.sort((a, b) => a.time - b.time);

        notes.forEach(note => {
            if (note.velocity < 0.15) return; // 过滤极弱音

            let playTime = note.time;
            let pitch = note.midi;
            let duration = Math.min(note.duration, 0.5); // 最多响 0.5 秒
            let gain = Math.min(0.3, note.velocity * 1.5);

            // 强制错开 40ms
            if (playTime - lastPlayedTime < 0.04) {
                playTime = lastPlayedTime + 0.04;
            }
            lastPlayedTime = playTime;

            const freq = 440 * Math.pow(2, (pitch - 69) / 12);
            const osc = audioCtx.createOscillator();
            const gainNode = audioCtx.createGain();
            
            osc.type = 'triangle';
            osc.frequency.value = freq;

            const noteStart = startTime + playTime;
            gainNode.gain.setValueAtTime(0, noteStart);
            gainNode.gain.linearRampToValueAtTime(gain, noteStart + 0.02);
            gainNode.gain.exponentialRampToValueAtTime(0.001, noteStart + duration);

            osc.connect(gainNode);
            gainNode.connect(audioCtx.destination);

            osc.start(noteStart);
            osc.stop(noteStart + duration + 0.1);
            
            activeOscillators.push(osc);
        });

        // ❌ 删除了之前那个 setTimeout 自动挂起的逻辑
        // 现在它会一直播放，直到用户手动点击“停止”或者切到后台

    } catch (e) {
        console.error("【音频引擎崩溃】", e);
    }
}