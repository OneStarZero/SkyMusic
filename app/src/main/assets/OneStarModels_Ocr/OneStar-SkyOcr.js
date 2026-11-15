export function process(ocrNotes, logFn) {
    logFn(`[3] 执行 OneStar-SkyOcr 简谱智能映射...`);
    
    const MIN_GAP = 50; 
    let processedNotes = [];
    let lastTime = -9999;

    ocrNotes.sort((a, b) => a.time - b.time);

    ocrNotes.forEach(note => {
        let pitch = note.midi;
        if (pitch < 48) pitch = 48;
        if (pitch > 84) pitch = 84;

        let time = note.time;
        if (time - lastTime < MIN_GAP) {
            time = lastTime + MIN_GAP;
        }

        processedNotes.push({
            time: time,
            midi: pitch,
            origMidi: pitch
        });

        lastTime = time;
    });

    logFn(`✅ OCR 序列处理完毕，共 ${processedNotes.length} 个音。`);
    return processedNotes;
}
