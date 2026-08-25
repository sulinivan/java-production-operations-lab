import { api } from '../api.js';
import { state } from '../state.js';
import { showToast } from '../shared.js';

/* --- Multi-Factor Authentication Logic --- */

export async function loadMfaSettings() {
    const banner = document.getElementById('mfa-status-banner');
    const setupSection = document.getElementById('mfa-setup-section');
    const activeSection = document.getElementById('mfa-active-section');

    // Clean previous setup info
    banner.textContent = '';
    setupSection.classList.add('hidden');
    activeSection.classList.add('hidden');

    try {
        // Probe initMfa: if MFA is already enabled, the backend returns 400 Bad Request
        const res = await api.initMfa();

        // If it succeeded, MFA is not enabled yet, show setup details
        state.user.mfaRequired = false;
        banner.className = 'mfa-status-banner disabled';
        banner.textContent = '⚠️ Multi-Factor Authentication is currently Disabled. Enable it to secure your files.';
        setupSection.classList.remove('hidden');

        document.getElementById('mfa-qr-img').src = res.data.qrCodeDataUri;
        document.getElementById('mfa-secret-text').textContent = res.data.secret;
    } catch (err) {
        // If it failed because MFA is already enabled, display active state
        if (err.status === 400 || (err.message && err.message.includes('already enabled'))) {
            state.user.mfaRequired = true;
            banner.className = 'mfa-status-banner enabled';
            banner.textContent = '🔒 TOTP Multi-Factor Authentication is currently Active';
            activeSection.classList.remove('hidden');
        } else {
            showToast(`Failed to initialize MFA settings: ${err.message}`, 'danger');
        }
    }
}

// NOTE: not currently called anywhere (confirmed unused in the pre-split app.js too —
// preserved as-is rather than removed, since dropping unused code wasn't part of this
// module-split's scope).
export async function initializeMfaSetup() {
    try {
        const res = await api.initMfa();
        if (res.success && res.data) {
            document.getElementById('mfa-qr-img').src = res.data.qrCodeDataUri;
            document.getElementById('mfa-secret-text').textContent = res.data.secret;
        }
    } catch (err) {
        showToast(`Failed to initialize MFA: ${err.message}`, 'danger');
    }
}

export async function handleMfaVerifySubmit(e) {
    e.preventDefault();
    const code = document.getElementById('mfa-verification-code').value;
    const btn = e.target.querySelector('button[type="submit"]');
    btn.disabled = true;

    try {
        const res = await api.verifyMfa(code);
        if (res.success) {
            showToast('Multi-Factor Authentication enabled successfully!', 'success');

            // Update local user state
            state.user.mfaRequired = true;

            // Refresh MFA panel
            loadMfaSettings();
        }
    } catch (err) {
        showToast(err.message, 'danger');
    } finally {
        btn.disabled = false;
        document.getElementById('mfa-verification-code').value = '';
    }
}
