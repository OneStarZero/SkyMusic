// OneStar-SkyFast.js
export function process(mappedNotes, logFn) {
    logFn(`[3] 执行 OneStar-SkyFast 主旋律提取...`);
    const WINDOW_MS = 35;
    let windowStart = -1;
    let processedNotes = [];

    for (let i = 0; i < mappedNotes.length; i++) {
        let current = mappedNotes[i];
        if (windowStart === -1) {
            windowStart = current.time;
            processedNotes.push(current);
        } else if (current.time - windowStart < WINDOW_MS) {
            let last = processedNotes[processedNotes.length - 1];
            if (current.origMidi > last.origMidi) processedNotes[processedNotes.length - 1] = current;
        } else {
            windowStart = current.time;
            processedNotes.push(current);
        }
    }
    return processedNotes;
}