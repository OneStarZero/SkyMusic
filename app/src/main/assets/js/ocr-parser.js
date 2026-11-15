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

    // 🌟 接收原生传回的文件路径，而不是庞大的 Base64 字符串
    window.onImagePathReceived = async function(imagePath) {
        const ocrStatus = document.getElementById('ocrStatus');
        ocrStatus.textContent = "正在分析图片结构并自动裁剪...";

        const img = new Image();
        // WebView 允许直接读取本地 file:// 路径
        img.src = 'file://' + imagePath;
        
        img.onload = async () => {
            try {
                // 1. 自动裁剪核心铺面区域
                const croppedCanvas = autoCropScore(img);
                
                // 2. 分析铺面类型
                const scoreType = detectScoreType(croppedCanvas);

                if (scoreType === 'block') {
                    ocrStatus.textContent = "检测到色块矩阵谱，正在逐格解析...";
                    parseBlockScore(croppedCanvas);
                } else if (scoreType === 'number') {
                    ocrStatus.textContent = "检测到数字简谱，正在请求云端 AI...";
                    // 数字谱必须走云端，这里临时转一下（因为已经压缩且裁剪，不会闪退）
                    const croppedBase64 = croppedCanvas.toDataURL('image/jpeg', 0.8).split(',')[1];
                    await requestCloudOcr(croppedBase64, ocrStatus);
                } else {
                    ocrStatus.textContent = "⚠️ 无法识别谱面类型";
                }
            } catch (err) {
                console.error(err);
                ocrStatus.textContent = "❌ 处理图片时出错";
            }
        };

        img.onerror = () => {
            ocrStatus.textContent = "❌ 无法读取本地图片文件";
        };
    };
}

// ================= 核心 1：智能自动裁剪 =================
function autoCropScore(img) {
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d');
    canvas.width = img.width;
    canvas.height = img.height;
    ctx.drawImage(img, 0, 0);

    // 剔除顶部 10% 和底部 5%（通常是标题和作者）
    const cropY = img.height * 0.10;
    const cropH = img.height * 0.85;
    const cropX = img.width * 0.05;
    const cropW = img.width * 0.90;

    const cropCanvas = document.createElement('canvas');
    cropCanvas.width = cropW;
    cropCanvas.height = cropH;
    const cropCtx = cropCanvas.getContext('2d');
    cropCtx.drawImage(canvas, cropX, cropY, cropW, cropH, 0, 0, cropW, cropH);
    
    return cropCanvas;
}

// ================= 核心 2：判断谱面类型 =================
function detectScoreType(canvas) {
    const ctx = canvas.getContext('2d');
    const imgData = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
    let darkPixels = 0;
    let colorfulPixels = 0;
    
    for (let i = 0; i < imgData.length; i += 40) {
        const r = imgData[i], g = imgData[i+1], b = imgData[i+2];
        
        if (r < 100 && g < 100 && b < 100) darkPixels++;
        if ((r > 150 && g < 100 && b < 100) || (b > 150 && r < 100 && g < 100)) colorfulPixels++;
    }

    if (colorfulPixels > 50 || darkPixels > imgData.length / 80) return 'block';
    return 'number';
}

// ================= 核心 3：方块矩阵解析 =================
function parseBlockScore(canvas) {
    const ctx = canvas.getContext('2d');
    const imgData = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
    
    const cols = 5;
    const colW = canvas.width / cols;
    
    let detectedBlocks = []; 

    for (let y = 0; y < canvas.height; y += 2) {
        for (let x = 0; x < canvas.width; x += 2) {
            const idx = (y * canvas.width + x) * 4;
            const r = imgData[idx], g = imgData[idx+1], b = imgData[idx+2];

            const isWhite = r > 240 && g > 240 && b > 240;
            const isBlack = r < 30 && g < 30 && b < 30;
            
            if (!isWhite && (!isBlack || (r < 100 && g < 100 && b < 100))) {
                const colIdx = Math.floor(x / colW);
                
                let isDuplicate = detectedBlocks.find(block => 
                    block.col === colIdx && Math.abs(block.y - y) < 30
                );

                if (!isDuplicate) detectedBlocks.push({ col: colIdx, y: y });
            }
        }
    }

    detectedBlocks.sort((a, b) => a.y - b.y);

    let notes = [];
    let maxY = canvas.height;
    
    detectedBlocks.forEach((block, index) => {
        let row = Math.floor((block.y / maxY) * 3); 
        row = Math.min(2, Math.max(0, row));
        let keyIndex = (2 - row) * 5 + block.col;
        notes.push({ time: index * 180, key: `1Key${keyIndex}` });
    });

    if (notes.length > 0) {
        document.getElementById('ocrStatus').textContent = `✅ 矩阵解析完成，共 ${notes.length} 个音符`;
        sendOcrToApp(notes);
    } else {
        document.getElementById('ocrStatus').textContent = "❌ 未能解析出有效色块";
    }
}

// ================= 核心 4：云端数字谱解析 =================
async function requestCloudOcr(croppedBase64, ocrStatus) {
    try {
        const formData = new FormData();
        formData.append("base64Image", "data:image/jpeg;base64," + croppedBase64);
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
            processNumberScore(text);
        } else {
            ocrStatus.textContent = "❌ 云端识别失败";
        }
    } catch (err) {
        console.error(err);
        ocrStatus.textContent = "❌ 网络请求异常";
    }
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

    if (parsedNotes.length === 0) {
        ocrStatus.textContent = "❌ 未能识别出有效数字";
        return;
    }

    ocrStatus.textContent = `✅ 数字谱解析成功，共 ${parsedNotes.length} 个音符`;
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