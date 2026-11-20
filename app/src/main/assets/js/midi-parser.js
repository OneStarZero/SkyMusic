import { Midi } from '@tonejs/midi';
import { showToast } from './ui.js';

import { process as processFast } from '../OneStarModels_MIDI/OneStar-SkyFast.js';
import { process as processPro } from '../OneStarModels_MIDI/OneStar-SkyPro.js';
import { process as processFastPro } from '../OneStarModels_MIDI/OneStar-SkyFastPro.js';

const KEY_MAP = { 60: "1Key0", 62: "1Key1", 64: "1Key2", 65: "1Key3", 67: "1Key4", 69: "1Key5", 71: "1Key6", 72: "1Key7", 74: "1Key8", 76: "1Key9" };
const VALID_KEYS = [60,62,64,65,67,69,71,72,74,76];

export function mapPitchToSky(midiPitch, shift = 0) {
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

export function initMidi() {
    const container = document.getElementById('modelContainer');
    if (container) {
        const cards = Array.from(container.querySelectorAll('.model-card'));
        cards.sort((a, b) => parseInt(a.getAttribute('data-order') || "99") - parseInt(b.getAttribute('data-order') || "99"));
        container.innerHTML = '';
        cards.forEach(card => container.appendChild(card));
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

    let currentMidiData = null;
    let pendingTxtContent = null;
    let selectedTrackIndices = new Set(); 
    let hideResultTimeout = null;

    function hideResultSmoothly() {
        if (resultCard.style.display === 'none') return;
        resultCard.classList.remove('fade-in');
        resultCard.classList.add('fade-out');
        
        clearTimeout(hideResultTimeout);
        hideResultTimeout = setTimeout(() => {
            resultCard.style.display = 'none';
            resultCard.classList.remove('fade-out');
        }, 300);
    }

    document.querySelectorAll('.model-card').forEach(card => {
        card.addEventListener('click', () => {
            if(card.classList.contains('active')) return;
            document.querySelectorAll('.model-card').forEach(c => c.classList.remove('active'));
            card.classList.add('active');
            
            hideResultSmoothly();
            logDiv.style.display = 'none';
            statusDiv.textContent = '已切换提取模型，准备就绪';
        });
    });

    dropZone.addEventListener('click', (e) => {
        if (typeof window.AndroidBridge !== 'undefined' && window.AndroidBridge.openMidiPicker) {
            e.preventDefault(); 
            window.AndroidBridge.openMidiPicker();
        }
    });

    midiInput.addEventListener('change', async () => {
        const file = midiInput.files[0];
        if (!file) return;
        const arrayBuffer = await file.arrayBuffer();
        processMidiData(arrayBuffer, file.name);
    });

    window.onMidiPathReceived = function(midiPath, fileName) {
        const xhr = new XMLHttpRequest();
        xhr.open('GET', 'file://' + midiPath, true);
        xhr.responseType = 'arraybuffer';
        xhr.onload = function() {
            if (xhr.status === 200 || xhr.status === 0) {
                processMidiData(xhr.response, fileName);
            } else {
                showToast('MIDI 文件读取失败', 'error');
            }
        };
        xhr.onerror = function() {
            showToast('MIDI 文件网络读取异常', 'error');
        };
        xhr.send();
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
        hideResultSmoothly();
        logDiv.style.display = 'none';
        logDiv.textContent = '';

        try {
            let midiData = new Midi(arrayBuffer);
            if (!midiData || !midiData.tracks) throw new Error('无法读取音轨数据');

            currentMidiData = midiData;
            trackListContainer.innerHTML = '';
            selectedTrackIndices.clear();
            let totalNotes = 0, validTrackCount = 0;
            let instrumentsSet = new Set();
            let minMidi = 127, maxMidi = 0, maxTime = 0;

            currentMidiData.tracks.forEach((track, index) => {
                if (track.notes.length > 0) {
                    validTrackCount++;
                    totalNotes += track.notes.length;
                    const instName = track.instrument.name || '未知乐器';
                    instrumentsSet.add(instName);
                    
                    let displayName = track.name && track.name.trim() !== "" ? track.name.trim() : `轨道 ${index + 1}`;

                    track.notes.forEach(n => {
                        if (n.midi < minMidi) minMidi = n.midi;
                        if (n.midi > maxMidi) maxMidi = n.midi;
                        if (n.time > maxTime) maxTime = n.time;
                    });

                    if (index !== 9) selectedTrackIndices.add(index);

                    const item = document.createElement('div');
                    item.className = `track-item ${selectedTrackIndices.has(index) ? 'active' : ''}`;
                    item.innerHTML = `<div class="track-info"><span class="track-name">${displayName}</span><span class="track-notes">${track.notes.length} 音符 - ${instName}</span></div><div class="check-circle"></div>`;
                    item.addEventListener('click', () => {
                        if (selectedTrackIndices.has(index)) { selectedTrackIndices.delete(index); item.classList.remove('active'); } 
                        else { selectedTrackIndices.add(index); item.classList.add('active'); }
                    });
                    trackListContainer.appendChild(item);
                }
            });

            const totalSeconds = Math.floor(maxTime);
            const mins = Math.floor(totalSeconds / 60), secs = totalSeconds % 60;
            let bpmVal = currentMidiData.header.tempos.length > 0 ? Math.round(currentMidiData.header.tempos[0].bpm) : 120;

            document.getElementById('infoTotalTracks').textContent = currentMidiData.tracks.length;
            document.getElementById('infoValidTracks').textContent = validTrackCount;
            document.getElementById('infoInstruments').textContent = Array.from(instrumentsSet).join(', ') || '无';
            document.getElementById('infoBpm').textContent = bpmVal;
            document.getElementById('infoDuration').textContent = `${mins}:${secs.toString().padStart(2, '0')}`;
            document.getElementById('infoPitchRange').textContent = `${minMidi} ~ ${maxMidi}`;
            document.getElementById('infoTotalNotes').textContent = totalNotes;

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

    convertBtn.addEventListener('click', (e) => {
        e.preventDefault();
        if (!currentMidiData) return;
        
        if (selectedTrackIndices.size === 0) { showToast('请至少选择一个音轨！', 'error'); return; }

        const activeCard = document.querySelector('.model-card.active');
        const currentMode = activeCard ? activeCard.getAttribute('data-mode') : 'fastpro';
        const isMelody = (currentMode === 'fast');
        const isFastPro = (currentMode === 'fastpro');

        convertBtn.disabled = true; 
        convertBtn.querySelector('.btn-text').textContent = '正在提取中...';
        
        hideResultSmoothly();
        logDiv.style.display = 'block'; 
        logDiv.textContent = '';
        
        statusDiv.textContent = isMelody ? '正在加载 SkyFast...' : (isFastPro ? '正在加载 SkyFastPro...' : '正在加载 SkyPro...');

        setTimeout(() => {
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
                
                let pitches = rawNotes.map(n => n.midi).sort((a, b) => a - b);
                let medianPitch = pitches[Math.floor(pitches.length / 2)];
                const shift = Math.round((68 - medianPitch) / 12) * 12;

                let mappedNotes = rawNotes.map(n => ({
                    time: n.time, key: mapPitchToSky(n.midi, shift), velocity: n.velocity, origMidi: n.midi
                }));

                logDiv.textContent += `[1] 原始识别: ${extractedCount} 个\n`;
                logDiv.textContent += `[2] 剔除长音: -${longNoteCount} 个\n`;

                let processedNotes = [];
                const logger = (msg) => logDiv.textContent += msg + '\n';
                
                if (isMelody) {
                    processedNotes = processFast(mappedNotes, logger);
                } else if (isFastPro) {
                    processedNotes = processFastPro(mappedNotes, logger);
                } else {
                    processedNotes = processPro(mappedNotes, logger);
                }

                let allNotes = [];
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

                logDiv.textContent += `✅ 最终输出音符: ${allNotes.length} 个\n`;

                let originalBpm = currentMidiData.header.tempos.length > 0 ? currentMidiData.header.tempos[0].bpm : 120;
                let studioSpeed = Math.round(originalBpm * (700 / 120));
                if (studioSpeed < 100) studioSpeed = 100;
                if (studioSpeed > 4000) studioSpeed = 4000;

                let modelName = isMelody ? "SkyFast" : (isFastPro ? "SkyFastPro" : "SkyPro");
                
                // 🌟 核心修复：强制清理旧后缀，保证模型名加在最后面
                let baseSongName = document.getElementById('songNameInput').value.trim() || '未命名曲目';
                if (baseSongName.includes(" || ")) {
                    baseSongName = baseSongName.split(" || ")[0].trim();
                }
                const finalSongName = `${baseSongName} || ${modelName}`; 

                const finalAuthor = document.getElementById('authorInput').value.trim() || '未知作者';
                const finalTranscriber = document.getElementById('transcriberInput').value.trim() || '_OneStar';

                const skyStudioData = [{
                    name: finalSongName, author: finalAuthor, transcribedBy: finalTranscriber,
                    isComposed: false, bpm: studioSpeed, bitsPerPage: 16, pitchLevel: 0, isEncrypted: false,
                    songNotes: allNotes, model: modelName 
                }];

                pendingTxtContent = JSON.stringify(skyStudioData);

                const firstNoteTime = allNotes[0].time;
                const lastNoteTime = allNotes[allNotes.length - 1].time;
                const totalDurationSec = ((lastNoteTime - firstNoteTime) / 1000).toFixed(1);

                document.getElementById('stepFinalNotes').textContent = allNotes.length;
                document.getElementById('outDuration').textContent = `${totalDurationSec}s`;
                document.getElementById('outSpeed').textContent = studioSpeed;
                document.getElementById('outShift').textContent = (shift > 0 ? '+' : '') + shift;
                
                document.getElementById('outSongName').textContent = finalSongName;
                document.getElementById('outAuthor').textContent = finalAuthor;
                document.getElementById('outModel').textContent = modelName;
                document.getElementById('outTranscriber').textContent = finalTranscriber;

                clearTimeout(hideResultTimeout);
                resultCard.style.display = 'block';
                resultCard.classList.remove('fade-out');
                resultCard.classList.add('fade-in');
                setTimeout(() => resultCard.classList.remove('fade-in'), 500);
                
                statusDiv.textContent = '✅ 提取完成！';
                
                setTimeout(() => resultCard.scrollIntoView({ behavior: 'smooth', block: 'center' }), 100);

                const isApp = typeof window.AndroidBridge !== 'undefined';
                document.getElementById('appActions').style.display = isApp ? 'flex' : 'none';
                document.getElementById('browserActions').style.display = isApp ? 'none' : 'block';

                if (isApp) {
                    document.getElementById('btnSendApp').onclick = () => {
                        const payload = { name: finalSongName, author: finalAuthor, model: modelName, bpm: studioSpeed, notes: allNotes, fullJson: pendingTxtContent, targetFolder: "AppSheets" };
                        window.AndroidBridge.startPlay(JSON.stringify(payload));
                    };
                    document.getElementById('btnSaveLocal').onclick = () => {
                        const payload = { name: finalSongName, author: finalAuthor, fullJson: pendingTxtContent, targetFolder: "LocalSheets" };
                        window.AndroidBridge.saveLocal(JSON.stringify(payload));
                    };
                } else {
                    document.getElementById('btnDownload').onclick = () => {
    const blob = new Blob([pendingTxtContent], { type: 'text/plain;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    
    // 🌟 浏览器下载也严格按照 歌曲名-作者 || 模型名.txt 命名
    a.download = `${baseSongName}-${finalAuthor} || ${modelName}.txt`;
    
    a.click();
};
                }

            } catch (err) {
                statusDiv.textContent = '❌ 转换出错';
                logDiv.textContent += '错误: ' + err.message;
                showToast(err.message, 'error');
            } finally {
                convertBtn.disabled = false;
                convertBtn.querySelector('.btn-text').textContent = '✨ 开始提取';
            }
        }, 50);
    });
}