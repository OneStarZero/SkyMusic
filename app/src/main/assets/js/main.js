import { initMidi } from './midi-parser.js';
import { initRandomBackground } from './bg.js';
import { initAutoModels } from './modelRegistry.js';

function hideLoadingOverlay(loadingScreen) {
    if (!loadingScreen) return;
    loadingScreen.classList.add('hidden');
    loadingScreen.style.transition = 'opacity 0.5s ease, visibility 0.5s';
    loadingScreen.style.opacity = '0';
    setTimeout(() => {
        loadingScreen.style.display = 'none';
    }, 500);
}

document.addEventListener('DOMContentLoaded', () => {
    const loadingScreen = document.getElementById('loading-screen');
    const loadStatus = document.getElementById('loadStatus');
    const loadSubStatus = document.getElementById('loadSubStatus');
    const progressText = document.getElementById('loadProgressText');
    const progressBar = document.getElementById('loadProgressBar');
    const progressGlow = document.getElementById('loadProgressGlow');

    window.SkyMusicModels = {
        selected: null,
        map: {},
        list: []
    };

    // 🌟 适配新 UI 的状态更新
    function updateLoadState(title, sub, percent) {
        if (loadStatus) loadStatus.textContent = title;
        if (loadSubStatus) loadSubStatus.textContent = sub;
        if (progressText) progressText.textContent = Math.round(percent) + '%';
        if (progressBar) progressBar.style.width = percent + '%';
        if (progressGlow) progressGlow.style.left = `calc(${percent}% - 5px)`;
    }

    window.addEventListener('error', (e) => {
        updateLoadState("发生了一点小意外", `脚本错误: ${e.message || '未知错误'}`, 100);
        console.error('[GlobalError]', e.error || e.message);
        setTimeout(() => hideLoadingOverlay(loadingScreen), 1000);
    });

    window.addEventListener('unhandledrejection', (e) => {
        const reason = e.reason?.message || e.reason || '未处理的异步异常';
        updateLoadState("发生了一点小意外", `异步错误: ${reason}`, 100);
        console.error('[UnhandledRejection]', e.reason);
        setTimeout(() => hideLoadingOverlay(loadingScreen), 1000);
    });

    // 模拟真实感的前期加载进度跳跃
    updateLoadState("正在唤醒 UI 核心模块...", "加载本地配置文件", 10);
    initMidi();

    setTimeout(() => {
        updateLoadState("正在计算星体运行轨道...", "生成随机背景与光影", 40);
        initRandomBackground();
        const canvas = document.getElementById('bgCanvas');
        if (canvas) canvas.classList.add('loaded');

        setTimeout(async () => {
            updateLoadState("正在拉取 MIDI 解析引擎...", "连接 Tone.js 及动态模型", 75);

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
                updateLoadState("星门已开启", "欢迎回来", 100);
                setTimeout(() => {
                    hideLoadingOverlay(loadingScreen);
                }, 600);
            }, 400);
        }, 400);
    }, 200);

    // 最终安全兜底
    setTimeout(() => {
        hideLoadingOverlay(loadingScreen);
    }, 6000);
});