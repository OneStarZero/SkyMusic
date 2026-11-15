import { initTabs } from './ui.js';
import { initMidi } from './midi-parser.js';
import { initOcr } from './ocr-parser.js';

document.addEventListener('DOMContentLoaded', () => {
    initTabs();
    initMidi();
    initOcr();
});