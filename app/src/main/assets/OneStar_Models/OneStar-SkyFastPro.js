export function process(mappedNotes, logFn) {
    logFn(`[3] 执行 OneStar-SkyFastPro 原轨直出...`);
    
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