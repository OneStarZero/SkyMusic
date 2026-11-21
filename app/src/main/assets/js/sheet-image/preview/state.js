/**
 * SkyMusic · 预览层共享状态
 */

import { STYLE_DEFAULTS } from '../style.js';

export const DEFAULT_ZOOM = 0.5;

export const view = {
  raw: null,
  title: '未命名乐谱',
  author: '',
  model: '',
  transcriber: '',
  rows: 15,
  tol: 25,
  bpm: 0,
  style: STYLE_DEFAULTS,
  layout: null,
  pages: [],
  selected: [],
  info: null,
  infoItems: [],
  mergeLong: false,
  busy: false,

  pageZoom: DEFAULT_ZOOM,
  longZoom: DEFAULT_ZOOM,

  overlay: null,
  titleEl: null,
  pagesHost: null,
  longHost: null,
  longCanvas: null,
  zoomLabel: null,
  key15Btn: null,
  key9Btn: null,
  selectAllRow: null,
  selectAllSwitch: null,
  selectCountEl: null,
  progressBar: null,
  progressFill: null,
  progressText: null,
  tabPagesBtn: null,
  tabLongBtn: null,
  bodyPages: null,
  bodyLong: null,
  btnSave: null,
  btnSaveLong: null
};

export let activeTab = 'pages';

export function setActiveTab(tab) {
  activeTab = tab;
}

export function getActiveTab() {
  return activeTab;
}
