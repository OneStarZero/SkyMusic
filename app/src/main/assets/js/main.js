import { initTabs } from './ui.js';
import { initMidi } from './midi-parser.js';
import { initOcr } from './ocr-parser.js';
import { initAudio } from './audio-parser.js';

document.addEventListener('DOMContentLoaded', () => {
    initTabs();
    initMidi();
    initOcr();
    initAudio();
});