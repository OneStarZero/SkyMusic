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

    // 1. 启动核心业务逻辑
    updateLoadState("正在唤醒 UI 核心模块...", 10);
    initMidi();

    // 2. 延迟加载随机星空
    setTimeout(() => {
        updateLoadState("正在计算星体运行轨道...", 40);
        
        initRandomBackground();
        const canvas = document.getElementById('bgCanvas');
        if (canvas) canvas.classList.add('loaded');

        // 3. 模拟引擎加载完成
        setTimeout(() => {
            updateLoadState("正在拉取 MIDI 解析引擎...", 75);
            
            setTimeout(() => {
                updateLoadState("星门已开启，欢迎回来！", 100);
                
                // 4. 延迟淡出加载层
                setTimeout(() => {
                    if (loadingScreen) {
                        loadingScreen.classList.add('hidden');
                    }
                }, 400);

            }, 300);
        }, 200);
    }, 100);
});