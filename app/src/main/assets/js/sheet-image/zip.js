/**
 * SkyMusic · 无依赖 ZIP 写入器（store 模式）
 * PNG 本身已压缩，无需 deflate，直接存储即可
 */

const encoder = new TextEncoder();

const CRC_TABLE = (function () {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    }
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(bytes) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < bytes.length; i++) {
    c = CRC_TABLE[(c ^ bytes[i]) & 0xFF] ^ (c >>> 8);
  }
  return (c ^ 0xFFFFFFFF) >>> 0;
}

function dosTime(d) {
  const time = ((d.getHours() & 0x1F) << 11) |
               ((d.getMinutes() & 0x3F) << 5) |
               ((Math.floor(d.getSeconds() / 2)) & 0x1F);
  const date = (((d.getFullYear() - 1980) & 0x7F) << 9) |
               (((d.getMonth() + 1) & 0x0F) << 5) |
               (d.getDate() & 0x1F);
  return { time: time, date: date };
}

export function createZipWriter() {
  const chunks = [];
  const central = [];
  let offset = 0;

  function addFile(name, bytes) {
    const nameBytes = encoder.encode(name);
    const crc = crc32(bytes);
    const now = dosTime(new Date());

    const local = new Uint8Array(30 + nameBytes.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true);
    lv.setUint16(6, 0x0800, true);
    lv.setUint16(8, 0, true);
    lv.setUint16(10, now.time, true);
    lv.setUint16(12, now.date, true);
    lv.setUint32(14, crc, true);
    lv.setUint32(18, bytes.length, true);
    lv.setUint32(22, bytes.length, true);
    lv.setUint16(26, nameBytes.length, true);
    lv.setUint16(28, 0, true);
    local.set(nameBytes, 30);

    chunks.push(local, bytes);

    const cd = new Uint8Array(46 + nameBytes.length);
    const cv = new DataView(cd.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 20, true);
    cv.setUint16(6, 20, true);
    cv.setUint16(8, 0x0800, true);
    cv.setUint16(10, 0, true);
    cv.setUint16(12, now.time, true);
    cv.setUint16(14, now.date, true);
    cv.setUint32(16, crc, true);
    cv.setUint32(20, bytes.length, true);
    cv.setUint32(24, bytes.length, true);
    cv.setUint16(28, nameBytes.length, true);
    cv.setUint16(30, 0, true);
    cv.setUint16(32, 0, true);
    cv.setUint16(34, 0, true);
    cv.setUint16(36, 0, true);
    cv.setUint32(38, 0, true);
    cv.setUint32(42, offset, true);
    cd.set(nameBytes, 46);

    central.push(cd);
    offset += local.length + bytes.length;
  }

  function finish() {
    const cdStart = offset;
    let cdSize = 0;
    for (let i = 0; i < central.length; i++) {
      chunks.push(central[i]);
      cdSize += central[i].length;
    }

    const end = new Uint8Array(22);
    const ev = new DataView(end.buffer);
    ev.setUint32(0, 0x06054b50, true);
    ev.setUint16(4, 0, true);
    ev.setUint16(6, 0, true);
    ev.setUint16(8, central.length, true);
    ev.setUint16(10, central.length, true);
    ev.setUint32(12, cdSize, true);
    ev.setUint32(16, cdStart, true);
    ev.setUint16(20, 0, true);
    chunks.push(end);

    return new Blob(chunks, { type: 'application/zip' });
  }

  return { addFile: addFile, finish: finish };
}

/** canvas → PNG 字节 */
export function canvasToBytes(canvas) {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (!blob) { reject(new Error('canvas 转 PNG 失败')); return; }
      const reader = new FileReader();
      reader.onload = () => resolve(new Uint8Array(reader.result));
      reader.onerror = () => reject(new Error('读取 PNG 数据失败'));
      reader.readAsArrayBuffer(blob);
    }, 'image/png');
  });
}

/** 多张 canvas 上下拼接成一张长图 canvas（同步版，仅用于小图） */
export function mergeCanvasesToLong(canvases, gapPx, bgColor, maxPixels) {
  if (!canvases.length) return null;

  let maxW = 0;
  let totalH = 0;
  for (let i = 0; i < canvases.length; i++) {
    if (canvases[i].width > maxW) maxW = canvases[i].width;
    totalH += canvases[i].height;
  }
  totalH += gapPx * (canvases.length - 1);

  let scale = 1;
  if (maxW * totalH > maxPixels) {
    scale = Math.sqrt(maxPixels / (maxW * totalH));
  }

  const outW = Math.max(1, Math.round(maxW * scale));
  const outH = Math.max(1, Math.round(totalH * scale));

  const out = document.createElement('canvas');
  out.width = outW;
  out.height = outH;
  const ctx = out.getContext('2d');

  ctx.fillStyle = bgColor;
  ctx.fillRect(0, 0, outW, outH);

  let y = 0;
  for (let i = 0; i < canvases.length; i++) {
    const c = canvases[i];
    const dw = c.width * scale;
    const dh = c.height * scale;
    const dx = (outW - dw) / 2;
    ctx.drawImage(c, dx, y, dw, dh);
    y += dh + gapPx * scale;
  }

  return out;
}
/**
 * 分块生成长图 PNG 字节数组
 * 超过 16384px 时自动拆成多张，返回 [{part, total, bytes}, ...]
 */
export async function buildLongPngBytes(canvases, gapPx, bgColor, maxPixels, onProgress) {
  if (!canvases.length) throw new Error('没有可用的页面');

  let maxW = 0;
  let totalH = 0;
  for (let i = 0; i < canvases.length; i++) {
    if (canvases[i].width > maxW) maxW = canvases[i].width;
    totalH += canvases[i].height;
  }
  totalH += gapPx * (canvases.length - 1);

  let scale = 1;
  if (maxW * totalH > maxPixels) {
    scale = Math.sqrt(maxPixels / (maxW * totalH));
  }

  const outW = Math.max(1, Math.round(maxW * scale));
  const outH = Math.max(1, Math.round(totalH * scale));

  const MAX_DIM = 16384;
  const partCount = Math.ceil(outH / MAX_DIM);
  const partH = Math.ceil(outH / partCount);

  const results = [];

  for (let part = 0; part < partCount; part++) {
    const partTop = part * partH;
    const partBottom = Math.min(outH, partTop + partH);
    const thisH = partBottom - partTop;

    const out = document.createElement('canvas');
    out.width = outW;
    out.height = thisH;
    const ctx = out.getContext('2d', { alpha: false });

    ctx.fillStyle = bgColor;
    ctx.fillRect(0, 0, outW, thisH);

    let curY = 0;
    for (let i = 0; i < canvases.length; i++) {
      const c = canvases[i];
      const dw = c.width * scale;
      const dh = c.height * scale;
      const pageTop = curY;
      const pageBottom = curY + dh;

      if (pageBottom >= partTop && pageTop <= partBottom) {
        const srcTop = Math.max(0, (partTop - pageTop) / scale);
        const srcBottom = Math.min(c.height, (partBottom - pageTop) / scale);
        if (srcBottom > srcTop) {
          const srcH = srcBottom - srcTop;
          const dstY = pageTop + srcTop * scale - partTop;
          const dstH = srcH * scale;
          const dx = (outW - dw) / 2;

          ctx.drawImage(
            c,
            0, srcTop, c.width, srcH,
            dx, dstY, dw, dstH
          );
        }
      }

      curY += dh + gapPx * scale;
    }

    if (onProgress) {
      onProgress(Math.round(((part + 1) / partCount) * 90));
    }

    await new Promise(r => requestAnimationFrame(r));

    const blob = await new Promise((resolve, reject) => {
      out.toBlob(b => b ? resolve(b) : reject(new Error('PNG 编码失败')), 'image/png');
    });

    out.width = 0;
    out.height = 0;

    const buf = await blob.arrayBuffer();
    results.push({
      part: part + 1,
      total: partCount,
      bytes: new Uint8Array(buf)
    });
  }

  if (onProgress) onProgress(100);
  return results;
}
