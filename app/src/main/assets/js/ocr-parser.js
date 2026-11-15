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

    // 接收原生传回的 Base64 图片数据
    window.onImageBase64Received = async function(imageBase64) {
        const ocrStatus = document.getElementById('ocrStatus');
        ocrStatus.textContent = "正在分析图片结构...";

        // 策略 1：先尝试在前端进行 Canvas 像素级网格扫描（秒杀色块图）
        const isGridImage = await scanGridImage(imageBase64);
        
        if (isGridImage) {
            return; // 如果是网格图，scanGridImage 内部已经处理并发送了
        }

        // 策略 2：如果不是网格图，则调用云端 OCR 解析数字谱
        ocrStatus.textContent = "正在通过云端 AI 解析简谱...";
        try {
            const formData = new FormData();
            formData.append("base64Image", "data:image/jpeg;base64," + imageBase64);
            formData.append("language", "chs");
            formData.append("isOverlayRequired", "false");
            formData.append("apikey", "helloworld");

            const res = await fetch('https://api.ocr.space/parse/image', {
                method: 'POST',
                body: formData
            });
            const data = await res.json();

            if (data && !data.IsErroredOnProcessing && data.ParsedResults && data.ParsedResults.length > 0) {
                const text = data.ParsedResults[0].ParsedText;
                processOcrText(text);
            } else {
                ocrStatus.textContent = "❌ 云端识别失败，请重试";
            }
        } catch (err) {
            console.error(err);
            ocrStatus.textContent = "❌ 网络请求异常";
        }
    };
}

// ================= 引擎 1：Canvas 像素扫描（识别色块网格图） =================
function scanGridImage(base64) {
    return new Promise((resolve) => {
        const img = new Image();
        img.onload = () => {
            const canvas = document.createElement('canvas');
            const ctx = canvas.getContext('2d');
            canvas.width = img.width;
            canvas.height = img.height;
            ctx.drawImage(img, 0, 0);

            // 光遇色块图的特征：5 列，通常很长
            const cols = 5; 
            const rows = 15; 
            
            // 如果图片不是偏长条形，判定不是网格图
            if (img.width >= img.height) {
                resolve(false);
                return;
            }

            const cellW = img.width / cols;
            const cellH = img.height / rows;
            let noteCount = 0;
            let notes = [];

            // 扫描网格中心点是否有颜色（非白非黑）
            for (let r = 0; r < rows; r++) {
                for (let c = 0; c < cols; c++) {
                    const centerX = Math.floor(c * cellW + cellW / 2);
                    const centerY = Math.floor(r * cellH + cellH / 2);
                    
                    const pixel = ctx.getImageData(centerX, centerY, 1, 1).data;
                    const rC = pixel[0], gC = pixel[1], bC = pixel[2];
                    
                    const isWhite = rC > 240 && gC > 240 && bC > 240;
                    const isBlack = rC < 30 && gC < 30 && bC < 30;
                    
                    if (!isWhite && !isBlack) {
                        noteCount++;
                        // 假设这 5 列对应光遇的第一排键位 (0~4)，实际需根据谱面调整
                        notes.push({ time: noteCount * 200, key: `1Key${c}` });
                    }
                }
            }

            // 如果色块数量大于 10，大概率是网格图
            if (noteCount > 10) {
                document.getElementById('ocrStatus').textContent = `✅ 识别为网格图，提取 ${notes.length} 个色块`;
                sendOcrToApp(notes);
                resolve(true);
            } else {
                resolve(false);
            }
        };
        img.onerror = () => resolve(false);
        img.src = 'data:image/jpeg;base64,' + base64;
    });
}

// ================= 引擎 2：数字简谱解析 =================
function processOcrText(text) {
    const ocrStatus = document.getElementById('ocrStatus');
    
    // 剔除无关字符，保留数字和 i（i 表示高音 1）
    const cleanText = text.replace(/[^1-7i]/g, '');
    const BASE_MIDI = { '1': 60, '2': 62, '3': 64, '4': 65, '5': 67, '6': 69, '7': 71, 'i': 72 };
    
    let parsedNotes = [];
    let currentTime = 0;

    for (let char of cleanText) {
        let midi = BASE_MIDI[char];
        if (midi) {
            parsedNotes.push({ time: currentTime, midi: midi, velocity: 0.8 });
            currentTime += 350; 
        }
    }

    if (parsedNotes.length === 0) {
        ocrStatus.textContent = "❌ 未能识别出有效简谱";
        return;
    }

    ocrStatus.textContent = `✅ 简谱识别成功，共 ${parsedNotes.length} 个音符`;
    showOcrResult(parsedNotes);
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