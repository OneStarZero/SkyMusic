/**
 * SkyMusic · OneStar-SkyBalance
 * 基于 SkyRaw 一比一复刻，平衡主旋律与伴奏，保留长音，追求连贯自然
 */

export const meta = {
    mode: 'balance',
    name: 'OneStar-SkyBalance',
    shortName: 'SkyBalance',
    fidelity: 88,
    filterLongNotes: false,
    maxDurationMs: 0,
    mergeChordWindowMs: 0,
    desc: '平衡主旋律与伴奏。保留全部长音，按音高分层处理，追求连贯自然。'
};

/** 主旋律音高阈值（高于此值视为主旋律） */
const MELODY_PITCH = 67;  // G4

/** 主旋律同键最小间隔 */
const MELODY_MIN_GAP = 30;

/** 伴奏同键最小间隔 */
const ACCOMP_MIN_GAP = 60;

/** 最大和弦音数 */
const MAX_CHORD = 4;

/** 和弦判定窗口 */
const CHORD_WINDOW = 20;

/** 同键短间隔合并阈值 */
const MERGE_GAP = 80;

export function process(mappedNotes, logFn) {
    if (!Array.isArray(mappedNotes)) {
        if (typeof logFn === 'function') {
            logFn(`[3] OneStar-SkyBalance 收到非数组输入，返回 0 个音符`);
        }
        return [];
    }

    if (typeof logFn === 'function') {
        logFn(`[3] 执行 OneStar-SkyBalance 平衡主旋律与伴奏...`);
    }

    if (!mappedNotes.length) return [];

    // 1. 按时间排序
    const sorted = [...mappedNotes].sort((a, b) => a.time - b.time);

    // 2. 分层：主旋律 / 伴奏
    const melody = [];
    const accomp = [];

    for (let i = 0; i < sorted.length; i++) {
        const n = sorted[i];
        const midi = n.origMidi !== undefined ? n.origMidi : 60;
        if (midi >= MELODY_PITCH) {
            melody.push(n);
        } else {
            accomp.push(n);
        }
    }

    if (typeof logFn === 'function') {
        logFn(`[3] 分层：主旋律 ${melody.length} 个，伴奏 ${accomp.length} 个`);
    }

    // 3. 主旋律：同键间隔 30ms
    const melodyProcessed = dedupeByKey(melody, MELODY_MIN_GAP);

    // 4. 伴奏：同键间隔 60ms
    const accompProcessed = dedupeByKey(accomp, ACCOMP_MIN_GAP);

    // 5. 合并
    let merged = melodyProcessed.concat(accompProcessed);
    merged.sort((a, b) => a.time - b.time);

    // 6. 和弦限制：同一时间窗口最多 4 音
    merged = limitChord(merged, MAX_CHORD, CHORD_WINDOW);

    // 7. 同键短间隔合并（连贯性）
    merged = mergeShortGaps(merged, MERGE_GAP);

    if (typeof logFn === 'function') {
        logFn(`[3] 最终输出 ${merged.length} 个音符`);
    }

    return merged;
}

/** 同键去重：间隔小于 minGap 的丢弃 */
function dedupeByKey(notes, minGap) {
    const result = [];
    const lastTimeByKey = {};

    for (let i = 0; i < notes.length; i++) {
        const n = notes[i];
        const key = n.key;
        const last = lastTimeByKey[key];

        if (last !== undefined && n.time - last < minGap) {
            continue;
        }

        result.push({
            time: n.time,
            key: n.key,
            origMidi: n.origMidi
        });
        lastTimeByKey[key] = n.time;
    }

    return result;
}

/** 和弦限制：同一时间窗口最多 maxChord 音 */
function limitChord(notes, maxChord, windowMs) {
    const result = [];
    let windowStart = -1;
    let windowNotes = [];

    for (let i = 0; i < notes.length; i++) {
        const n = notes[i];

        if (windowStart < 0 || n.time - windowStart > windowMs) {
            if (windowNotes.length > 0) {
                result.push(...windowNotes);
            }
            windowStart = n.time;
            windowNotes = [n];
        } else {
            if (windowNotes.length < maxChord) {
                windowNotes.push(n);
            }
        }
    }

    if (windowNotes.length > 0) {
        result.push(...windowNotes);
    }

    return result;
}

/** 同键短间隔合并：间隔 < mergeGap 的保留第一个 */
function mergeShortGaps(notes, mergeGap) {
    const result = [];
    const lastTimeByKey = {};

    for (let i = 0; i < notes.length; i++) {
        const n = notes[i];
        const key = n.key;
        const last = lastTimeByKey[key];

        if (last !== undefined && n.time - last < mergeGap) {
            continue;
        }

        result.push(n);
        lastTimeByKey[key] = n.time;
    }

    return result;
}
