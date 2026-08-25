/**
 * CloudShare Application Bootstrap
 * Wires up the SPA: registers the API auth-failure callback, performs the
 * initial session check, and binds every DOM event listener once at startup.
 * All view logic itself lives in ./views/*.js, routing in ./router.js,
 * session lifecycle in ./session.js, and shared state in ./state.js — this
 * file intentionally contains no rendering or business logic of its own.
 */

import { api } from './api.js';
import { state } from './state.js';
import { showToast, debounce } from './shared.js';
import { checkActiveSession, clearSession } from './session.js';
import { router } from './router.js';

import { handleLoginSubmit, handleRegisterSubmit, handleLogout } from './views/auth.js';
import {
    loadFilesDashboard,
    loadSharedFiles,
    handleFilesUpload
} from './views/dashboard.js';
import {
    closeShareModal,
    handleInternalShareSubmit,
    handlePublicLinkSubmit,
    copyPublicLinkToClipboard,
    handlePublicDownload
} from './views/sharing.js';
import { handleMfaVerifySubmit } from './views/mfa.js';
import {
    handleAdminStepUpSubmit,
    cancelAdminStepUp,
    loadAdminUsers,
    loadAdminLogs
} from './views/admin.js';

// Initializer
document.addEventListener('DOMContentLoaded', () => {
    initApp();
});

function initApp() {
    // Register API Authentication Failure callback (Redirects to Login on expired sessions)
    api.registerAuthFailureCallback((message) => {
        showToast(message, 'danger');
        clearSession();
    });

    // Check if user is already logged in (try refresh first)
    checkActiveSession();

    // Bind Event Listeners (Strict compliance: no inline handlers in HTML)
    bindGlobalEvents();
}

/* --- Global Event Bindings --- */
function bindGlobalEvents() {
    // Router binds
    window.addEventListener('hashchange', router);
    window.addEventListener('load', router);

    // Nav navigation buttons
    document.getElementById('nav-dashboard-btn').addEventListener('click', () => { window.location.hash = '#dashboard'; });
    document.getElementById('nav-mfa-btn').addEventListener('click', () => { window.location.hash = '#mfa'; });
    document.getElementById('nav-admin-btn').addEventListener('click', () => { window.location.hash = '#admin'; });
    document.getElementById('nav-logout-btn').addEventListener('click', handleLogout);

    // Auth toggle buttons
    document.getElementById('switch-to-register-btn').addEventListener('click', () => { window.location.hash = '#register'; });
    document.getElementById('switch-to-login-btn').addEventListener('click', () => { window.location.hash = '#login'; });

    // Forms submissions
    document.getElementById('login-form').addEventListener('submit', handleLoginSubmit);
    document.getElementById('register-form').addEventListener('submit', handleRegisterSubmit);
    document.getElementById('admin-stepup-form').addEventListener('submit', handleAdminStepUpSubmit);
    document.getElementById('admin-stepup-cancel-btn').addEventListener('click', cancelAdminStepUp);

    // Dashboard: Files filtering, sort, and pagination
    document.getElementById('file-search-input').addEventListener('input', debounce((e) => {
        state.files.searchQuery = e.target.value;
        state.files.page = 0;
        loadFilesDashboard();
    }, 300));
    document.getElementById('file-sort-select').addEventListener('change', (e) => {
        state.files.sort = e.target.value;
        state.files.page = 0;
        loadFilesDashboard();
    });
    document.getElementById('files-prev-btn').addEventListener('click', () => {
        if (state.files.page > 0) {
            state.files.page--;
            loadFilesDashboard();
        }
    });
    document.getElementById('files-next-btn').addEventListener('click', () => {
        if (state.files.page < state.files.totalPages - 1) {
            state.files.page++;
            loadFilesDashboard();
        }
    });

    document.getElementById('shared-prev-btn').addEventListener('click', () => {
        if (state.sharedFiles.page > 0) {
            state.sharedFiles.page--;
            loadSharedFiles();
        }
    });
    document.getElementById('shared-next-btn').addEventListener('click', () => {
        if (state.sharedFiles.page < state.sharedFiles.totalPages - 1) {
            state.sharedFiles.page++;
            loadSharedFiles();
        }
    });

    // Dashboard: Drag and Drop Upload
    const dropZone = document.getElementById('drop-zone');
    const fileInput = document.getElementById('file-input');

    dropZone.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', (e) => {
        handleFilesUpload(e.target.files);
    });

    ['dragenter', 'dragover'].forEach(eventName => {
        dropZone.addEventListener(eventName, (e) => {
            e.preventDefault();
            dropZone.classList.add('drag-active');
        }, false);
    });

    ['dragleave', 'drop'].forEach(eventName => {
        dropZone.addEventListener(eventName, (e) => {
            e.preventDefault();
            dropZone.classList.remove('drag-active');
        }, false);
    });

    dropZone.addEventListener('drop', (e) => {
        const dt = e.dataTransfer;
        const files = dt.files;
        handleFilesUpload(files);
    }, false);

    // Modals Sharing close trigger
    document.getElementById('share-modal-close-btn').addEventListener('click', closeShareModal);

    // Sharing modal tabs toggle
    const tabInternalBtn = document.getElementById('tab-internal-btn');
    const tabPublicBtn = document.getElementById('tab-public-btn');
    const tabInternalContent = document.getElementById('tab-internal-content');
    const tabPublicContent = document.getElementById('tab-public-content');

    tabInternalBtn.addEventListener('click', () => {
        tabInternalBtn.classList.add('active');
        tabPublicBtn.classList.remove('active');
        tabInternalContent.classList.remove('hidden');
        tabPublicContent.classList.add('hidden');
    });

    tabPublicBtn.addEventListener('click', () => {
        tabPublicBtn.classList.add('active');
        tabInternalBtn.classList.remove('active');
        tabPublicContent.classList.remove('hidden');
        tabInternalContent.classList.add('hidden');
    });

    // Sharing Submit Action Forms
    document.getElementById('share-internal-form').addEventListener('submit', handleInternalShareSubmit);
    document.getElementById('share-public-form').addEventListener('submit', handlePublicLinkSubmit);
    document.getElementById('copy-public-link-btn').addEventListener('click', copyPublicLinkToClipboard);

    // MFA Configuration Forms
    document.getElementById('mfa-verify-form').addEventListener('submit', handleMfaVerifySubmit);

    // Admin Logs Filtering & Pagination
    document.getElementById('admin-logs-user-filter').addEventListener('input', debounce((e) => {
        state.admin.logs.userIdFilter = e.target.value.trim();
        state.admin.logs.page = 0;
        loadAdminLogs();
    }, 300));
    document.getElementById('admin-logs-action-filter').addEventListener('change', (e) => {
        state.admin.logs.actionFilter = e.target.value;
        state.admin.logs.page = 0;
        loadAdminLogs();
    });

    document.getElementById('admin-users-prev-btn').addEventListener('click', () => {
        if (state.admin.users.page > 0) {
            state.admin.users.page--;
            loadAdminUsers();
        }
    });
    document.getElementById('admin-users-next-btn').addEventListener('click', () => {
        if (state.admin.users.page < state.admin.users.totalPages - 1) {
            state.admin.users.page++;
            loadAdminUsers();
        }
    });

    document.getElementById('admin-logs-prev-btn').addEventListener('click', () => {
        if (state.admin.logs.page > 0) {
            state.admin.logs.page--;
            loadAdminLogs();
        }
    });
    document.getElementById('admin-logs-next-btn').addEventListener('click', () => {
        if (state.admin.logs.page < state.admin.logs.totalPages - 1) {
            state.admin.logs.page++;
            loadAdminLogs();
        }
    });

    // Public share View events
    document.getElementById('public-download-btn').addEventListener('click', handlePublicDownload);
}
