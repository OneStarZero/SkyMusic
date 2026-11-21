// ./js/modelRegistry.js

const DEFAULT_MODEL_SOURCES = [
    {
        file: '../OneStarModels_MIDI/OneStar-SkyRaw.js',
        fallbackMode: 'raw',
        fallbackName: 'OneStar-SkyRaw',
        fallbackFidelity: 100,
        fallbackFilterLongNotes: false,
        fallbackMaxDurationMs: 1000,
        fallbackMergeChordWindowMs: 0,
        fallbackDesc: '一比一原样复刻。不做任何过滤、量化、拆分、合并或时间调整。'
    },
    {
        file: '../OneStarModels_MIDI/OneStar-SkyFastPro.js',
        fallbackMode: 'fastpro',
        fallbackName: 'OneStar-SkyFastPro',
        fallbackFidelity: 90,
        fallbackFilterLongNotes: true,
        fallbackMaxDurationMs: 1000,
        fallbackMergeChordWindowMs: 15,
        fallbackDesc: '原轨直出。保留所有音符与伴奏细节，最大程度追求原曲听感。'
    },
    {
        file: '../OneStarModels_MIDI/OneStar-SkyPro.js',
        fallbackMode: 'pro',
        fallbackName: 'OneStar-SkyPro',
        fallbackFidelity: 75,
        fallbackFilterLongNotes: true,
        fallbackMaxDurationMs: 1000,
        fallbackMergeChordWindowMs: 15,
        fallbackDesc: '全景还原。提取主旋律，智能拆分和弦为琶音，保留丰富听感。'
    },
    {
        file: '../OneStarModels_MIDI/OneStar-SkyFast.js',
        fallbackMode: 'fast',
        fallbackName: 'OneStar-SkyFast',
        fallbackFidelity: 50,
        fallbackFilterLongNotes: true,
        fallbackMaxDurationMs: 1000,
        fallbackMergeChordWindowMs: 15,
        fallbackDesc: '极简单音。剔除伴奏，只保留最高音主线，适合手动弹奏。'
    }
];

function normalizeModelName(rawName = '', mode = '') {
    const trimmed = String(rawName || '').trim();
    if (trimmed) return trimmed;
    return mode ? `OneStar-${mode}` : 'OneStar-Model';
}

function toShortModelTag(fullName = '', mode = '') {
    const cleaned = String(fullName || '').replace(/^OneStar-/i, '').trim();
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

export async function loadSortedModels(sources = DEFAULT_MODEL_SOURCES, logFn = null) {
    const loaded = [];

    for (const src of sources) {
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
            const name = normalizeModelName(meta.name || src.fallbackName, mode);
            const desc = (typeof meta.desc === 'string' && meta.desc.trim())
                ? meta.desc.trim()
                : (src.fallbackDesc || '');
            const fidelity = (typeof meta.fidelity === 'number' && !Number.isNaN(meta.fidelity))
                ? meta.fidelity
                : (typeof src.fallbackFidelity === 'number' ? src.fallbackFidelity : 50);

            const hasMetaFilterFlag = Object.prototype.hasOwnProperty.call(meta, 'filterLongNotes');
            const filterLongNotes = hasMetaFilterFlag
                ? parseBooleanFlag(meta.filterLongNotes, false)
                : parseBooleanFlag(src.fallbackFilterLongNotes, false);

            const maxDurationMs = (typeof meta.maxDurationMs === 'number' && !Number.isNaN(meta.maxDurationMs) && meta.maxDurationMs > 0)
                ? meta.maxDurationMs
                : (src.fallbackMaxDurationMs || 1000);

            const mergeChordWindowMs = (typeof meta.mergeChordWindowMs === 'number' && !Number.isNaN(meta.mergeChordWindowMs) && meta.mergeChordWindowMs >= 0)
                ? meta.mergeChordWindowMs
                : (typeof src.fallbackMergeChordWindowMs === 'number' ? src.fallbackMergeChordWindowMs : 15);

            loaded.push({
                mode,
                name,
                shortName: toShortModelTag(name, mode),
                desc,
                fidelity,
                filterLongNotes,
                maxDurationMs,
                mergeChordWindowMs,
                file: src.file,
                process: mod.process
            });
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

export function renderSortedModelCards(container, models, onSelect = null, defaultMode = null) {
    if (!container || !Array.isArray(models) || models.length === 0) return;

    container.innerHTML = '';
    const activeMode = defaultMode || models[0].mode;

    models.forEach((item, index) => {
        const card = document.createElement('div');
        card.className = `model-card${item.mode === activeMode ? ' active' : ''}`;
        card.dataset.mode = item.mode;
        card.dataset.order = String(index + 1);
        card.dataset.fidelity = String(item.fidelity);
        card.dataset.filterLongNotes = String(item.filterLongNotes);

        card.innerHTML = `
            <div class="model-content">
                <div class="model-name">${item.name}</div>
                <div class="model-desc">${item.desc}</div>
            </div>
            <div class="model-indicator"></div>
        `;

        card.addEventListener('click', () => {
            container.querySelectorAll('.model-card').forEach(el => el.classList.remove('active'));
            card.classList.add('active');
            if (typeof onSelect === 'function') {
                onSelect(item);
            }
        });

        container.appendChild(card);
    });

    const initialItem = models.find(m => m.mode === activeMode) || models[0];
    if (typeof onSelect === 'function' && initialItem) {
        onSelect(initialItem);
    }
}

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
