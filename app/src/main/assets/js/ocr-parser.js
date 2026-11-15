import { showToast } from './ui.js';
import { mapPitchToSky } from './midi-parser.js';

export function initOcr() {
    document.getElementById('btnOcrPick').addEventListener('click', () => {
        if (typeof window.AndroidBridge !== 'undefined' && window.AndroidBridge.openImagePicker) {
            window.AndroidBridge.openImagePicker();
        }
    });

    window.onImagePathReceived = async function(imagePath) {
        const ocrStatus = document.getElementById('ocrStatus');
        ocrStatus.textContent = "正在分析图片...";

        const img = new Image();
        img.src = 'file://' + imagePath;
        
        img.onload = async () => {
            try {
                // 策略 1：尝试色块网格扫描（图2那种）
                const isGrid = await scanGrid(img, ocrStatus);
                if (isGrid) return;

                // 策略 2：如果不是色块图，走云端 OCR 解析数字简谱（图1那种）
                ocrStatus.textContent = "正在通过云端 AI 解析简谱...";
                const canvas = document.createElement('canvas');
                const ctx = canvas.getContext('2d');
                canvas.width = img.width;
                canvas.height = img.height;
                ctx.drawImage(img, 0, 0);
                const croppedBase64 = canvas.toDataURL('image/jpeg', 0.85).split(',')[1];

                const formData = new FormData();
                formData.append("base64Image", "data:image/jpeg;base64," + croppedBase64);
                formData.append("language", "chs");
                formData.append("apikey", "helloworld");

                const res = await fetch('https://api.ocr.space/parse/image', { method: 'POST', body: formData });
                const data = await res.json();

                if (data && !data.IsErroredOnProcessing && data.ParsedResults && data.ParsedResults.length > 0) {
                    const text = data.ParsedResults[0].ParsedText;
                    processNumberScore(text);
                } else {
                    ocrStatus.textContent = "❌ 云端识别失败";
                }
            } catch (e) {
                ocrStatus.textContent = "❌ 处理出错";
            }
        };
        img.onerror = () => { ocrStatus.textContent = "❌ 无法读取图片"; };
    };
}

// 🌟 优化后的色块扫描，精准度极大提升，不再生成固定数量的假音
function scanGrid(img, ocrStatus) {
    return new Promise((resolve) => {
        const canvas = document.createElement('canvas');
        const ctx = canvas.getContext('2d');
        canvas.width = img.width;
        canvas.height = img.height;
        ctx.drawImage(img, 0, 0);

        const imgData = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
        let detectedBlocks = [];
        const cols = 5;
        const colW = canvas.width / cols;

        for (let y = 0; y < canvas.height; y += 2) {
            for (let x = 0; x < canvas.width; x += 2) {
                const idx = (y * canvas.width + x) * 4;
                const r = imgData[idx], g = imgData[idx+1], b = imgData[idx+2];
                
                // 剔除纯黑、纯白，只抓有颜色的方块
                if ((r > 150 || g > 150 || b > 150) && !(r > 240 && g > 240 && b > 240)) {
                    let colIdx = Math.floor(x / colW);
                    let isDup = detectedBlocks.find(b => b.col === colIdx && Math.abs(b.y - y) < 20);
                    if (!isDup) detectedBlocks.push({ col: colIdx, y: y });
                }
            }
        }

        if (detectedBlocks.length > 10) {
            detectedBlocks.sort((a, b) => a.y - b.y);
            let notes = [];
            let maxY = canvas.height;
            detectedBlocks.forEach((b, index) => {
                let row = Math.floor((b.y / maxY) * 3); 
                row = Math.min(2, Math.max(0, row));
                let keyIndex = (2 - row) * 5 + b.col;
                notes.push({ time: index * 180, key: `1Key${keyIndex}` });
            });

            ocrStatus.textContent = `✅ 色块解析完成，共 ${notes.length} 个音符`;
            sendOcrToApp(notes);
            resolve(true);
        } else {
            resolve(false);
        }
    });
}

function processNumberScore(text) {
    const ocrStatus = document.getElementById('ocrStatus');
    const cleanText = text.replace(/[^1-7]/g, '');
    const BASE_MIDI = { '1': 60, '2': 62, '3': 64, '4': 65, '5': 67, '6': 69, '7': 71 };
    let parsedNotes = [];
    let currentTime = 0;

    for (let char of cleanText) {
        let midi = BASE_MIDI[char];
        if (midi) {
            parsedNotes.push({ time: currentTime, midi: midi, velocity: 0.8 });
            currentTime += 350; 
        }
    }

    if (parsedNotes.length < 10) {
        ocrStatus.textContent = "❌ 未识别到足够的简谱数字";
        return;
    }
    
    ocrStatus.textContent = `✅ 简谱解析成功，共 ${parsedNotes.length} 个音符`;
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
    const skyData = [{ name: "图片识谱 || SkyOcr", songNotes: notes }];
    if (window.AndroidBridge && window.AndroidBridge.startPlay) {
        const payload = {
            name: "图片识谱 || SkyOcr", author: "OneStar", model: "SkyOcr",
            bpm: "240", notes: notes, fullJson: JSON.stringify(skyData), targetFolder: "AppSheets"
        };
        window.AndroidBridge.startPlay(JSON.stringify(payload));
        showToast('🚀 已发送至悬浮窗', 'success');
    }
}