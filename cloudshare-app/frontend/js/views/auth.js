import { api } from '../api.js';
import { state } from '../state.js';
import { showToast } from '../shared.js';
import { setupShell, clearSession } from '../session.js';
import { showAuthCard } from '../router.js';

/* --- Authentication actions --- */

export async function handleLoginSubmit(e) {
    e.preventDefault();
    const usernameOrEmail = document.getElementById('login-username').value;
    const password = document.getElementById('login-password').value;
    const mfaCode = document.getElementById('login-mfa').value;

    const btn = e.target.querySelector('button[type="submit"]');
    btn.disabled = true;

    try {
        const res = await api.login(usernameOrEmail, password, mfaCode || null);
        if (res.success && res.data) {
            state.user = res.data.user;
            setupShell();
            window.location.hash = '#dashboard';
            showToast(`Welcome back, ${state.user.username}!`, 'success');

            // Clear inputs
            document.getElementById('login-username').value = '';
            document.getElementById('login-password').value = '';
            document.getElementById('login-mfa').value = '';
        }
    } catch (err) {
        showToast(err.message, 'danger');
    } finally {
        btn.disabled = false;
    }
}

export async function handleRegisterSubmit(e) {
    e.preventDefault();
    const username = document.getElementById('register-username').value;
    const email = document.getElementById('register-email').value;
    const password = document.getElementById('register-password').value;

    const btn = e.target.querySelector('button[type="submit"]');
    btn.disabled = true;

    try {
        const res = await api.register(username, email, password);
        if (res.success) {
            showToast('Account registered successfully. Please sign in.', 'success');
            showAuthCard('login');

            // Populate login card username
            document.getElementById('login-username').value = username;

            // Clear registration inputs
            document.getElementById('register-username').value = '';
            document.getElementById('register-email').value = '';
            document.getElementById('register-password').value = '';
        }
    } catch (err) {
        showToast(err.message, 'danger');
    } finally {
        btn.disabled = false;
    }
}

export async function handleLogout() {
    try {
        await api.logout();
        showToast('Logout successful', 'success');
    } catch (err) {
        // Ignored, state cleared regardless
    } finally {
        clearSession();
    }
}
