export const meta = {
    mode: 'raw',
    name: 'OneStar-SkyRaw',
    fidelity: 100,
    filterLongNotes: false,
    maxDurationMs: 1000,
    mergeChordWindowMs: 0,
    desc: '一比一原样复刻。不做任何过滤、量化、拆分、合并或时间调整。'
};

export function process(mappedNotes, logFn) {
    if (!Array.isArray(mappedNotes)) {
        if (typeof logFn === 'function') {
            logFn(`[3] OneStar-SkyRaw 收到非数组输入，返回 0 个音符`);
        }
        return [];
    }

    const result = [...mappedNotes]
        .sort((a, b) => a.time - b.time)
        .map(note => ({
            time: note.time,
            key: note.key,
            origMidi: note.origMidi
        }));

    if (typeof logFn === 'function') {
        logFn(`[3] 执行 OneStar-SkyRaw 一比一原样复刻（零合并/零过滤，完整保留 ${result.length} 个音符）...`);
    }

    return result;
}
