// js/ocr-parser.js
import { showToast } from './ui.js';
import { mapPitchToSky } from './midi-parser.js';

export function initOcr() {
    document.getElementById('btnOcrPick').addEventListener('click', () => {
        if (typeof window.AndroidBridge !== 'undefined' && window.AndroidBridge.openImagePicker) {
            window.AndroidBridge.openImagePicker();
        } else {
            showToast('请在 App 内使用', 'error');
        }
    });

    // 接收带坐标的 OCR 数据
    window.onImageWordsReceived = function(jsonString) {
        const ocrStatus = document.getElementById('ocrStatus');
        ocrStatus.textContent = "正在解析版面坐标...";

        try {
            const words = JSON.parse(jsonString);
            
            let validNotes = [];
            
            words.forEach(w => {
                if (w.text.includes("作者") || w.text.includes("改编") || w.text.includes("B=")) return;
                
                let cleanText = w.text.replace(/i/g, "1'"); 
                const matches = cleanText.match(/[1-7]/g);
                if (matches) {
                    matches.forEach((num) => {
                        let isHigh = cleanText.includes("'") || cleanText.includes(".");
                        validNotes.push({ num: num, isHigh: isHigh, x: w.x, y: w.y });
                    });
                }
            });

            if (validNotes.length === 0) {
                ocrStatus.textContent = "❌ 未检测到简谱数字";
                return;
            }

            validNotes.sort((a, b) => a.x - b.x);
            const X_THRESHOLD = 40; 
            let groupedChords = [];
            let currentGroup = [validNotes[0]];

            for (let i = 1; i < validNotes.length; i++) {
                let note = validNotes[i];
                let lastNote = currentGroup[currentGroup.length - 1];

                if (Math.abs(note.x - lastNote.x) <= X_THRESHOLD) {
                    currentGroup.push(note);
                } else {
                    groupedChords.push({ notes: currentGroup, x: lastNote.x });
                    currentGroup = [note];
                }
            }
            if (currentGroup.length > 0) {
                groupedChords.push({ notes: currentGroup, x: currentGroup[currentGroup.length - 1].x });
            }

            const BASE_MIDI = { '1': 60, '2': 62, '3': 64, '4': 65, '5': 67, '6': 69, '7': 71 };
            let parsedNotes = [];
            let currentTime = 0;
            
            let totalDistance = groupedChords[groupedChords.length - 1].x - groupedChords[0].x;
            let avgGap = totalDistance / groupedChords.length;
            const LONG_NOTE_FACTOR = 1.5; 

            for (let i = 0; i < groupedChords.length; i++) {
                let group = groupedChords[i];
                let currentX = group.x;
                let nextX = (i < groupedChords.length - 1) ? groupedChords[i + 1].x : currentX + avgGap;
                
                let timeStep = 350; 
                let gapRatio = (nextX - currentX) / avgGap;
                
                let isLong = gapRatio >= LONG_NOTE_FACTOR;
                
                let seen = new Set();
                group.notes.forEach(n => {
                    let midi = BASE_MIDI[n.num];
                    if (n.isHigh) midi += 12; 

                    if (!seen.has(midi)) {
                        seen.add(midi);
                        
                        if (isLong) {
                            let holdBeats = Math.min(8, Math.floor(gapRatio)); 
                            for (let h = 0; h < holdBeats; h++) {
                                parsedNotes.push({ time: currentTime + (h * 80), midi: midi, velocity: 0.8 });
                            }
                        } else {
                            parsedNotes.push({ time: currentTime, midi: midi, velocity: 0.8 });
                        }
                    }
                });

                currentTime += timeStep * gapRatio; 
            }

            ocrStatus.textContent = `✅ 识别完成，提取出 ${groupedChords.length} 个节拍，共 ${parsedNotes.length} 个音`;
            showOcrResult(parsedNotes);

        } catch (err) {
            console.error(err);
            ocrStatus.textContent = "❌ 解析异常: " + err.message;
        }
    };
}

function showOcrResult(parsedNotes) {
    document.getElementById('ocrNotesCount').textContent = parsedNotes.length;
    document.getElementById('ocrResultCard').style.display = 'block';
    
    document.getElementById('btnOcrConvert').onclick = async () => {
        try {
            const module = await import('../OneStarModels_Ocr/OneStar-SkyOcr.js');
            const processed = module.process(parsedNotes, console.log);
            const finalNotes = processed.map(n => ({ time: n.time, key: mapPitchToSky(n.midi) }));
            sendOcrToApp(finalNotes);
        } catch (e) {
            showToast('转换失败: ' + e.message, 'error');
        }
    };
}

function sendOcrToApp(notes) {
    const skyData = [{
        name: "图片识谱 || SkyOcr", author: "OneStar", transcribedBy: "_OneStar",
        bpm: 240, songNotes: notes
    }];
    if (window.AndroidBridge && window.AndroidBridge.startPlay) {
        const payload = {
            name: "图片识谱 || SkyOcr", author: "OneStar", model: "SkyOcr",
            notes: notes, fullJson: JSON.stringify(skyData), targetFolder: "AppSheets"
        };
        window.AndroidBridge.startPlay(JSON.stringify(payload));
        showToast('🚀 已发送至悬浮窗', 'success');
    }
}