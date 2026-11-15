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

    // 接收原生的图片 Base64
    window.onImageBase64Received = async function(imageBase64) {
        const ocrStatus = document.getElementById('ocrStatus');
        ocrStatus.textContent = "正在通过云端 AI 识别(需联网)...";

        try {
            const formData = new FormData();
            formData.append("base64Image", "data:image/jpeg;base64," + imageBase64);
            formData.append("language", "chs"); // 中文简体
            formData.append("isOverlayRequired", "false");
            // 使用 apikey: helloworld 作为公共测试密钥
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
                ocrStatus.textContent = "❌ 云端识别失败: " + (data.ErrorMessage ? data.ErrorMessage[0] : "未知错误");
            }
        } catch (err) {
            console.error(err);
            ocrStatus.textContent = "❌ 网络请求异常，请检查网络连接";
        }
    };
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
        ocrStatus.textContent = "❌ 未能识别出数字简谱";
        return;
    }

    ocrStatus.textContent = `✅ 识别成功，共 ${parsedNotes.length} 个数字`;
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