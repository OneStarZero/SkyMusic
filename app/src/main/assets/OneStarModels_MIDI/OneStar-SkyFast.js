export const meta = {
    mode: 'fast',
    name: 'OneStar-SkyFast',
    fidelity: 50,
    desc: '极简单音并过滤长音。剔除伴奏与过长音符，只保留最高音主线。'
};

export function process(mappedNotes, logFn) {
    const MAX_DURATION_MS = 1000;
    const WINDOW_MS = 45;
    const MIN_GAP = 60;
    const MIN_VELOCITY = 0.2;

    if (!Array.isArray(mappedNotes)) {
        return [];
    }

    let longNoteCount = 0;
    const durationFiltered = mappedNotes.filter(note => {
        const durMs = typeof note.durationMs === 'number'
            ? note.durationMs
            : (typeof note.duration === 'number' ? note.duration * 1000 : 0);
        if (durMs > MAX_DURATION_MS) {
            longNoteCount++;
            return false;
        }
        return true;
    });

    if (typeof logFn === 'function') {
        logFn(`[3] 执行 OneStar-SkyFast 纯净单音主旋律提取（剔除长音 -${longNoteCount} 个）...`);
    }

    let processedNotes = [];

    let sortedNotes = [...durationFiltered]
        .filter(n => n.velocity >= MIN_VELOCITY)
        .sort((a, b) => a.time - b.time);

    let windowStart = -1;
    let currentBestNote = null;

    for (let i = 0; i < sortedNotes.length; i++) {
        let current = sortedNotes[i];

        if (windowStart === -1) {
            windowStart = current.time;
            currentBestNote = current;
            continue;
        }

        if (current.time - windowStart < WINDOW_MS) {
            if (current.origMidi > currentBestNote.origMidi) {
                currentBestNote = current;
            }
        } else {
            pushNote(currentBestNote);
            windowStart = current.time;
            currentBestNote = current;
        }
    }
    if (currentBestNote) pushNote(currentBestNote);

    function pushNote(note) {
        if (!note) return;
        if (processedNotes.length > 0) {
            let lastNote = processedNotes[processedNotes.length - 1];
            if (lastNote.key === note.key && (note.time - lastNote.time) < 100) return;
            if (note.time - lastNote.time < MIN_GAP) note.time = lastNote.time + MIN_GAP;
        }
        processedNotes.push({ time: note.time, key: note.key, origMidi: note.origMidi });
    }

    return processedNotes;
}
