import { initMidi } from './midi-parser.js';
import { initRandomBackground } from './bg.js';
import { initAutoModels } from './modelRegistry.js';

function hideLoadingOverlay(loadingScreen) {
    if (!loadingScreen) return;
    loadingScreen.classList.add('hidden');
    loadingScreen.style.transition = 'opacity 0.25s ease';
    loadingScreen.style.opacity = '0';
    setTimeout(() => {
        loadingScreen.style.display = 'none';
    }, 260);
}

document.addEventListener('DOMContentLoaded', () => {
    const loadingScreen = document.getElementById('loading-screen');
    const loadStatus = document.getElementById('loadStatus');
    const loadProgressBar = document.getElementById('loadProgressBar');

    window.SkyMusicModels = {
        selected: null,
        map: {},
        list: []
    };

    function updateLoadState(text, percent) {
        if (loadStatus) loadStatus.textContent = text;
        if (loadProgressBar) loadProgressBar.style.width = percent + '%';
    }

    window.addEventListener('error', (e) => {
        updateLoadState(`脚本错误: ${e.message || e.error || '未知错误'}`, 100);
        console.error('[GlobalError]', e.error || e.message);
        hideLoadingOverlay(loadingScreen);
    });

    window.addEventListener('unhandledrejection', (e) => {
        const reason = e.reason?.message || e.reason || '未处理的异步异常';
        updateLoadState(`异步错误: ${reason}`, 100);
        console.error('[UnhandledRejection]', e.reason);
        hideLoadingOverlay(loadingScreen);
    });

    updateLoadState("正在唤醒 UI 核心模块...", 10);
    initMidi();

    setTimeout(() => {
        updateLoadState("正在计算星体运行轨道...", 40);

        // 初始化背景
        initRandomBackground();
        const canvas = document.getElementById('bgCanvas');
        if (canvas) canvas.classList.add('loaded');

        // 如果原生下发过主题，在这里读取并应用（你也可以在 bg.js 里手动调 window.AndroidBridge.saveTheme 保存）
        // 这里仅做界面加载完成的通知
        setTimeout(async () => {
            updateLoadState("正在拉取 MIDI 解析引擎与模型...", 75);

            try {
                const { models, modelMap } = await initAutoModels({
                    containerId: 'modelContainer',
                    onSelect: (modelItem) => {
                        window.SkyMusicModels.selected = modelItem;
                    },
                    logFn: (msg) => console.log(msg)
                });
                window.SkyMusicModels.list = models || [];
                window.SkyMusicModels.map = modelMap || {};
            } catch (err) {
                console.warn('[AutoModelsFallback]', err);
            }

            setTimeout(() => {
                updateLoadState("星门已开启，欢迎回来！", 100);
                setTimeout(() => {
                    hideLoadingOverlay(loadingScreen);
                }, 400);
            }, 300);
        }, 200);
    }, 100);

    // 最终安全兜底：防止任何意外导致加载层一直挡在前面
    setTimeout(() => {
        hideLoadingOverlay(loadingScreen);
    }, 5000);
});
