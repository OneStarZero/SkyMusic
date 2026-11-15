// js/ui.js
export function showToast(message, type = 'info') {
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    document.getElementById('toast-container').appendChild(toast);
    setTimeout(() => toast.remove(), 3000);
}

export function initTabs() {
    const tabMidi = document.getElementById('tabMidi');
    const tabOcr = document.getElementById('tabOcr');
    const viewMidi = document.getElementById('viewMidi');
    const viewOcr = document.getElementById('viewOcr');

    tabMidi.addEventListener('click', () => {
        tabMidi.className = 'tab-btn active-midi';
        tabOcr.className = 'tab-btn inactive';
        viewMidi.style.display = 'block';
        viewOcr.style.display = 'none';
    });

    tabOcr.addEventListener('click', () => {
        tabOcr.className = 'tab-btn active-ocr';
        tabMidi.className = 'tab-btn inactive';
        viewMidi.style.display = 'none';
        viewOcr.style.display = 'block';
    });
}