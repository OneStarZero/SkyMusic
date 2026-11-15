// OneStarModels_Media/OneStar_MusicFastBasic.js
export function process(notes, logFn) {
    logFn(`[3] 执行 OneStar_MusicFastBasic 音频扒谱映射...`);
    
    const MIN_VELOCITY = 0.15; 
    const KEY_MAP = { 60: "1Key0", 62: "1Key1", 64: "1Key2", 65: "1Key3", 67: "1Key4", 69: "1Key5", 71: "1Key6", 72: "1Key7", 74: "1Key8", 76: "1Key9" };
    const VALID_KEYS = [60,62,64,65,67,69,71,72,74,76];

    function mapPitchToSky(midiPitch) {
        let p = midiPitch;
        while (p < 60) p += 12;
        while (p > 76) p -= 12;
        let best = 60, minDist = 999;
        for (const key of VALID_KEYS) {
            const d = Math.abs(p - key);
            if (d < minDist) { minDist = d; best = key; }
        }
        return KEY_MAP[best];
    }

    let processedNotes = [];
    let sortedNotes = [...notes]
        .filter(n => n.velocity >= MIN_VELOCITY)
        .sort((a, b) => a.time - b.time);

    let lastTime = -9999;
    sortedNotes.forEach(n => {
        let time = Math.round(n.time * 1000);
        if (time - lastTime < 50) {
            time = lastTime + 50;
        }
        
        processedNotes.push({
            time: time,
            key: mapPitchToSky(n.midi),
            origMidi: n.midi
        });
        lastTime = time;
    });

    logFn(`✅ 音频 AI 映射完成，共 ${processedNotes.length} 个音符。`);
    return processedNotes;
}