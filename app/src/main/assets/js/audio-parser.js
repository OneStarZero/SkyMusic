import { showToast } from './ui.js';

export function initAudio() {
    const btnLocal = document.getElementById('btnLocalAudio');
    const btnCloud = document.getElementById('btnCloudAudio');
    const status = document.getElementById('audioStatus');

    if (btnLocal) {
        btnLocal.addEventListener('click', () => {
            if (typeof window.AndroidBridge !== 'undefined' && window.AndroidBridge.openAudioPicker) {
                status.textContent = "正在启动本地 AI 引擎...";
                window.AndroidBridge.openAudioPicker();
            } else {
                showToast('请在 App 内使用本地极速版', 'error');
            }
        });
    }

    if (btnCloud) {
        btnCloud.addEventListener('click', () => {
            showToast('云端满血版算力接入中，敬请期待', 'info');
        });
    }

    window.onAudioTranscribed = async function(notesJsonStr) {
        if (!status) return;
        status.textContent = "正在解析 AI 结果...";
        
        try {
            const rawNotes = JSON.parse(notesJsonStr);
            if (!rawNotes || rawNotes.length === 0) { 
                status.textContent = "❌ 未能从音频中识别出有效音符";
                return; 
            }

            status.textContent = "正在生成光遇铺面...";

            // 🌟 修改：引入重命名后的 JS 模型
            const module = await import('../OneStarModels_Media/OneStar_MusicFastBasic.js');
            const finalNotes = module.process(rawNotes, console.log);

            const bpm = "120";
            const skyData = [{ name: "音频扒谱 || OneStarMusicFastBasic", author: "OneStar", bpm: bpm, songNotes: finalNotes }];
            
            if (window.AndroidBridge && window.AndroidBridge.startPlay) {
                const payload = {
                    name: "音频扒谱 || OneStarMusicFastBasic", author: "OneStar", model: "OneStarMusicFastBasic",
                    bpm: bpm, notes: finalNotes, fullJson: JSON.stringify(skyData), targetFolder: "AppSheets"
                };
                window.AndroidBridge.startPlay(JSON.stringify(payload));
                status.textContent = `✅ 生成成功，共 ${finalNotes.length} 个音符，已发送至悬浮窗！`;
                showToast('🚀 已发送至悬浮窗', 'success');
            }
        } catch (err) { 
            console.error(err);
            status.textContent = "❌ 解析音频数据异常"; 
        }
    };
}