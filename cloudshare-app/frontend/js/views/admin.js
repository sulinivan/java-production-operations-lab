import { api } from '../api.js';
import { state } from '../state.js';
import { showToast, getActionBadgeClass } from '../shared.js';
import { router } from '../router.js';

/* --- Admin panel logic & step-up timer --- */

export function triggerAdminStepUp() {
    document.getElementById('admin-stepup-code').value = '';
    document.getElementById('admin-stepup-modal').classList.remove('hidden');
}

export function cancelAdminStepUp() {
    document.getElementById('admin-stepup-modal').classList.add('hidden');
    // Bounce user back to dashboard view
    window.location.hash = '#dashboard';
}

export async function handleAdminStepUpSubmit(e) {
    e.preventDefault();
    const code = document.getElementById('admin-stepup-code').value;
    const btn = e.target.querySelector('button[type="submit"]');
    btn.disabled = true;

    try {
        const res = await api.stepUpMfa(code);
        if (res.success && res.data) {
            document.getElementById('admin-stepup-modal').classList.add('hidden');

            showToast('Administrative authorization verified.', 'success');

            // Configure automatic step-up expiration timer using response TTL
            const ttlSeconds = res.data.expiresInSeconds || 300;

            if (state.stepUpTimer) {
                clearTimeout(state.stepUpTimer);
            }

            state.stepUpTimer = setTimeout(() => {
                api.setStepUpToken(null);
                showToast('Administrative session expired. Step-up authorization required.', 'warning');
                if (window.location.hash === '#admin') {
                    router(); // Re-route to trigger step-up prompt
                }
            }, ttlSeconds * 1000);

            // Access granted - reload router views
            router();
        }
    } catch (err) {
        showToast(`Verification failed: ${err.message}`, 'danger');
    } finally {
        btn.disabled = false;
        document.getElementById('admin-stepup-code').value = '';
    }
}

export async function loadAdminPanel() {
    state.admin.users.page = 0;
    state.admin.logs.page = 0;

    await loadAdminUsers();
    await loadAdminLogs();
}

export async function loadAdminUsers() {
    try {
        const res = await api.listUsers(state.admin.users.page, 10);
        if (res.success && res.data) {
            state.admin.users.content = res.data.content || [];
            state.admin.users.totalPages = res.data.totalPages || 1;
            renderAdminUsers();
        }
    } catch (err) {
        showToast(`Failed to load users: ${err.message}`, 'danger');
    }
}

export function renderAdminUsers() {
    const tbody = document.getElementById('admin-users-tbody');
    tbody.textContent = '';

    if (state.admin.users.content.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 6;
        td.className = 'text-center text-muted';
        td.textContent = 'No registered users found.';
        tr.appendChild(td);
        tbody.appendChild(tr);
        return;
    }

    // Render safely using DOM creator logic
    state.admin.users.content.forEach(user => {
        const tr = document.createElement('tr');

        const tdId = document.createElement('td');
        tdId.textContent = user.id;

        const tdUser = document.createElement('td');
        tdUser.textContent = user.username;

        const tdEmail = document.createElement('td');
        tdEmail.textContent = user.email;

        const tdRoles = document.createElement('td');
        tdRoles.textContent = user.roles ? user.roles.join(', ') : 'ROLE_USER';

        const tdMfa = document.createElement('td');
        tdMfa.textContent = user.mfaEnabled ? '🛡️ Enabled' : '❌ Disabled';
        if (user.mfaEnabled) tdMfa.className = 'text-success';

        const tdJoined = document.createElement('td');
        tdJoined.textContent = new Date(user.createdAt).toLocaleDateString();

        tr.appendChild(tdId);
        tr.appendChild(tdUser);
        tr.appendChild(tdEmail);
        tr.appendChild(tdRoles);
        tr.appendChild(tdMfa);
        tr.appendChild(tdJoined);
        tbody.appendChild(tr);
    });

    // Pagination updates
    document.getElementById('admin-users-prev-btn').disabled = state.admin.users.page === 0;
    document.getElementById('admin-users-next-btn').disabled = state.admin.users.page >= state.admin.users.totalPages - 1;
    document.getElementById('admin-users-page-info').textContent = `Page ${state.admin.users.page + 1} of ${state.admin.users.totalPages}`;
}

export async function loadAdminLogs() {
    try {
        const filterUser = state.admin.logs.userIdFilter || null;
        const filterAction = state.admin.logs.actionFilter || null;

        const res = await api.getAuditLogs(state.admin.logs.page, 15, filterUser, filterAction);
        if (res.success && res.data) {
            state.admin.logs.content = res.data.content || [];
            state.admin.logs.totalPages = res.data.totalPages || 1;
            renderAdminLogs();
        }
    } catch (err) {
        showToast(`Failed to load audit logs: ${err.message}`, 'danger');
    }
}

export function renderAdminLogs() {
    const tbody = document.getElementById('admin-logs-tbody');
    tbody.textContent = '';

    if (state.admin.logs.content.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 5;
        td.className = 'text-center text-muted';
        td.textContent = 'No matching audit logs found.';
        tr.appendChild(td);
        tbody.appendChild(tr);
        return;
    }

    state.admin.logs.content.forEach(log => {
        const tr = document.createElement('tr');

        const tdTime = document.createElement('td');
        tdTime.textContent = new Date(log.createdAt).toLocaleString();

        const tdUser = document.createElement('td');
        tdUser.textContent = log.userId ? log.userId : 'SYSTEM / ANONYMOUS';

        const tdAction = document.createElement('td');
        const badge = document.createElement('span');
        badge.className = `badge-action ${getActionBadgeClass(log.action)}`;
        badge.textContent = log.action;
        tdAction.appendChild(badge);

        const tdIp = document.createElement('td');
        tdIp.textContent = log.ipAddress || '---';

        const tdDetails = document.createElement('td');
        tdDetails.textContent = log.details || '';

        tr.appendChild(tdTime);
        tr.appendChild(tdUser);
        tr.appendChild(tdAction);
        tr.appendChild(tdIp);
        tr.appendChild(tdDetails);
        tbody.appendChild(tr);
    });

    // Pagination updates
    document.getElementById('admin-logs-prev-btn').disabled = state.admin.logs.page === 0;
    document.getElementById('admin-logs-next-btn').disabled = state.admin.logs.page >= state.admin.logs.totalPages - 1;
    document.getElementById('admin-logs-page-info').textContent = `Page ${state.admin.logs.page + 1} of ${state.admin.logs.totalPages}`;
}
