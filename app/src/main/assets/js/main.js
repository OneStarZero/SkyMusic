import { Midi } from '@tonejs/midi';

// ==================== 基础工具 ====================
function showToast(message, type = 'info') {
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    document.getElementById('toast-container').appendChild(toast);
    setTimeout(() => toast.remove(), 3000);
}

// ==================== 视图切换逻辑 ====================
const tabMidi = document.getElementById('tabMidi');
const tabOcr = document.getElementById('tabOcr');
const viewMidi = document.getElementById('viewMidi');
const viewOcr = document.getElementById('viewOcr');

tabMidi.addEventListener('click', () => {
    tabMidi.className = 'tab-btn active-midi';
    tabOcr.className = 'tab-btn inactive';
    viewMidi.style.display = 'block';
    viewOcr.style.display = 'none';
});

tabOcr.addEventListener('click', () => {
    tabOcr.className = 'tab-btn active-ocr';
    tabMidi.className = 'tab-btn inactive';
    viewMidi.style.display = 'none';
    viewOcr.style.display = 'block';
});


// ==================== 视图 1：MIDI 逻辑 ====================
const midiInput = document.getElementById('midiFile');
const dropZone = document.getElementById('dropZone');
const dropText = document.getElementById('dropText');
const configPanel = document.getElementById('configPanel');
const trackListContainer = document.getElementById('trackListContainer');
const convertBtn = document.getElementById('convertBtn');
const statusDiv = document.getElementById('status');
const logDiv = document.getElementById('log');
const resultCard = document.getElementById('resultCard');
const btnSendApp = document.getElementById('btnSendApp');
const btnSaveLocal = document.getElementById('btnSaveLocal');

let currentMidiData = null;
let pendingTxtContent = null;
let selectedMode = 'fast';
let selectedTrackIndices = new Set(); 

const KEY_MAP = { 60: "1Key0", 62: "1Key1", 64: "1Key2", 65: "1Key3", 67: "1Key4", 69: "1Key5", 71: "1Key6", 72: "1Key7", 74: "1Key8", 76: "1Key9" };
const VALID_KEYS = [60,62,64,65,67,69,71,72,74,76];

function mapPitchToSky(midiPitch, shift = 0) {
    let p = midiPitch + shift;
    while (p < 60) p += 12;
    while (p > 76) p -= 12;
    let best = 60, minDist = 999;
    for (const key of VALID_KEYS) {
        const d = Math.abs(p - key);
        if (d < minDist) { minDist = d; best = key; }
    }
    return KEY_MAP[best];
}

document.querySelectorAll('.model-card').forEach(card => {
    card.addEventListener('click', () => {
        if(card.classList.contains('active')) return;
        document.querySelectorAll('.model-card').forEach(c => c.classList.remove('active'));
        card.classList.add('active');
        selectedMode = card.getAttribute('data-mode');
        resultCard.style.display = 'none';
        logDiv.style.display = 'none';
        statusDiv.textContent = '准备就绪';
    });
});

// 🌟 拖拽逻辑（浏览器）
dropZone.addEventListener('dragover', (e) => { e.preventDefault(); });
dropZone.addEventListener('drop', (e) => { 
    e.preventDefault();
    if (e.dataTransfer.files.length) { 
        const file = e.dataTransfer.files[0];
        file.arrayBuffer().then(buf => processMidiData(buf, file.name));
    } 
});

// 🌟 MIDI 点击逻辑（调起原生独立 Activity）
dropZone.addEventListener('click', (e) => {
    // 如果点到的是 input，或者原生环境
    if (typeof window.AndroidBridge !== 'undefined' && window.AndroidBridge.openMidiPicker) {
        e.preventDefault(); // 阻止触发 input
        window.AndroidBridge.openMidiPicker();
    }
});

midiInput.addEventListener('change', async () => {
    const file = midiInput.files[0];
    if (!file) return;
    const arrayBuffer = await file.arrayBuffer();
    processMidiData(arrayBuffer, file.name);
});

// 🌟 接收原生传回的 MIDI 数据
window.onFileSelectedFromAndroid = function(base64Data, fileName) {
    try {
        const binaryString = atob(base64Data);
        const len = binaryString.length;
        const bytes = new Uint8Array(len);
        for (let i = 0; i < len; i++) {
            bytes[i] = binaryString.charCodeAt(i);
        }
        processMidiData(bytes.buffer, fileName);
    } catch (err) {
        showToast('文件读取失败，请重试', 'error');
    }
};

async function processMidiData(arrayBuffer, fileName) {
    if (!arrayBuffer || arrayBuffer.byteLength === 0) { showToast('文件内容为空', 'error'); return; }

    dropZone.classList.add('has-file');
    dropText.innerHTML = `✅ 已选择：<br><span class="filename">${fileName}</span>`;
    
    let rawName = fileName.replace(/\.[^.]+$/, '');
    let songTitle = rawName, originalAuthor = '';
    const separators = [' - ', ' -', '- ', '-', '_', '—'];
    for (let sep of separators) {
        if (rawName.includes(sep)) {
            const parts = rawName.split(sep);
            if (parts.length >= 2) { songTitle = parts[0].trim(); originalAuthor = parts.slice(1).join(' ').trim(); break; }
        }
    }
    document.getElementById('songNameInput').value = songTitle;
    document.getElementById('authorInput').value = originalAuthor; 

    statusDiv.textContent = '正在读取文件...';
    configPanel.style.display = 'none';
    resultCard.style.display = 'none';
    logDiv.style.display = 'none';
    logDiv.textContent = '';

    try {
        let midiData = new Midi(arrayBuffer);
        if (!midiData || !midiData.tracks) throw new Error('无法读取音轨数据');

        currentMidiData = midiData;
        trackListContainer.innerHTML = '';
        selectedTrackIndices.clear();
        let totalNotes = 0, validTrackCount = 0;

        currentMidiData.tracks.forEach((track, index) => {
            if (track.notes.length > 0) {
                validTrackCount++;
                totalNotes += track.notes.length;
                let displayName = track.name && track.name.trim() !== "" ? track.name.trim() : `轨道 ${index + 1}`;
                if (index !== 9 && selectedTrackIndices.size < 2) selectedTrackIndices.add(index);

                const item = document.createElement('div');
                item.className = `track-item ${selectedTrackIndices.has(index) ? 'active' : ''}`;
                item.innerHTML = `<div class="track-info"><span class="track-name">${displayName}</span><span class="track-notes">${track.notes.length} 音符</span></div><div class="check-circle"></div>`;
                item.addEventListener('click', () => {
                    if (selectedTrackIndices.has(index)) { selectedTrackIndices.delete(index); item.classList.remove('active'); } 
                    else { selectedTrackIndices.add(index); item.classList.add('active'); }
                });
                trackListContainer.appendChild(item);
            }
        });

        document.getElementById('infoTracks').textContent = validTrackCount;
        document.getElementById('infoNotes').textContent = totalNotes;
        configPanel.style.display = 'block';
        convertBtn.disabled = false; 
        statusDiv.textContent = '准备就绪';
    } catch (err) {
        statusDiv.textContent = '❌ 解析失败';
        showToast('解析失败: ' + err.message, 'error');
        dropZone.classList.remove('has-file'); 
        dropText.textContent = "点击选择或拖拽 MIDI 文件";
    }
}

convertBtn.addEventListener('click', async (e) => {
    e.preventDefault();
    if (!currentMidiData) return;
    document.activeElement?.blur();
    
    if (selectedTrackIndices.size === 0) { showToast('请至少选择一个音轨！', 'error'); return; }

    convertBtn.disabled = true; 
    convertBtn.textContent = 'AI 正在提取中...';
    resultCard.style.display = 'none';
    logDiv.style.display = 'block'; 
    logDiv.textContent = '';
    
    const isMelody = (selectedMode === 'fast');
    statusDiv.textContent = isMelody ? '正在加载 SkyFast...' : '正在加载 SkyPro...';

    setTimeout(async () => {
        try {
            let rawNotes = [];
            const selectedArr = Array.from(selectedTrackIndices);
            let tracksToProcess = currentMidiData.tracks.filter((t, i) => selectedArr.includes(i));

            tracksToProcess.forEach(track => {
                track.notes.forEach(note => {
                    if (note.duration * 1000 > 1000) return;
                    rawNotes.push({ time: Math.round(note.time * 1000), midi: note.midi, velocity: note.velocity });
                });
            });

            rawNotes.sort((a, b) => a.time - b.time);
            
            let pitches = rawNotes.map(n => n.midi).sort((a, b) => a - b);
            let medianPitch = pitches[Math.floor(pitches.length / 2)];
            const shift = Math.round((68 - medianPitch) / 12) * 12;

            let mappedNotes = rawNotes.map(n => ({
                time: n.time, key: mapPitchToSky(n.midi, shift), velocity: n.velocity, origMidi: n.midi
            }));

            logDiv.textContent += `[1] 原始识别: ${rawNotes.length} 个\n`;

            let processedNotes = [];
            if (isMelody) {
                const module = await import('../OneStar_Models/OneStar-SkyFast.js');
                processedNotes = module.process(mappedNotes, (msg) => logDiv.textContent += msg + '\n');
            } else if (selectedMode === 'fastpro') {
                const module = await import('../OneStar_Models/OneStar-SkyFastPro.js');
                processedNotes = module.process(mappedNotes, (msg) => logDiv.textContent += msg + '\n');
            } else {
                const module = await import('../OneStar_Models/OneStar-SkyPro.js');
                processedNotes = module.process(mappedNotes, (msg) => logDiv.textContent += msg + '\n');
            }

            let allNotes = [];
            if (selectedMode === 'fastpro') {
                allNotes = processedNotes.map(n => ({ time: n.time, key: n.key }));
            } else {
                let finalNotes = [], lastT = -9999;
                processedNotes.forEach(n => {
                    if (n.time - lastT < 15) {
                         let prev = finalNotes[finalNotes.length - 1];
                         if (prev && !prev.keys.includes(n.key)) prev.keys.push(n.key);
                    } else {
                         finalNotes.push({ time: n.time, keys: [n.key] });
                         lastT = n.time;
                    }
                });
                finalNotes.forEach(g => g.keys.forEach(k => allNotes.push({ time: g.time, key: k })));
            }

            let modelName = isMelody ? "SkyFast" : (selectedMode === 'fastpro' ? "SkyFastPro" : "SkyPro");
            const baseSongName = document.getElementById('songNameInput').value.trim() || '未命名';
            const finalSongName = `${baseSongName} || ${modelName}`; 
            const finalAuthor = document.getElementById('authorInput').value.trim() || '未知作者';
            const finalTranscriber = document.getElementById('transcriberInput').value.trim() || '_OneStar';
            const originalBpm = currentMidiData.header.tempos.length > 0 ? currentMidiData.header.tempos[0].bpm : 120;
            const studioSpeed = Math.min(4000, Math.max(100, Math.round(originalBpm * (700 / 120))));

            const skyStudioData = [{
                name: finalSongName, author: finalAuthor, transcribedBy: finalTranscriber,
                isComposed: false, bpm: studioSpeed, bitsPerPage: 16, pitchLevel: 0, isEncrypted: false,
                songNotes: allNotes, model: modelName 
            }];

            pendingTxtContent = JSON.stringify(skyStudioData);

            document.getElementById('stepFinalNotes').textContent = allNotes.length;
            document.getElementById('outDuration').textContent = ((allNotes[allNotes.length-1].time - allNotes[0].time)/1000).toFixed(1)+'s';
            document.getElementById('outSpeed').textContent = studioSpeed;
            document.getElementById('outShift').textContent = (shift > 0 ? '+' : '') + shift;
            
            resultCard.style.display = 'block';
            statusDiv.textContent = '✅ 提取完成！';
            
            const isApp = typeof window.AndroidBridge !== 'undefined';
            document.getElementById('appActions').style.display = isApp ? 'flex' : 'none';
            document.getElementById('browserActions').style.display = isApp ? 'none' : 'block';

            if (isApp) {
                btnSendApp.onclick = () => {
                    const payload = { name: finalSongName, author: finalAuthor, model: modelName, notes: allNotes, fullJson: pendingTxtContent, targetFolder: "AppSheets" };
                    window.AndroidBridge.startPlay(JSON.stringify(payload));
                };
                btnSaveLocal.onclick = () => {
                    const payload = { name: finalSongName, author: finalAuthor, fullJson: pendingTxtContent, targetFolder: "LocalSheets" };
                    window.AndroidBridge.saveLocal(JSON.stringify(payload));
                };
            } else {
                document.getElementById('btnDownload').onclick = () => {
                    const blob = new Blob([pendingTxtContent], { type: 'text/plain;charset=utf-8' });
                    const a = document.createElement('a');
                    a.href = URL.createObjectURL(blob);
                    a.download = `${finalSongName} - ${finalAuthor}.txt`;
                    a.click();
                };
            }
        } catch (err) {
            statusDiv.textContent = '❌ 转换出错';
            logDiv.textContent += '错误: ' + err.message;
            showToast(err.message, 'error');
        } finally {
            convertBtn.disabled = false;
            convertBtn.textContent = '✨ 启动 AI 深度提取';
        }
    }, 50);
});


// ==================== 视图 2：图片识谱逻辑 ====================
// 🌟 原生图片选择器调用
document.getElementById('btnOcrPick').addEventListener('click', () => {
    if (typeof window.AndroidBridge !== 'undefined' && window.AndroidBridge.openImagePicker) {
        window.AndroidBridge.openImagePicker();
    } else {
        showToast('请在 App 内使用', 'error');
    }
});

// 🌟 接收原生的 OCR 识别结果
window.onImageTextRecognized = function(text) {
    const ocrStatus = document.getElementById('ocrStatus');
    ocrStatus.textContent = "正在解析文本...";
    
    const cleanText = text.replace(/[^1-7\s']/g, '');
    const regex = /([1-7])([']?)/g;
    const BASE_MIDI = { '1': 60, '2': 62, '3': 64, '4': 65, '5': 67, '6': 69, '7': 71 };
    
    let parsedNotes = [];
    let currentTime = 0;
    let match;
    
    while ((match = regex.exec(cleanText)) !== null) {
        let midi = BASE_MIDI[match[1]];
        if (match[2] === "'") midi += 12;
        parsedNotes.push({ time: currentTime, midi: midi, velocity: 0.8 });
        currentTime += 400;
    }

    if (parsedNotes.length === 0) {
        ocrStatus.textContent = "❌ 未能识别出有效数字";
        return;
    }

    ocrStatus.textContent = `✅ 识别到 ${parsedNotes.length} 个音符`;
    document.getElementById('ocrNotesCount').textContent = parsedNotes.length;
    document.getElementById('ocrResultCard').style.display = 'block';

    document.getElementById('btnOcrConvert').onclick = async () => {
        try {
            const module = await import('../OneStarModels_Ocr/OneStar-SkyOcr.js');
            const processed = module.process(parsedNotes, console.log);

            const finalNotes = processed.map(n => ({
                time: n.time,
                key: mapPitchToSky(n.midi)
            }));

            const skyData = [{
                name: "图片识谱 || SkyOcr", author: "OneStar", transcribedBy: "_OneStar",
                bpm: 240, songNotes: finalNotes
            }];

            if (window.AndroidBridge && window.AndroidBridge.startPlay) {
                const payload = {
                    name: "图片识谱 || SkyOcr", author: "OneStar", model: "SkyOcr",
                    notes: finalNotes, fullJson: JSON.stringify(skyData), targetFolder: "AppSheets"
                };
                window.AndroidBridge.startPlay(JSON.stringify(payload));
                showToast('🚀 已发送至悬浮窗', 'success');
            }
        } catch (e) {
            showToast('转换失败: ' + e.message, 'error');
        }
    };
};