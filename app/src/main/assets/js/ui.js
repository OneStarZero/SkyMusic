export function showToast(message, type = 'info') {
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    document.getElementById('toast-container').appendChild(toast);
    setTimeout(() => toast.remove(), 3000);
}

export function initTabs() {
    const tabs = [
        { btn: document.getElementById('tabMidi'), view: document.getElementById('viewMidi'), cls: 'active-midi' },
        { btn: document.getElementById('tabOcr'), view: document.getElementById('viewOcr'), cls: 'active-ocr' },
        { btn: document.getElementById('tabAudio'), view: document.getElementById('viewAudio'), cls: 'active-audio' }
    ];

    const setActive = (activeIdx) => {
        tabs.forEach((t, i) => {
            if (i === activeIdx) {
                t.btn.className = `tab-btn ${t.cls}`;
                t.view.style.display = 'block';
            } else {
                t.btn.className = 'tab-btn inactive';
                t.view.style.display = 'none';
            }
        });
    };

    tabs.forEach((t, i) => t.btn.addEventListener('click', () => setActive(i)));
}

window.onAppReady = function() {
    const loader = document.getElementById('app-loader');
    if (loader) {
        loader.style.opacity = '0';
        setTimeout(() => loader.remove(), 300);
    }
};
setTimeout(() => { if(window.onAppReady) window.onAppReady(); }, 1500);