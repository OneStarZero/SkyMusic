import { initMidi } from './midi-parser.js';
import { initRandomBackground } from './bg.js';

document.addEventListener('DOMContentLoaded', () => {
    // 启动随机治愈系背景
    initRandomBackground();
    // 启动 MIDI 转换器
    initMidi();
});