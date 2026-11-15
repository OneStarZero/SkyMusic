// OneStar_Models/OneStar-SkyFastPro.js
export function process(mappedNotes, logFn) {
    logFn(`[3] 执行 OneStar-SkyFastPro 原轨直出...`);
    
    // 最小物理安全间隔，防止同键连击被安卓系统吞掉
    const MIN_GAP = 35; 

    let processedNotes = [];
    
    // 1. 严格按时间排序
    let sortedNotes = [...mappedNotes].sort((a, b) => a.time - b.time);

    // 2. 遍历输出
    let lastTimeByKey = {}; // 记录每个键上一次按下的时间

    sortedNotes.forEach(note => {
        let currentTime = note.time;

        // 如果这个键上次按下的时间距离现在太近（小于 35ms），稍微往后推一点
        if (lastTimeByKey[note.key] !== undefined) {
            if (currentTime - lastTimeByKey[note.key] < MIN_GAP) {
                currentTime = lastTimeByKey[note.key] + MIN_GAP;
            }
        }

        processedNotes.push({
            time: currentTime,
            key: note.key,
            origMidi: note.origMidi
        });

        lastTimeByKey[note.key] = currentTime;
    });

    logFn(`✅ 直出完成：输入 ${mappedNotes.length} 个音符，输出 ${processedNotes.length} 个。`);
    return processedNotes;
}