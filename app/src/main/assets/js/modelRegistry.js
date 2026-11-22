// ./js/modelRegistry.js

const DEFAULT_MODEL_SOURCES = [
    { file: '../OneStarModels_MIDI/OneStar-SkyRaw.js',      fallbackMode: 'raw' },
    { file: '../OneStarModels_MIDI/OneStar-SkyBalance.js',  fallbackMode: 'balance' },
    { file: '../OneStarModels_MIDI/OneStar-SkyFastPro.js',  fallbackMode: 'fastpro' },
    { file: '../OneStarModels_MIDI/OneStar-SkyPro.js',      fallbackMode: 'pro' },
    { file: '../OneStarModels_MIDI/OneStar-SkyFast.js',     fallbackMode: 'fast' }
];

/** 兜底元数据（仅当模型文件加载失败时使用） */
const FALLBACK_META = {
    raw:     { name: 'OneStar-SkyRaw',     fidelity: 100, filterLongNotes: false, maxDurationMs: 1000, mergeChordWindowMs: 0,  desc: '一比一原样复刻。不做任何过滤、量化、拆分、合并或时间调整。' },
    balance: { name: 'OneStar-SkyBalance', fidelity: 88,  filterLongNotes: false, maxDurationMs: 0,    mergeChordWindowMs: 0,  desc: '平衡主旋律与伴奏。保留全部长音，按音高分层处理，追求连贯自然。' },
    fastpro: { name: 'OneStar-SkyFastPro', fidelity: 90,  filterLongNotes: true,  maxDurationMs: 1000, mergeChordWindowMs: 15, desc: '原轨直出。保留所有音符与伴奏细节，最大程度追求原曲听感。' },
    pro:     { name: 'OneStar-SkyPro',     fidelity: 75,  filterLongNotes: true,  maxDurationMs: 1000, mergeChordWindowMs: 15, desc: '全景还原。提取主旋律，智能拆分和弦为琶音，保留丰富听感。' },
    fast:    { name: 'OneStar-SkyFast',    fidelity: 50,  filterLongNotes: true,  maxDurationMs: 1000, mergeChordWindowMs: 15, desc: '极简单音。剔除伴奏，只保留最高音主线，适合手动弹奏。' }
};

/* ========================== 工具函数 ========================== */

function normalizeModelName(rawName = '', mode = '') {
    const trimmed = String(rawName || '').trim();
    if (trimmed) return trimmed;
    return mode ? `OneStar-${mode}` : 'OneStar-Model';
}

function toShortModelTag(fullName = '', mode = '') {
    const cleaned = String(fullName || '').replace(/^OneStar-/, '').trim();
    return cleaned || String(mode || 'Model');
}

function parseBooleanFlag(val, fallback = false) {
    if (typeof val === 'boolean') return val;
    if (typeof val === 'string') {
        const lower = val.trim().toLowerCase();
        if (lower === 'true') return true;
        if (lower === 'false') return false;
    }
    return Boolean(fallback);
}

function pickNumber(val, fallback, min = -Infinity) {
    if (typeof val === 'number' && !Number.isNaN(val) && val >= min) return val;
    return fallback;
}

/* ========================== 加载模型 ========================== */

export async function loadSortedModels(sources = DEFAULT_MODEL_SOURCES, logFn = null) {
    const loaded = [];

    for (const src of sources) {
        const fb = FALLBACK_META[src.fallbackMode] || {};

        try {
            const mod = await import(src.file);

            if (typeof mod.process !== 'function') {
                if (typeof logFn === 'function') {
                    logFn(`[警告] 模型 ${src.file} 缺少 process 函数，已跳过`);
                }
                continue;
            }

            const meta = (mod.meta && typeof mod.meta === 'object') ? mod.meta : {};
            const mode = (typeof meta.mode === 'string' && meta.mode.trim())
                ? meta.mode.trim()
                : src.fallbackMode;

            const name = normalizeModelName(meta.name || fb.name, mode);
            const desc = (typeof meta.desc === 'string' && meta.desc.trim())
                ? meta.desc.trim()
                : (fb.desc || '');

            const fidelity = pickNumber(meta.fidelity, fb.fidelity ?? 50, 0);

            const hasMetaFilterFlag = Object.prototype.hasOwnProperty.call(meta, 'filterLongNotes');
            const filterLongNotes = hasMetaFilterFlag
                ? parseBooleanFlag(meta.filterLongNotes, false)
                : parseBooleanFlag(fb.filterLongNotes, false);

            const maxDurationMs = pickNumber(meta.maxDurationMs, fb.maxDurationMs ?? 1000, 0);
            const mergeChordWindowMs = pickNumber(meta.mergeChordWindowMs, fb.mergeChordWindowMs ?? 15, 0);

            loaded.push({
                mode,
                name,
                shortName: meta.shortName || toShortModelTag(name, mode),
                desc,
                fidelity,
                filterLongNotes,
                maxDurationMs,
                mergeChordWindowMs,
                file: src.file,
                process: mod.process
            });

            if (typeof logFn === 'function') {
                logFn(`[Model] 加载成功: ${name} (${mode})`);
            }
        } catch (err) {
            if (typeof logFn === 'function') {
                logFn(`[提示] 模型 ${src.file} 加载失败: ${err?.message || err}`);
            }
        }
    }

    loaded.sort((a, b) => {
        if (b.fidelity !== a.fidelity) return b.fidelity - a.fidelity;
        return a.name.localeCompare(b.name);
    });

    return loaded;
}

/* ========================== 渲染模型卡片 ========================== */

export function renderSortedModelCards(container, models, onSelect = null, defaultMode = null) {
    if (!container || !Array.isArray(models) || models.length === 0) return;

    container.innerHTML = '';
    const activeMode = defaultMode || models[0].mode;

    const frag = document.createDocumentFragment();

    models.forEach((item, index) => {
        const card = document.createElement('div');
        card.className = `model-card${item.mode === activeMode ? ' active' : ''}`;
        card.dataset.mode = item.mode;
        card.dataset.order = String(index + 1);
        card.dataset.fidelity = String(item.fidelity);
        card.dataset.filterLongNotes = String(item.filterLongNotes);

        const content = document.createElement('div');
        content.className = 'model-content';

        const nameEl = document.createElement('div');
        nameEl.className = 'model-name';
        nameEl.textContent = item.name;

        const descEl = document.createElement('div');
        descEl.className = 'model-desc';
        descEl.textContent = item.desc;

        content.appendChild(nameEl);
        content.appendChild(descEl);

        const indicator = document.createElement('div');
        indicator.className = 'model-indicator';

        card.appendChild(content);
        card.appendChild(indicator);

        card.addEventListener('click', () => {
            container.querySelectorAll('.model-card').forEach(el => el.classList.remove('active'));
            card.classList.add('active');
            if (typeof onSelect === 'function') {
                onSelect(item);
            }
        });

        frag.appendChild(card);
    });

    container.appendChild(frag);

    const initialItem = models.find(m => m.mode === activeMode) || models[0];
    if (typeof onSelect === 'function' && initialItem) {
        onSelect(initialItem);
    }
}

/* ========================== 初始化 ========================== */

export async function initAutoModels({
    containerId = 'modelContainer',
    sources = DEFAULT_MODEL_SOURCES,
    defaultMode = null,
    onSelect = null,
    logFn = null
} = {}) {
    const models = await loadSortedModels(sources, logFn);
    const container = document.getElementById(containerId);
    renderSortedModelCards(container, models, onSelect, defaultMode);

    const modelMap = {};
    for (const m of models) {
        modelMap[m.mode] = m;
    }

    window.SkyMusicModels = {
        selected: models[0] || null,
        map: modelMap,
        list: models,
        getByMode(mode) {
            return modelMap[mode] || this.selected || models[0] || null;
        }
    };

    return { models, modelMap, selected: window.SkyMusicModels.selected };
}
