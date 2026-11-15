// js/main.js
import { Midi } from '@tonejs/midi';

function showToast(message, type = 'info') {
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    document.getElementById('toast-container').appendChild(toast);
    setTimeout(() => toast.remove(), 3000);
}

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

// ==========================================
// 🌟 核心：供原生 MidiPickerActivity 调用的全局函数
// ==========================================
window.onFileSelectedFromAndroid = function(base64Data, fileName) {
    try {
        const binaryString = atob(base64Data);
        const len = binaryString.length;
        const bytes = new Uint8Array(len);
        for (let i = 0; i < len; i++) {
            bytes[i] = binaryString.charCodeAt(i);
        }
        // 直接复用解析逻辑
        processMidiData(bytes.buffer, fileName);
    } catch (err) {
        console.error("原生文件解码失败", err);
        showToast('文件解析失败，请重试', 'error');
    }
};

// 模型切换
document.querySelectorAll('.model-card').forEach(card => {
    card.addEventListener('click', () => {
        if(card.classList.contains('active')) return;
        document.querySelectorAll('.model-card').forEach(c => c.classList.remove('active'));
        card.classList.add('active');
        selectedMode = card.getAttribute('data-mode');
        resultCard.style.display = 'none';
        logDiv.style.display = 'none';
        setStatus('准备就绪');
    });
});

function log(msg) { logDiv.textContent += msg + '\n'; logDiv.scrollTop = logDiv.scrollHeight; }
function setStatus(msg) { statusDiv.textContent = msg; }

// 拖拽与点击上传监听
['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => dropZone.addEventListener(eventName, (e) => { e.preventDefault(); e.stopPropagation(); }, false));
['dragenter', 'dragover'].forEach(eventName => dropZone.addEventListener(eventName, () => dropZone.classList.add('dragover'), false));
['dragleave', 'drop'].forEach(eventName => dropZone.addEventListener(eventName, () => dropZone.classList.remove('dragover'), false));
dropZone.addEventListener('drop', (e) => { 
    if (e.dataTransfer.files.length) { 
        midiInput.files = e.dataTransfer.files; 
        handleFileSelect(); 
    } 
});

midiInput.addEventListener('change', handleFileSelect);

// ==========================================
// 🌟 文件选择入口
// ==========================================
async function handleFileSelect() {
    // 如果在 App 内，由于原生拦截了 input 的 click 事件，其实不会走到这里
    // 这里主要保留给浏览器环境使用
    const file = midiInput.files[0];
    if (!file) return;
    const arrayBuffer = await file.arrayBuffer();
    processMidiData(arrayBuffer, file.name);
}

// ==========================================
// 🌟 核心解析逻辑（供原生和浏览器共用）
// ==========================================
async function processMidiData(arrayBuffer, fileName) {
    if (!arrayBuffer || arrayBuffer.byteLength === 0) {
        showToast('文件内容为空', 'error');
        return;
    }

    if (!fileName.toLowerCase().endsWith('.mid') && !fileName.toLowerCase().endsWith('.midi')) {
        showToast('请选择 .mid 或 .midi 文件！', 'error');
        return;
    }

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

    setStatus('正在读取文件...');
    configPanel.style.display = 'none';
    resultCard.style.display = 'none';
    logDiv.style.display = 'none';
    logDiv.textContent = '';

    try {
        let midiData;
        try {
            midiData = new Midi(arrayBuffer);
        } catch (midiErr) {
            throw new Error('MIDI 解析失败，可能文件已损坏或不是标准格式');
        }

        if (!midiData || !midiData.tracks) throw new Error('无法读取音轨数据');

        currentMidiData = midiData;
        
        trackListContainer.innerHTML = '';
        selectedTrackIndices.clear();
        let totalNotes = 0;
        let validTrackCount = 0;

        currentMidiData.tracks.forEach((track, index) => {
            if (track.notes.length > 0) {
                validTrackCount++;
                totalNotes += track.notes.length;
                let displayName = track.name && track.name.trim() !== "" ? track.name.trim() : `轨道 ${index + 1}`;
                
                if (index !== 9 && selectedTrackIndices.size < 2) {
                    selectedTrackIndices.add(index);
                }

                const item = document.createElement('div');
                item.className = `track-item ${selectedTrackIndices.has(index) ? 'active' : ''}`;
                item.dataset.index = index;
                
                item.innerHTML = `
                    <div class="track-info">
                        <span class="track-name" title="${displayName}">${displayName}</span>
                        <span class="track-notes">${track.notes.length} 音符</span>
                    </div>
                    <div class="check-circle"></div>
                `;
                
                item.addEventListener('click', () => {
                    if (selectedTrackIndices.has(index)) {
                        selectedTrackIndices.delete(index);
                        item.classList.remove('active');
                    } else {
                        selectedTrackIndices.add(index);
                        item.classList.add('active');
                    }
                });

                trackListContainer.appendChild(item);
            }
        });

        document.getElementById('infoTracks').textContent = validTrackCount;
        document.getElementById('infoNotes').textContent = totalNotes;

        configPanel.style.display = 'block';
        convertBtn.disabled = false; 
        setStatus('准备就绪');
        
    } catch (err) {
        console.error(err);
        setStatus('❌ 解析失败');
        showToast('解析失败: ' + err.message, 'error');
        dropZone.classList.remove('has-file'); 
        dropText.textContent = "点击选择或拖拽 MIDI 文件";
    }
}

// ==========================================
// 🌟 提取转换逻辑
// ==========================================
const KEY_MAP = { 60: "1Key0", 62: "1Key1", 64: "1Key2", 65: "1Key3", 67: "1Key4", 69: "1Key5", 71: "1Key6", 72: "1Key7", 74: "1Key8", 76: "1Key9" };
const VALID_KEYS = [60,62,64,65,67,69,71,72,74,76];

function calculateBestShift(notes) {
    if (notes.length === 0) return 0;
    const pitches = notes.map(n => n.midi).sort((a, b) => a - b);
    const medianPitch = pitches[Math.floor(pitches.length / 2)];
    return Math.round((68 - medianPitch) / 12) * 12;
}

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

convertBtn.addEventListener('click', async (e) => {
    e.preventDefault();
    
    if (!currentMidiData) return;
    document.activeElement?.blur();
    
    if (selectedTrackIndices.size === 0) {
        showToast('请至少选择一个音轨！', 'error');
        return;
    }

    convertBtn.disabled = true; 
    convertBtn.textContent = 'AI 正在提取中...';
    resultCard.style.display = 'none';
    logDiv.style.display = 'block'; 
    logDiv.textContent = '';
    
    const isMelody = (selectedMode === 'fast');
    setStatus(isMelody ? '正在加载 SkyFast...' : '正在加载 SkyPro...');

    setTimeout(async () => {
        try {
            let rawNotes = [];
            let extractedCount = 0;
            let longNoteCount = 0;

            const selectedArr = Array.from(selectedTrackIndices);
            let tracksToProcess = currentMidiData.tracks.filter((t, i) => selectedArr.includes(i));

            tracksToProcess.forEach(track => {
                track.notes.forEach(note => {
                    extractedCount++;
                    if (note.duration * 1000 > 1000) {
                        longNoteCount++;
                        return;
                    }
                    rawNotes.push({ time: Math.round(note.time * 1000), midi: note.midi, velocity: note.velocity });
                });
            });

            if (rawNotes.length === 0) throw new Error('无有效音符');

            rawNotes.sort((a, b) => a.time - b.time);
            const shift = calculateBestShift(rawNotes);
            
            let mappedNotes = rawNotes.map(n => ({
                time: n.time, key: mapPitchToSky(n.midi, shift), velocity: n.velocity, origMidi: n.midi
            }));

            log(`[1] 原始识别: ${extractedCount} 个`);
            log(`[2] 剔除长音: -${longNoteCount} 个`);

            let processedNotes = [];

            if (isMelody) {
                const module = await import('../OneStar_Models/OneStar-SkyFast.js');
                processedNotes = module.process(mappedNotes, log);
            } else if (selectedMode === 'fastpro') {
                const module = await import('../OneStar_Models/OneStar-SkyFastPro.js');
                processedNotes = module.process(mappedNotes, log);
            } else {
                const module = await import('../OneStar_Models/OneStar-SkyPro.js');
                processedNotes = module.process(mappedNotes, log);
            }

            let allNotes = [];
            if (selectedMode === 'fastpro') {
                allNotes = processedNotes.map(n => ({ time: n.time, key: n.key }));
            } else {
                let finalNotes = [];
                let lastT = -9999;
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

            log(`✅ 最终输出音符: ${allNotes.length} 个\n`);

            let originalBpm = currentMidiData.header.tempos.length > 0 ? currentMidiData.header.tempos[0].bpm : 120;
            let studioSpeed = Math.round(originalBpm * (700 / 120));
            if (studioSpeed < 100) studioSpeed = 100;
            if (studioSpeed > 4000) studioSpeed = 4000;

            let modelName = "SkyPro";
            if (isMelody) modelName = "SkyFast";
            else if (selectedMode === 'fastpro') modelName = "SkyFastPro";

            const baseSongName = document.getElementById('songNameInput').value.trim() || '未命名曲目';
            const finalSongName = `${baseSongName} || ${modelName}`; 
            
            const finalOriginalAuthor = document.getElementById('authorInput').value.trim() || '未知作者';
            const finalTranscriber = document.getElementById('transcriberInput').value.trim() || '_OneStar';

            const skyStudioData = [{
                name: finalSongName,
                author: finalOriginalAuthor,        
                transcribedBy: finalTranscriber,    
                isComposed: false,
                bpm: studioSpeed,
                bitsPerPage: 16,
                pitchLevel: 0,
                isEncrypted: false,
                songNotes: allNotes,
                model: modelName 
            }];

            pendingTxtContent = JSON.stringify(skyStudioData);

            const firstNoteTime = allNotes[0].time;
            const lastNoteTime = allNotes[allNotes.length - 1].time;
            const totalDurationSec = ((lastNoteTime - firstNoteTime) / 1000).toFixed(1);

            document.getElementById('stepFinalNotes').textContent = allNotes.length;
            document.getElementById('outDuration').textContent = `${totalDurationSec}s`;
            document.getElementById('outSpeed').textContent = studioSpeed;
            document.getElementById('outShift').textContent = (shift > 0 ? '+' : '') + shift;
            
            resultCard.style.display = 'block';
            setStatus('✅ 提取完成！');
            
            setTimeout(() => resultCard.scrollIntoView({ behavior: 'smooth', block: 'center' }), 100);

            const isApp = typeof window.AndroidBridge !== 'undefined';
            document.getElementById('appActions').style.display = isApp ? 'flex' : 'none';
            document.getElementById('browserActions').style.display = isApp ? 'none' : 'block';

            if (isApp) {
                btnSendApp.onclick = () => {
                    const rawNotesOnly = allNotes.map(n => ({ time: n.time, key: n.key }));
                    const payload = {
                        name: finalSongName,
                        author: finalOriginalAuthor,
                        transcriber: finalTranscriber,
                        model: modelName,
                        notes: rawNotesOnly,
                        fullJson: pendingTxtContent,
                        targetFolder: "AppSheets"
                    };
                    window.AndroidBridge.startPlay(JSON.stringify(payload));
                };

                btnSaveLocal.onclick = () => {
                    const payload = {
                        name: finalSongName,
                        author: finalOriginalAuthor,
                        fullJson: pendingTxtContent,
                        targetFolder: "LocalSheets"
                    };
                    window.AndroidBridge.saveLocal(JSON.stringify(payload));
                };
            } else {
                document.getElementById('btnDownload').onclick = () => {
                    const blob = new Blob([pendingTxtContent], { type: 'text/plain;charset=utf-8' });
                    const url = URL.createObjectURL(blob);
                    const a = document.createElement('a');
                    a.href = url;
                    a.download = `${finalSongName} - ${finalOriginalAuthor}.txt`;
                    a.click();
                    URL.revokeObjectURL(url);
                    showToast('已触发浏览器下载', 'success');
                };
            }

        } catch (err) {
            console.error(err);
            setStatus('❌ 转换出错');
            log('错误: ' + err.message);
            showToast(err.message, 'error');
        } finally {
            convertBtn.disabled = false;
            convertBtn.textContent = '✨ 启动 AI 深度提取';
        }
    }, 50);
});