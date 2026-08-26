/**
 * Pure UI utility helpers with no dependency on application state or the API
 * client. Safe to import from any other module without creating a cycle.
 */

export function showToast(message, type = 'info') {
    const container = document.getElementById('notification-container');

    const toast = document.createElement('div');
    toast.className = `toast ${type}`;

    const textSpan = document.createElement('span');
    textSpan.textContent = message;

    const closeBtn = document.createElement('button');
    closeBtn.type = 'button';
    closeBtn.className = 'toast-close';
    closeBtn.setAttribute('aria-label', 'Dismiss notification');
    closeBtn.textContent = '×';
    closeBtn.addEventListener('click', () => {
        toast.remove();
    });

    toast.appendChild(textSpan);
    toast.appendChild(closeBtn);
    container.appendChild(toast);

    // Auto cleanup after 5 seconds
    setTimeout(() => {
        if (toast.parentNode) {
            toast.remove();
        }
    }, 5000);
}

export function getFileEmoji(filename) {
    const ext = filename.split('.').pop().toLowerCase();
    switch (ext) {
        case 'pdf': return '📕';
        case 'doc':
        case 'docx': return '📘';
        case 'xls':
        case 'xlsx': return '📗';
        case 'png':
        case 'jpg':
        case 'jpeg':
        case 'gif': return '🖼️';
        case 'zip':
        case 'rar':
        case 'tar':
        case 'gz': return '📦';
        case 'txt': return '📄';
        default: return '📄';
    }
}

export function formatBytes(bytes, decimals = 2) {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const dm = decimals < 0 ? 0 : decimals;
    const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
}

export function getActionBadgeClass(action) {
    if (action.includes('SUCCESS') || action.includes('ENABLED')) {
        return 'badge-success';
    }
    if (action.includes('FAILED') || action.includes('VIRUS')) {
        return 'badge-failed';
    }
    if (action.includes('REKEY')) {
        return 'badge-warning';
    }
    return 'badge-info';
}

export function debounce(func, delay) {
    let timeout;
    return function (...args) {
        clearTimeout(timeout);
        timeout = setTimeout(() => func.apply(this, args), delay);
    };
}
