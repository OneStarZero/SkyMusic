import { initMidi } from './midi-parser.js';
import { initRandomBackground } from './bg.js';

document.addEventListener('DOMContentLoaded', () => {
    const loadingScreen = document.getElementById('loading-screen');
    const loadStatus = document.getElementById('loadStatus');
    const loadProgressBar = document.getElementById('loadProgressBar');

    function updateLoadState(text, percent) {
        if (loadStatus) loadStatus.textContent = text;
        if (loadProgressBar) loadProgressBar.style.width = percent + '%';
    }

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
        setTimeout(() => {
            updateLoadState("正在拉取 MIDI 解析引擎...", 75);
            setTimeout(() => {
                updateLoadState("星门已开启，欢迎回来！", 100);
                setTimeout(() => { if (loadingScreen) loadingScreen.classList.remove('hidden'); loadingScreen.classList.add('hidden'); }, 400);
            }, 300);
        }, 200);
    }, 100);
});