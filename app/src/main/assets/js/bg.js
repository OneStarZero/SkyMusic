export function initRandomBackground() {
    const canvas = document.getElementById('bgCanvas');
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    let width, height;
    let glows = [];
    let stars = [];
    let currentTheme = null;

    // 定义 4 种治愈系主题（底色渐变 + 高亮光晕）
    const themes = [
        { // 晨岛蓝
            base: ['#0B1120', '#1E1B4B'], 
            glows: ['rgba(125, 211, 252, 0.4)', 'rgba(56, 189, 248, 0.3)'] 
        },
        { // 星云紫
            base: ['#0B0F19', '#2E1065'], 
            glows: ['rgba(167, 139, 250, 0.45)', 'rgba(139, 92, 246, 0.3)'] 
        },
        { // 霞谷金
            base: ['#0B1120', '#451A03'], 
            glows: ['rgba(253, 224, 71, 0.3)', 'rgba(245, 158, 11, 0.2)'] 
        },
        { // 雨林绿
            base: ['#08121A', '#064E3B'], 
            glows: ['rgba(52, 211, 153, 0.35)', 'rgba(16, 185, 129, 0.25)'] 
        }
    ];

    function resize() {
        width = canvas.width = window.innerWidth;
        height = canvas.height = window.innerHeight;
        initElements();
    }

    function initElements() {
        // 1. 每次刷新随机挑选一个主题
        currentTheme = themes[Math.floor(Math.random() * themes.length)];

        glows = [];
        // 2. 生成 3~4 个大光晕，位置和大小大幅随机
        const glowCount = Math.floor(Math.random() * 2) + 3;
        for (let i = 0; i < glowCount; i++) {
            glows.push({
                x: Math.random() * width,
                y: Math.random() * height,
                radius: Math.random() * 300 + 250, // 光晕更大更明显
                color: currentTheme.glows[Math.floor(Math.random() * currentTheme.glows.length)],
                vx: (Math.random() - 0.5) * 0.6, // 漂移速度稍快一点点
                vy: (Math.random() - 0.5) * 0.6
            });
        }

        // 3. 生成星星
        stars = [];
        const starCount = Math.floor((width * height) / 5000); 
        for (let i = 0; i < starCount; i++) {
            stars.push({
                x: Math.random() * width,
                y: Math.random() * height,
                size: Math.random() * 1.8 + 0.3,
                alpha: Math.random(),
                vAlpha: (Math.random() - 0.5) * 0.04 // 闪烁频率随机
            });
        }
    }

    function draw() {
        // 1. 渲染随机主题的底色渐变
        const bgGrad = ctx.createLinearGradient(0, 0, 0, height);
        bgGrad.addColorStop(0, currentTheme.base[0]);
        bgGrad.addColorStop(1, currentTheme.base[1]);
        ctx.fillStyle = bgGrad;
        ctx.fillRect(0, 0, width, height);

        // 2. 渲染漂移光晕 (使用发光叠加模式)
        ctx.globalCompositeOperation = 'screen';
        glows.forEach(g => {
            const radGrad = ctx.createRadialGradient(g.x, g.y, 0, g.x, g.y, g.radius);
            radGrad.addColorStop(0, g.color);
            radGrad.addColorStop(1, 'transparent');
            ctx.fillStyle = radGrad;
            ctx.beginPath();
            ctx.arc(g.x, g.y, g.radius, 0, Math.PI * 2);
            ctx.fill();

            // 光晕移动逻辑
            g.x += g.vx;
            g.y += g.vy;
            // 边界碰撞回弹
            if (g.x < -g.radius || g.x > width + g.radius) g.vx *= -1;
            if (g.y < -g.radius || g.y > height + g.radius) g.vy *= -1;
        });

        // 3. 渲染闪烁星星 + 大星星的星芒效果
        ctx.globalCompositeOperation = 'source-over';
        stars.forEach(s => {
            s.alpha += s.vAlpha;
            if (s.alpha > 1 || s.alpha < 0.1) s.vAlpha *= -1; // 闪烁
            
            ctx.fillStyle = `rgba(255, 255, 255, ${s.alpha})`;
            
            // 大星星带星芒（十字交叉）
            if (s.size > 1.2) {
                ctx.beginPath();
                ctx.arc(s.x, s.y, s.size, 0, Math.PI * 2);
                ctx.fill();
                // 横向星芒
                ctx.fillRect(s.x - s.size * 2.5, s.y - 0.5, s.size * 5, 1);
                // 纵向星芒
                ctx.fillRect(s.x - 0.5, s.y - s.size * 2.5, 1, s.size * 5);
            } else {
                // 小星星单纯圆点
                ctx.beginPath();
                ctx.arc(s.x, s.y, s.size, 0, Math.PI * 2);
                ctx.fill();
            }
        });

        requestAnimationFrame(draw);
    }

    window.addEventListener('resize', resize);
    resize();
    draw();
}