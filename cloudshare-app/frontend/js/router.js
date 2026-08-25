import { api } from './api.js';
import { state } from './state.js';
import { showToast } from './shared.js';
import { loadFilesDashboard } from './views/dashboard.js';
import { loadMfaSettings } from './views/mfa.js';
import { loadAdminPanel, triggerAdminStepUp } from './views/admin.js';
import { showPublicShareView } from './views/sharing.js';

// NOTE on circular import: views/admin.js imports `router` from this module
// (handleAdminStepUpSubmit needs to force an immediate re-route after a
// successful step-up, and the step-up expiry timer needs to re-route back to
// the step-up prompt). That is safe here specifically because every usage on
// both sides is inside a function body, called later at runtime — never
// evaluated at module-load time — which is the standard safe pattern for ES
// module cycles. If either side starts referencing the other at the top
// level of the module (e.g. as a default export value computed at import
// time), this cycle would break.

/* --- SPA Router --- */
export function router() {
    const hash = window.location.hash || '#dashboard';

    // De-activate all view sections and navigation buttons
    document.querySelectorAll('.app-view').forEach(view => view.classList.add('hidden'));
    document.querySelectorAll('.nav-btn').forEach(btn => btn.classList.remove('active'));

    // Handle Public Share route first (independent of authentication)
    if (hash.startsWith('#share/')) {
        const shareCode = hash.split('/')[1];
        if (shareCode) {
            showPublicShareView(shareCode);
            return;
        }
    }

    // Protect regular views - Redirect to login if anonymous
    if (!api.getAccessToken()) {
        showAuthCard(hash === '#register' ? 'register' : 'login');
        return;
    }

    // Standard logged-in views
    if (hash === '#dashboard') {
        document.getElementById('view-dashboard').classList.remove('hidden');
        document.getElementById('nav-dashboard-btn').classList.add('active');
        loadFilesDashboard();
    } else if (hash === '#mfa') {
        document.getElementById('view-mfa').classList.remove('hidden');
        document.getElementById('nav-mfa-btn').classList.add('active');
        loadMfaSettings();
    } else if (hash === '#admin') {
        // Enforce Admin role restriction
        const isAdmin = state.user.roles && state.user.roles.includes('ROLE_ADMIN');
        if (!isAdmin) {
            window.location.hash = '#dashboard';
            showToast('Access Denied: Administrative privileges required.', 'danger');
            return;
        }

        // Verify Step-up Token state
        if (!api.getStepUpToken()) {
            triggerAdminStepUp();
        } else {
            document.getElementById('view-admin').classList.remove('hidden');
            document.getElementById('nav-admin-btn').classList.add('active');
            loadAdminPanel();
        }
    } else {
        // Default Fallback
        window.location.hash = '#dashboard';
    }
}

export function showAuthCard(cardType) {
    document.getElementById('app-shell').classList.add('hidden');
    document.getElementById('public-share-gateway').classList.add('hidden');
    document.getElementById('auth-gateway').classList.remove('hidden');

    if (cardType === 'register') {
        document.getElementById('auth-login-card').classList.add('hidden');
        document.getElementById('auth-register-card').classList.remove('hidden');
    } else {
        document.getElementById('auth-login-card').classList.remove('hidden');
        document.getElementById('auth-register-card').classList.add('hidden');
    }
}
