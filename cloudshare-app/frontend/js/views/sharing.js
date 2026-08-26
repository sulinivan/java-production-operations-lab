import { api } from '../api.js';
import { showToast } from '../shared.js';

/* --- Sharing Modals Managers --- */

export function openShareModal(fileId, filename) {
    const modal = document.getElementById('share-modal');
    document.getElementById('share-modal-filename').textContent = filename;
    document.getElementById('share-internal-file-id').value = fileId;
    document.getElementById('share-public-file-id').value = fileId;

    // Reset modals forms
    document.getElementById('share-internal-form').reset();
    document.getElementById('share-public-form').reset();
    document.getElementById('public-link-result-card').classList.add('hidden');

    // Force default active tabs
    document.getElementById('tab-internal-btn').click();

    modal.classList.remove('hidden');
}

export function closeShareModal() {
    document.getElementById('share-modal').classList.add('hidden');
}

export async function handleInternalShareSubmit(e) {
    e.preventDefault();
    const fileId = document.getElementById('share-internal-file-id').value;
    const target = document.getElementById('share-internal-target').value;
    const permission = document.getElementById('share-internal-permission').value;

    const btn = e.target.querySelector('button[type="submit"]');
    btn.disabled = true;

    try {
        const res = await api.shareInternal(fileId, target, permission);
        if (res.success) {
            showToast(`Shared file successfully with ${target}.`, 'success');
            closeShareModal();
        }
    } catch (err) {
        showToast(err.message, 'danger');
    } finally {
        btn.disabled = false;
    }
}

export async function handlePublicLinkSubmit(e) {
    e.preventDefault();
    const fileId = document.getElementById('share-public-file-id').value;
    const expiry = document.getElementById('share-public-expiry').value;
    const password = document.getElementById('share-public-password').value;
    const limit = document.getElementById('share-public-limit').value;

    const btn = e.target.querySelector('button[type="submit"]');
    btn.disabled = true;

    try {
        const res = await api.createPublicLink(fileId, expiry, password || null, limit || null);
        if (res.success && res.data) {
            const linkDisplay = document.getElementById('public-link-url-display');
            const resultCard = document.getElementById('public-link-result-card');

            // Formulate standard client link using location origin
            // E.g., https://localhost/#share/aB7cdX9Y
            const customShareUrl = `${window.location.origin}/#share/${res.data.shareCode}`;
            linkDisplay.value = customShareUrl;

            resultCard.classList.remove('hidden');
            showToast('Secure public link generated successfully.', 'success');
        }
    } catch (err) {
        showToast(err.message, 'danger');
    } finally {
        btn.disabled = false;
    }
}

export async function copyPublicLinkToClipboard() {
    const input = document.getElementById('public-link-url-display');
    input.select();
    input.setSelectionRange(0, 99999); // Mobile compatibility

    try {
        await navigator.clipboard.writeText(input.value);
        showToast('Link copied to clipboard.', 'success');
    } catch (e) {
        showToast('Failed to copy. Please manually select the URL.', 'warning');
    }
}

/* --- Public Shared Link view Logic --- */

export async function showPublicShareView(shareCode) {
    document.getElementById('app-shell').classList.add('hidden');
    document.getElementById('auth-gateway').classList.add('hidden');

    const gateway = document.getElementById('public-share-gateway');
    document.getElementById('public-file-code').textContent = `Link Code: ${shareCode}`;

    try {
        const res = await api.getPublicLinkInfo(shareCode);
        if (res.success && res.data) {
            if (res.data.passwordProtected) {
                document.getElementById('public-password-section').classList.remove('hidden');
            } else {
                document.getElementById('public-password-section').classList.add('hidden');
            }
            document.getElementById('public-password').value = '';
            gateway.classList.remove('hidden');
        } else {
            showToast('Access Denied: Shared link does not exist.', 'danger');
            gateway.classList.add('hidden');
        }
    } catch (err) {
        let msg = 'Access Denied: Shared link does not exist.';
        if (err.status === 403) {
            msg = 'Access Denied: Link has expired or reached download limit.';
        } else if (err.status === 404) {
            msg = 'Access Denied: Shared link does not exist.';
        }
        showToast(msg, 'danger');
        gateway.classList.add('hidden');
    }
}

export async function handlePublicDownload() {
    const hash = window.location.hash;
    const shareCode = hash.split('/')[1];
    if (!shareCode) return;

    const password = document.getElementById('public-password').value;
    const btn = document.getElementById('public-download-btn');
    btn.disabled = true;

    showToast('Initializing download...', 'info');

    try {
        await api.downloadPublicLink(shareCode, password || null);
        showToast('File downloaded successfully.', 'success');
    } catch (err) {
        let msg = err.message;
        if (err.status === 403) {
            msg = 'Access Denied: Link has expired or reached download limit.';
        } else if (err.status === 404) {
            msg = 'Access Denied: Shared link does not exist or invalid passcode.';
        }
        showToast(msg, 'danger');
    } finally {
        btn.disabled = false;
    }
}
