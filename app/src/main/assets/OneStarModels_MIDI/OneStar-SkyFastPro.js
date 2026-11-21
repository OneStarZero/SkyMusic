export const meta = {
    mode: 'fastpro',
    name: 'OneStar-SkyFastPro',
    fidelity: 90,
    filterLongNotes: true,
    maxDurationMs: 1000,
    desc: '原轨直出。保留所有音符与伴奏细节，最大程度追求原曲听感。'
};

export function process(mappedNotes, logFn) {
    if (typeof logFn === 'function') {
        logFn(`[3] 执行 OneStar-SkyFastPro 原轨直出...`);
    }

    const MIN_GAP = 35;
    let processedNotes = [];
    let sortedNotes = [...mappedNotes].sort((a, b) => a.time - b.time);
    let lastTimeByKey = {};

    sortedNotes.forEach(note => {
        let currentTime = note.time;
        if (lastTimeByKey[note.key] !== undefined) {
            if (currentTime - lastTimeByKey[note.key] < MIN_GAP) {
                currentTime = lastTimeByKey[note.key] + MIN_GAP;
            }
        }
        processedNotes.push({ time: currentTime, key: note.key, origMidi: note.origMidi });
        lastTimeByKey[note.key] = currentTime;
    });

    return processedNotes;
}
