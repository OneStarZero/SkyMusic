export function initRandomBackground() {
    const canvas = document.getElementById('bgCanvas');
    if (!canvas) return;
    const ctx = canvas.getContext('2d', { alpha: false });
    let width, height;
    let animationId = null;

    // 完全随机生成治愈系颜色
    function generateRandomTheme() {
        const baseHue = 180 + Math.random() * 160; 
        return {
            bgTop: `hsl(${baseHue}, 40%, 8%)`,
            bgBottom: `hsl(${(baseHue + 20) % 360}, 50%, 15%)`,
            nebulas: [
                `hsla(${baseHue}, 80%, 60%, 0.25)`,
                `hsla(${(baseHue + 40) % 360}, 85%, 65%, 0.2)`,
                `hsla(${(baseHue - 30 + 360) % 360}, 80%, 60%, 0.15)`
            ],
            star: `hsl(${(baseHue + 180) % 360}, 100%, 90%)`,
            particle: `hsl(${(baseHue + 60) % 360}, 100%, 70%)`,
            aurora: `hsla(${(baseHue + 90) % 360}, 80%, 70%, 0.08)`
        };
    }

    let theme = generateRandomTheme();
    let bgGrad, nebulaCanvas;
    let stars = [], particles = [], meteors = [], auroras = [];

    function init() {
        width = canvas.width = window.innerWidth;
        height = canvas.height = window.innerHeight;

        bgGrad = ctx.createLinearGradient(0, 0, 0, height);
        bgGrad.addColorStop(0, theme.bgTop);
        bgGrad.addColorStop(1, theme.bgBottom);

        // 🌟 离屏渲染星云，大大节省 CPU
        nebulaCanvas = document.createElement('canvas');
        nebulaCanvas.width = width;
        nebulaCanvas.height = height;
        const nCtx = nebulaCanvas.getContext('2d');
        
        for (let i = 0; i < 5; i++) {
            const x = Math.random() * width;
            const y = Math.random() * height;
            const radius = Math.random() * 250 + 150;
            const color = theme.nebulas[i % theme.nebulas.length];
            
            const radGrad = nCtx.createRadialGradient(x, y, 0, x, y, radius);
            radGrad.addColorStop(0, color);
            radGrad.addColorStop(1, 'transparent');
            nCtx.fillStyle = radGrad;
            nCtx.beginPath();
            nCtx.arc(x, y, radius, 0, Math.PI * 2);
            nCtx.fill();
        }

        stars = [];
        const starCount = Math.floor((width * height) / 6000);
        for (let i = 0; i < starCount; i++) {
            stars.push({
                x: Math.random() * width, y: Math.random() * height,
                size: Math.random() * 1.5 + 0.5,
                alpha: Math.random(), vAlpha: (Math.random() - 0.5) * 0.05,
                isBig: Math.random() > 0.92
            });
        }

        particles = [];
        for (let i = 0; i < 20; i++) {
            particles.push({
                x: Math.random() * width, y: Math.random() * height,
                size: Math.random() * 1.5 + 1,
                vy: -(Math.random() * 0.3 + 0.1),
                vx: (Math.random() - 0.5) * 0.2,
                alpha: Math.random(), vAlpha: (Math.random() - 0.5) * 0.02
            });
        }

        auroras = [];
        for (let i = 0; i < 2; i++) {
            auroras.push({
                offset: Math.random() * 1000, speed: Math.random() * 0.002 + 0.001,
                amplitude: Math.random() * 80 + 50, y: height * (Math.random() * 0.3 + 0.5),
                color: theme.aurora
            });
        }
    }

    // 限制 30 帧，肉眼看起来依然丝滑，但计算量减半
    let lastTime = 0;
    const interval = 1000 / 30;

    function draw(time) {
        animationId = requestAnimationFrame(draw);
        
        if (time - lastTime < interval) return;
        lastTime = time;

        ctx.fillStyle = bgGrad;
        ctx.fillRect(0, 0, width, height);

        // 铺离屏星云
        ctx.globalCompositeOperation = 'screen';
        ctx.drawImage(nebulaCanvas, 0, 0);

        // 画极光
        auroras.forEach(a => {
            a.offset += a.speed;
            ctx.beginPath();
            ctx.moveTo(0, a.y);
            for (let x = 0; x <= width; x += 30) {
                let y = a.y + Math.sin(x * 0.004 + a.offset) * a.amplitude;
                ctx.lineTo(x, y);
            }
            ctx.lineTo(width, height);
            ctx.lineTo(0, height);
            ctx.closePath();
            ctx.fillStyle = a.color;
            ctx.fill();
        });

        // 画星星
        ctx.globalCompositeOperation = 'source-over';
        ctx.fillStyle = theme.star;
        stars.forEach(s => {
            s.alpha += s.vAlpha;
            if (s.alpha > 1 || s.alpha < 0.1) s.vAlpha *= -1;
            ctx.globalAlpha = s.alpha;
            
            ctx.beginPath();
            ctx.arc(s.x, s.y, s.size, 0, Math.PI * 2);
            ctx.fill();
            
            if (s.isBig) {
                ctx.fillRect(s.x - s.size * 2.5, s.y - 0.5, s.size * 5, 1);
                ctx.fillRect(s.x - 0.5, s.y - s.size * 2.5, 1, s.size * 5);
            }
        });
        ctx.globalAlpha = 1.0;

        // 画萤火光点
        ctx.fillStyle = theme.particle;
        particles.forEach(p => {
            p.alpha += p.vAlpha;
            if (p.alpha > 0.8 || p.alpha < 0.2) p.vAlpha *= -1;
            p.y += p.vy; p.x += p.vx;
            if (p.y < -10) { p.y = height + 10; p.x = Math.random() * width; }
            
            ctx.globalAlpha = p.alpha;
            ctx.beginPath();
            ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
            ctx.fill();
        });
        ctx.globalAlpha = 1.0;

        // 🌟 流星修复：方向改为从右上往左下，产生自然的斜落
        if (Math.random() < 0.015 && meteors.length < 2) {
            meteors.push({
                x: width + 50, 
                y: Math.random() * height * 0.4, // 从上半部分出现
                len: 150, 
                speed: 15, 
                angle: Math.PI / 4 + (Math.random() - 0.5) * 0.2, // 约 45 度偏左下
                life: 1.0
            });
        }
        
        for (let i = meteors.length - 1; i >= 0; i--) {
            const m = meteors[i];
            ctx.globalAlpha = m.life;
            const tx = m.x - Math.cos(m.angle) * m.len;
            const ty = m.y + Math.sin(m.angle) * m.len; // y 是加号，向下落
            
            const grad = ctx.createLinearGradient(m.x, m.y, tx, ty);
            grad.addColorStop(0, theme.star);
            grad.addColorStop(1, 'transparent');
            ctx.strokeStyle = grad;
            ctx.lineWidth = 1.5;
            ctx.beginPath();
            ctx.moveTo(m.x, m.y);
            ctx.lineTo(tx, ty);
            ctx.stroke();

            m.x -= Math.cos(m.angle) * m.speed;
            m.y += Math.sin(m.angle) * m.speed; // y 增加，向下落
            m.life -= 0.02;
            if (m.life <= 0 || m.x < -100 || m.y > height + 100) meteors.splice(i, 1);
        }
        ctx.globalAlpha = 1.0;
    }

    window.addEventListener('resize', init);
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) {
            if (animationId) cancelAnimationFrame(animationId);
        } else {
            lastTime = performance.now();
            animationId = requestAnimationFrame(draw);
        }
    });

    init();
    animationId = requestAnimationFrame(draw);
}