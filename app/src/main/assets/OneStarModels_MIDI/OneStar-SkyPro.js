export const meta = {
    mode: 'pro',
    name: 'OneStar-SkyPro',
    fidelity: 75,
    filterLongNotes: true,
    maxDurationMs: 1000,
    desc: '全景还原。提取主旋律，智能拆分和弦为琶音，保留丰富听感。'
};

export function process(mappedNotes, logFn) {
    if (typeof logFn === 'function') {
        logFn(`[3] 执行 OneStar-SkyPro 全景智能还原...`);
    }

    const GRID_STEP = 20;
    const MIN_GAP = 45;
    const SAFE_PLAY_GAP = 75;
    const MAX_CHORD_KEYS = 4;
    const MIN_VELOCITY = 0.2;

    let processedNotes = [];

    let rawNotes = mappedNotes
        .filter(n => n.velocity >= MIN_VELOCITY)
        .sort((a, b) => a.time - b.time);

    let quantized = rawNotes.map(n => ({ ...n, time: Math.round(n.time / GRID_STEP) * GRID_STEP }));

    let grouped = [];
    quantized.forEach(note => {
        let existing = grouped.find(g => g.time === note.time);
        if (existing) existing.notes.push(note); else grouped.push({ time: note.time, notes: [note] });
    });

    grouped.forEach(g => {
        g.notes.sort((a, b) => b.origMidi - a.origMidi);
        g.melody = g.notes[0];
        g.accompaniment = g.notes.slice(1);
        g.finalKeys = [g.melody.key];
        let usedKeys = new Set([g.melody.key]);

        for (let acc of g.accompaniment) {
            if (g.finalKeys.length >= MAX_CHORD_KEYS) break;
            if (usedKeys.has(acc.key)) continue;
            if (Math.abs(g.melody.origMidi - acc.origMidi) <= 24) {
                g.finalKeys.push(acc.key);
                usedKeys.add(acc.key);
            }
        }
    });

    let deduplicated = [];
    grouped.forEach(g => {
        let prev = deduplicated[deduplicated.length - 1];
        if (prev && g.time - prev.time < 50) {
            if (g.melody.key === prev.melody.key) {
                g.accompaniment.forEach(acc => {
                    if (!prev.finalKeys.includes(acc.key) && prev.finalKeys.length < MAX_CHORD_KEYS) prev.finalKeys.push(acc.key);
                });
                return;
            }
        }
        deduplicated.push(g);
    });

    let arpeggioPool = [];
    deduplicated.forEach(group => {
        if (group.finalKeys.length > MAX_CHORD_KEYS) {
            let extra = group.finalKeys.splice(MAX_CHORD_KEYS);
            extra.forEach((key, i) => arpeggioPool.push({ time: group.time + (i + 1) * GRID_STEP, key: key }));
        }
    });

    arpeggioPool.forEach(arp => {
        let existing = deduplicated.find(g => g.time === arp.time);
        if (existing) {
            if (!existing.finalKeys.includes(arp.key) && existing.finalKeys.length < MAX_CHORD_KEYS) existing.finalKeys.push(arp.key);
        } else {
            deduplicated.push({ time: arp.time, finalKeys: [arp.key] });
        }
    });

    deduplicated.sort((a, b) => a.time - b.time);

    let alignedGroups = [];
    let lastTime = -9999;

    deduplicated.forEach(group => {
        let currentTime = group.time;
        let timeDiff = currentTime - lastTime;

        if (timeDiff < MIN_GAP) {
            if (timeDiff <= 35) currentTime = lastTime + SAFE_PLAY_GAP;
        }

        let prev = alignedGroups[alignedGroups.length - 1];
        if (prev && currentTime - prev.time < 50) {
            group.finalKeys.forEach(k => {
                if (!prev.finalKeys.includes(k) && prev.finalKeys.length < MAX_CHORD_KEYS) prev.finalKeys.push(k);
            });
            prev.time = Math.max(prev.time, currentTime);
        } else {
            alignedGroups.push({ time: currentTime, finalKeys: [...group.finalKeys] });
            lastTime = currentTime;
        }
    });

    alignedGroups.forEach(group => {
        group.finalKeys.forEach(k => processedNotes.push({ time: group.time, key: k }));
    });

    return processedNotes;
}
