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

    // 接收原生传回的本地文件路径
    window.onImagePathReceived = async function(imagePath) {
        const ocrStatus = document.getElementById('ocrStatus');
        ocrStatus.textContent = "正在上传图片进行深度解析...";

        try {
            // 通过 XHR 读取本地图片
            const base64Image = await readLocalFileAsBase64(imagePath);
            if (!base64Image) {
                ocrStatus.textContent = "❌ 本地图片读取失败";
                return;
            }

            // 发送到高精度的 OCR 接口
            const formData = new FormData();
            formData.append("base64Image", "data:image/jpeg;base64," + base64Image);
            formData.append("language", "chs"); // 中文/简体
            formData.append("isOverlayRequired", "false"); // 直接要纯文本，我们自己用正则拆
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
                ocrStatus.textContent = "❌ 云端识别失败，可能是图片太模糊或格式不支持";
            }
        } catch (err) {
            console.error(err);
            ocrStatus.textContent = "❌ 网络请求异常，请检查网络";
        }
    };
}

// 🌟 辅助函数：从本地路径读取文件并转为 Base64
function readLocalFileAsBase64(path) {
    return new Promise((resolve) => {
        const xhr = new XMLHttpRequest();
        xhr.open('GET', 'file://' + path, true);
        xhr.responseType = 'blob';
        xhr.onload = () => {
            if (xhr.status === 200 || xhr.status === 0) {
                const reader = new FileReader();
                reader.onloadend = () => {
                    resolve(reader.result.split(',')[1]);
                };
                reader.readAsDataURL(xhr.response);
            } else {
                resolve(null);
            }
        };
        xhr.onerror = () => resolve(null);
        xhr.send();
    });
}

// 🌟 解析云端返回的纯文本
function processOcrText(text) {
    const ocrStatus = document.getElementById('ocrStatus');
    
    // 打印一下看看云端的原始识别结果，方便排查
    console.log("【云端原始文本】:", text);

    // 简化处理：保留 1-7 以及可能代表高音的数字/符号
    // 注意：五线谱形式的简谱，可能有数字上下的点，我们可以将其转换为高音。
    // 在这里，最简单稳妥的做法就是：提取所有连续的 1-7 序列。
    const cleanText = text.replace(/[^1-7]/g, '');
    
    // 基础 MIDI 映射
    const BASE_MIDI = { '1': 60, '2': 62, '3': 64, '4': 65, '5': 67, '6': 69, '7': 71 };
    
    let parsedNotes = [];
    let currentTime = 0;
    const TIME_STEP = 350; // 每个音符间隔 350 毫秒

    // 将字符串转换为音符
    for (let char of cleanText) {
        let midi = BASE_MIDI[char];
        if (midi) {
            parsedNotes.push({ time: currentTime, midi: midi, velocity: 0.8 });
            currentTime += TIME_STEP;
        }
    }

    // 防呆机制：如果识别出来的音符数量小于 10，大概是因为识别失败（全是噪声）。
    if (parsedNotes.length < 10) {
        ocrStatus.textContent = "❌ 未识别到足够的数字简谱（请确保上传的是清晰的数字谱）";
        return;
    }

    ocrStatus.textContent = `✅ 识别成功，共 ${parsedNotes.length} 个音符`;
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
    // 构造符合 SkyStudio 格式的 JSON
    const skyData = [{
        name: "图片识谱 || SkyOcr", author: "OneStar", transcribedBy: "_OneStar",
        bpm: 240, 
        songNotes: notes
    }];
    
    // 传给原生
    if (window.AndroidBridge && window.AndroidBridge.startPlay) {
        const payload = {
            name: "图片识谱 || SkyOcr", 
            author: "OneStar", 
            model: "SkyOcr",
            bpm: "240", // 🌟 修复：明确传入 bpm 字符串
            notes: notes, 
            fullJson: JSON.stringify(skyData), 
            targetFolder: "AppSheets"
        };
        window.AndroidBridge.startPlay(JSON.stringify(payload));
        showToast('🚀 已发送至悬浮窗', 'success');
    }
}