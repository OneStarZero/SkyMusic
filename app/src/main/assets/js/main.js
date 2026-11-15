// js/main.js
import { initTabs } from './ui.js';
import { initMidi } from './midi-parser.js';
import { initOcr } from './ocr-parser.js';

// 页面加载完成后，初始化所有模块
document.addEventListener('DOMContentLoaded', () => {
    initTabs();
    initMidi();
    initOcr();
});