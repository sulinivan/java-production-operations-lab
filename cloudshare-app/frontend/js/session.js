import { api } from './api.js';
import { state } from './state.js';
import { router } from './router.js';

/**
 * Helper to decode JWT token payload on client side
 */
function parseJwt(token) {
    try {
        const base64Url = token.split('.')[1];
        const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
        const jsonPayload = decodeURIComponent(window.atob(base64).split('').map(function (c) {
            return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
        }).join(''));
        return JSON.parse(jsonPayload);
    } catch (e) {
        return null;
    }
}

/**
 * Perform initial login state verification by hitting refresh endpoint.
 * This runs silently when the user loads the app.
 */
export async function checkActiveSession() {
    try {
        await api.performTokenRefresh();

        const token = api.getAccessToken();
        if (token) {
            const claims = parseJwt(token);
            if (claims) {
                state.user = {
                    id: claims.sub,
                    username: claims.username,
                    roles: claims.roles,
                    mfaRequired: false
                };
                setupShell();
                router();
            } else {
                clearSession();
                router();
            }
        } else {
            clearSession();
            router();
        }
    } catch (e) {
        clearSession();
        router();
    }
}

/**
 * Setup layout shell elements after a successful authentication
 */
export function setupShell() {
    const appShell = document.getElementById('app-shell');
    const authGateway = document.getElementById('auth-gateway');
    const headerUsername = document.getElementById('header-username');
    const navAdminBtn = document.getElementById('nav-admin-btn');

    authGateway.classList.add('hidden');
    appShell.classList.remove('hidden');

    if (state.user) {
        headerUsername.textContent = state.user.username;

        // Role check to toggle Admin button visibility
        const isAdmin = state.user.roles && state.user.roles.includes('ROLE_ADMIN');
        if (isAdmin) {
            navAdminBtn.classList.remove('hidden');
        } else {
            navAdminBtn.classList.add('hidden');
        }
    }
}

/**
 * Tear down session data, reset tokens and routing
 */
export function clearSession() {
    state.user = null;
    api.clearTokens();

    if (state.stepUpTimer) {
        clearTimeout(state.stepUpTimer);
        state.stepUpTimer = null;
    }

    // Hide dashboard shell, show login gateway
    document.getElementById('app-shell').classList.add('hidden');
    document.getElementById('auth-gateway').classList.remove('hidden');
    document.getElementById('public-share-gateway').classList.add('hidden');

    // Reset hash to login page if it's not a public share link or the register page
    const currentHash = window.location.hash;
    if (!currentHash.startsWith('#share/') && currentHash !== '#register') {
        window.location.hash = '#login';
    }
}
