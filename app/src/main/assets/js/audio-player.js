// js/audio-player.js
import PIANO_DATA from './piano-data.js';

let audioCtx = null;
let activeSources = [];
let pianoSamples = {};
let isLoaded = false;
let keepAliveInterval = null;

// 🌟 1. 极速解码内置的 Base64 音频
async function loadSamples() {
    if (isLoaded) return;
    if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();

    const promises = [];
    for (let i = 0; i <= 14; i++) {
        if (!PIANO_DATA[i]) continue;
        promises.push(
            fetch(PIANO_DATA[i])
                .then(res => res.arrayBuffer())
                .then(buffer => audioCtx.decodeAudioData(buffer))
                .then(decoded => { pianoSamples[i] = decoded; })
                .catch(e => console.warn(`音频 ${i} 解码失败`, e))
        );
    }
    await Promise.all(promises);
    isLoaded = true;
    console.log("🎹 15 个内置真实钢琴音色加载完毕！");
}

// 页面一加载就开始偷偷解码
loadSamples();

// 🌟 2. 统一的播放引擎
export function playMidiNotes(notes) {
    if (!isLoaded) {
        setTimeout(() => playMidiNotes(notes), 500);
        return;
    }
    
    stopAudio();
    audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    if (audioCtx.state === 'suspended') audioCtx.resume();

    const startTime = audioCtx.currentTime + 0.1;
    let lastPlayedTime = -1;

    // 主音量防破音
    const masterGain = audioCtx.createGain();
    masterGain.gain.value = 0.6; 
    masterGain.connect(audioCtx.destination);

    notes.sort((a, b) => a.time - b.time);
    const VALID_KEYS = [60, 62, 64, 65, 67, 69, 71, 72, 74, 76];

    notes.forEach(note => {
        if (note.velocity < 0.15) return; 
        let playTime = note.time;
        let pitch = note.midi;
        let duration = Math.min(note.duration + 0.3, 1.2); 

        if (playTime - lastPlayedTime < 0.05) {
            playTime = lastPlayedTime + 0.05;
        }
        lastPlayedTime = playTime;

        let p = pitch;
        while (p < 60) p += 12;
        while (p > 76) p -= 12;
        
        let bestKeyIdx = 0;
        let minDist = 999;
        for (let i = 0; i < VALID_KEYS.length; i++) {
            let dist = Math.abs(p - VALID_KEYS[i]);
            if (dist < minDist) { minDist = dist; bestKeyIdx = i; }
        }
        let fileIndex = Math.min(14, Math.max(0, pitch > 76 ? bestKeyIdx + 5 : bestKeyIdx));

        const buffer = pianoSamples[fileIndex];
        if (!buffer) return;

        const source = audioCtx.createBufferSource();
        source.buffer = buffer; 
        source.playbackRate.value = 1.0; 

        const gainNode = audioCtx.createGain();
        gainNode.gain.value = Math.min(1.0, note.velocity * 1.2);

        source.connect(gainNode);
        gainNode.connect(masterGain);

        const noteStart = startTime + playTime;
        source.start(noteStart);
        source.stop(noteStart + duration);
        
        activeSources.push(source);
    });
}

export function stopAudio() {
    if (keepAliveInterval) clearInterval(keepAliveInterval);
    activeSources.forEach(source => {
        try { source.stop(); } catch(e) {}
    });
    activeSources = [];
    if (audioCtx) {
        audioCtx.close();
        audioCtx = null;
    }
}