import { Midi } from '@tonejs/midi';
import { showToast } from './ui.js';
import { openSheetImagePreview } from './sheet-image.js';

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

function resolveActiveModel(mode) {
    const registry = window.SkyMusicModels;
    if (registry && typeof registry.getByMode === 'function') {
        const found = registry.getByMode(mode);
        if (found && typeof found.process === 'function') return found;
    }
    if (registry && registry.map && registry.map[mode] && typeof registry.map[mode].process === 'function') {
        return registry.map[mode];
    }
    if (registry && registry.selected && typeof registry.selected.process === 'function') {
        return registry.selected;
    }
    if (registry && Array.isArray(registry.list) && registry.list.length > 0) {
        return registry.list[0];
    }
    return null;
}

function applyLongNoteFilterIfEnabled(notes, model, logFn) {
    const shouldFilter = Boolean(model && model.filterLongNotes === true);
    if (!shouldFilter) {
        if (typeof logFn === 'function') {
            logFn(`[2] 自动检测 filterLongNotes=false：保留全部长音（${notes.length} 个）`);
        }
        return notes;
    }

    const maxDurMs = (typeof model.maxDurationMs === 'number' && model.maxDurationMs > 0)
        ? model.maxDurationMs
        : 1000;

    let removedCount = 0;
    const kept = notes.filter(n => {
        const durMs = typeof n.durationMs === 'number'
            ? n.durationMs
            : (typeof n.duration === 'number' ? n.duration * 1000 : 0);
        if (durMs > maxDurMs) {
            removedCount++;
            return false;
        }
        return true;
    });

    if (typeof logFn === 'function') {
        logFn(`[2] 自动检测 filterLongNotes=true（>${maxDurMs}ms）：剔除长音 -${removedCount} 个，剩余 ${kept.length} 个`);
    }

    return kept;
}

function ensureSheetImageButton(container, onClick) {
    if (!container) return null;
    let btn = container.querySelector('[data-role="sheet-image"]');
    if (!btn) {
        btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'action-btn btn-image';
        btn.setAttribute('data-role', 'sheet-image');
        btn.textContent = '▣ 生成谱面图片';
        container.appendChild(btn);
    }
    btn.onclick = (e) => {
        e.preventDefault();
        onClick();
    };
    return btn;
}

export function initMidi() {
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

    // 最近一次转换的上下文，供「生成谱面图片」复用
    let lastConvertContext = null;

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

    const modelContainer = document.getElementById('modelContainer');
    if (modelContainer) {
        modelContainer.addEventListener('click', (e) => {
            const card = e.target.closest('.model-card');
            if (!card) return;
            const mode = card.dataset.mode;
            if (window.SkyMusicModels?.map?.[mode]) {
                window.SkyMusicModels.selected = window.SkyMusicModels.map[mode];
            }
            hideResultSmoothly();
            logDiv.style.display = 'none';
            statusDiv.textContent = `已切换至 ${card.querySelector('.model-name')?.textContent || mode}，准备就绪`;
        });
    }

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
        const currentMode = activeCard ? activeCard.getAttribute('data-mode') : (window.SkyMusicModels?.selected?.mode || 'fastpro');
        const activeModel = resolveActiveModel(currentMode);

        if (!activeModel || typeof activeModel.process !== 'function') {
            showToast('未找到可用的提取模型', 'error');
            return;
        }

        const modelName = activeModel.shortName || activeModel.name.replace(/^OneStar-/i, '');

        convertBtn.disabled = true;
        convertBtn.querySelector('.btn-text').textContent = '正在提取中...';

        hideResultSmoothly();
        logDiv.style.display = 'block';
        logDiv.textContent = '';

        statusDiv.textContent = `正在使用 ${activeModel.name}...`;

        setTimeout(() => {
            try {
                let rawNotes = [];
                let extractedCount = 0;

                const selectedArr = Array.from(selectedTrackIndices);
                let tracksToProcess = currentMidiData.tracks.filter((t, i) => selectedArr.includes(i));

                tracksToProcess.forEach(track => {
                    track.notes.forEach(note => {
                        extractedCount++;
                        rawNotes.push({
                            time: Math.round(note.time * 1000),
                            duration: note.duration || 0,
                            midi: note.midi,
                            velocity: note.velocity
                        });
                    });
                });

                if (rawNotes.length === 0) throw new Error('无有效音符');

                logDiv.textContent += `[1] 原始识别: ${extractedCount} 个\n`;

                const logger = (msg) => logDiv.textContent += msg + '\n';
                const filteredRawNotes = applyLongNoteFilterIfEnabled(rawNotes, activeModel, logger);

                if (filteredRawNotes.length === 0) throw new Error('过滤长音后无有效音符');

                filteredRawNotes.sort((a, b) => a.time - b.time);

                let pitches = filteredRawNotes.map(n => n.midi).sort((a, b) => a - b);
                let medianPitch = pitches[Math.floor(pitches.length / 2)];
                const shift = Math.round((68 - medianPitch) / 12) * 12;

                let mappedNotes = filteredRawNotes.map(n => ({
                    time: n.time,
                    duration: n.duration,
                    key: mapPitchToSky(n.midi, shift),
                    velocity: n.velocity,
                    origMidi: n.midi
                }));

                let processedNotes = activeModel.process(mappedNotes, logger);

                const mergeWindowMs = (typeof activeModel.mergeChordWindowMs === 'number' && activeModel.mergeChordWindowMs >= 0)
                    ? activeModel.mergeChordWindowMs
                    : 15;

                let allNotes = [];
                if (mergeWindowMs <= 0) {
                    // 零合并模式（如 SkyRaw）：1:1 完整保留模型输出的每一个音符
                    allNotes = processedNotes.map(n => ({ time: n.time, key: n.key }));
                } else {
                    let finalNotes = [], lastT = -9999;
                    processedNotes.forEach(n => {
                        if (n.time - lastT < mergeWindowMs) {
                            let prev = finalNotes[finalNotes.length - 1];
                            if (prev && !prev.keys.includes(n.key)) prev.keys.push(n.key);
                        } else {
                            finalNotes.push({ time: n.time, keys: [n.key] });
                            lastT = n.time;
                        }
                    });
                    finalNotes.forEach(g => g.keys.forEach(k => allNotes.push({ time: g.time, key: k })));
                }

                logDiv.textContent += `✅ 最终输出音符: ${allNotes.length} 个\n`;

                let originalBpm = currentMidiData.header.tempos.length > 0 ? currentMidiData.header.tempos[0].bpm : 120;
                let studioSpeed = Math.round(originalBpm * (700 / 120));
                if (studioSpeed < 100) studioSpeed = 100;
                if (studioSpeed > 4000) studioSpeed = 4000;

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

                // 记录本次转换上下文，供「生成谱面图片」使用
                    lastConvertContext = {
                    sheet: skyStudioData[0],
                    songName: finalSongName,
                    baseSongName: baseSongName,
                    author: finalAuthor,
                    model: modelName,
                    transcriber: finalTranscriber,
                    bpm: studioSpeed,
                    toleranceMs: mergeWindowMs > 0 ? mergeWindowMs : 25
                };



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
                const appActions = document.getElementById('appActions');
                const browserActions = document.getElementById('browserActions');
                appActions.style.display = isApp ? 'flex' : 'none';
                browserActions.style.display = isApp ? 'none' : 'block';

                // 生成谱面图片：App 内与浏览器内都可用
                             const openImage = () => {
                    if (!lastConvertContext) {
                        showToast('请先完成一次转谱', 'error');
                        return;
                    }
                                        openSheetImagePreview(lastConvertContext.sheet, {
                        songName: lastConvertContext.songName,
                        author: lastConvertContext.author,
                        model: lastConvertContext.model,
                        transcriber: lastConvertContext.transcriber,
                        bpm: lastConvertContext.bpm,
                        keyCount: 15,
                        toleranceMs: lastConvertContext.toleranceMs
                    });

                };


                ensureSheetImageButton(appActions, openImage);
                ensureSheetImageButton(browserActions, openImage);

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
