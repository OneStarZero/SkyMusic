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

    // 🌟 接收原生的图片临时文件路径
    window.onImagePathReceived = async function(imagePath) {
        const ocrStatus = document.getElementById('ocrStatus');
        ocrStatus.textContent = "正在通过云端 AI 识别(需联网)...";

        try {
            // WebView 没有权限直接 fetch 本地 file，必须用 XHR 读成 Blob
            const base64Image = await readLocalFileAsBase64(imagePath);

            if (!base64Image) {
                ocrStatus.textContent = "❌ 本地图片读取失败";
                return;
            }

            const formData = new FormData();
            formData.append("base64Image", "data:image/jpeg;base64," + base64Image);
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

// 用 XHR 读取本地文件并转 Base64
function readLocalFileAsBase64(path) {
    return new Promise((resolve) => {
        const xhr = new XMLHttpRequest();
        xhr.open('GET', 'file://' + path, true);
        xhr.responseType = 'blob';
        xhr.onload = () => {
            if (xhr.status === 200 || xhr.status === 0) {
                const reader = new FileReader();
                reader.onloadend = () => {
                    const base64data = reader.result.split(',')[1];
                    resolve(base64data);
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

function processOcrText(text) {
    const ocrStatus = document.getElementById('ocrStatus');
    const cleanText = text.replace(/[^1-7]/g, '');
    const BASE_MIDI = { '1': 60, '2': 62, '3': 64, '4': 65, '5': 67, '6': 69, '7': 71 };
    
    let parsedNotes = [];
    let currentTime = 0;

    for (let char of cleanText) {
        parsedNotes.push({ time: currentTime, midi: BASE_MIDI[char], velocity: 0.8 });
        currentTime += 350; 
    }

    if (parsedNotes.length === 0) {
        ocrStatus.textContent = "❌ 未能识别出有效数字";
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