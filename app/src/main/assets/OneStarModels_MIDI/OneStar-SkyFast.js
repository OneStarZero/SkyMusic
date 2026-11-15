export function process(mappedNotes, logFn) {
    logFn(`[3] 执行 OneStar-SkyFast 纯净单音主旋律提取...`);
    
    const WINDOW_MS = 45;         
    const MIN_GAP = 60;           
    const MIN_VELOCITY = 0.2;     
    
    let processedNotes = [];

    let sortedNotes = [...mappedNotes]
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