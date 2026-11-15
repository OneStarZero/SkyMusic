// OneStar_Models/OneStar-SkyPro.js
export function process(mappedNotes, logFn) {
    logFn(`[3] 执行 OneStar-SkyPro 极致全景还原...`);
    
    // 仅保留最基础的时间对齐，不做任何阉割
    const GRID_STEP = 15; // 更精细的 15ms 网格

    let processedNotes = [];

    // 1. 按时间排序并剔除极弱噪声
    let sortedNotes = [...mappedNotes]
        .filter(n => n.velocity >= 0.1) 
        .sort((a, b) => a.time - b.time);

    // 2. 仅做微小的网格对齐（把 1ms 内的偏差对齐到一起，防止因为微小误差导致本该同时按的键被拆开）
    let grouped = [];
    sortedNotes.forEach(note => {
        let qTime = Math.round(note.time / GRID_STEP) * GRID_STEP;
        let existing = grouped.find(g => Math.abs(g.time - qTime) <= GRID_STEP);
        if (existing) {
            // 绝对不去重，哪怕是同键也塞进去（交给原生端处理）
            existing.notes.push(note);
        } else {
            grouped.push({ time: qTime, notes: [note] });
        }
    });

    // 3. 展平，不做任何和弦数量限制
    grouped.forEach(g => {
        g.notes.forEach(n => {
            processedNotes.push({ time: g.time, key: n.key, origMidi: n.origMidi });
        });
    });

    // 4. 最后再做一次排序，确保时间轴绝对准确
    processedNotes.sort((a, b) => a.time - b.time);

    logFn(`✅ Pro 提取完成，保留全部伴奏细节。`);
    return processedNotes;
}