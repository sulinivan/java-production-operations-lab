import { api } from '../api.js';
import { state } from '../state.js';
import { showToast, getFileEmoji, formatBytes } from '../shared.js';
import { openShareModal } from './sharing.js';

/* --- Files Dashboard Logic --- */

export async function loadFilesDashboard() {
    try {
        const res = await api.listFiles(state.files.page, state.files.size, state.files.sort);
        if (res.success && res.data) {
            state.files.content = res.data.content || [];
            state.files.totalPages = res.data.totalPages || 1;
            renderFilesTable();
        }
    } catch (err) {
        showToast(`Failed to load files: ${err.message}`, 'danger');
    }
    loadSharedFiles();
}

export async function loadSharedFiles() {
    try {
        const res = await api.listSharedWithMe(state.sharedFiles.page, state.sharedFiles.size);
        if (res.success && res.data) {
            state.sharedFiles.content = res.data.content || [];
            state.sharedFiles.totalPages = res.data.totalPages || 1;
            renderSharedFilesTable();
        }
    } catch (err) {
        showToast(`Failed to load shared files: ${err.message}`, 'danger');
    }
}

export function renderFilesTable() {
    const tbody = document.getElementById('files-tbody');
    const emptyState = document.getElementById('files-empty-state');

    // Clear rows safely
    tbody.textContent = '';

    // Filter files based on client-side search query
    let filteredFiles = state.files.content;
    if (state.files.searchQuery) {
        const query = state.files.searchQuery.toLowerCase();
        filteredFiles = filteredFiles.filter(file => file.name.toLowerCase().includes(query));
    }

    if (filteredFiles.length === 0) {
        emptyState.classList.remove('hidden');
        document.getElementById('files-prev-btn').disabled = true;
        document.getElementById('files-next-btn').disabled = true;
        document.getElementById('files-page-info').textContent = 'Page 1 of 1';
        return;
    }

    emptyState.classList.add('hidden');

    // Render files safely (XSS defense: use textContent and element construction)
    filteredFiles.forEach(file => {
        const tr = document.createElement('tr');

        // File name & Icon
        const tdName = document.createElement('td');
        const fileDiv = document.createElement('div');
        fileDiv.className = 'file-name-cell';

        const fileIcon = document.createElement('span');
        fileIcon.className = 'file-icon-s';
        fileIcon.textContent = getFileEmoji(file.name);

        const nameSpan = document.createElement('span');
        nameSpan.className = 'file-name-text';
        nameSpan.textContent = file.name;

        fileDiv.appendChild(fileIcon);
        fileDiv.appendChild(nameSpan);
        tdName.appendChild(fileDiv);

        // Size
        const tdSize = document.createElement('td');
        tdSize.textContent = formatBytes(file.sizeBytes);

        // Upload Time
        const tdTime = document.createElement('td');
        tdTime.textContent = new Date(file.uploadedAt).toLocaleString();

        // Actions
        const tdActions = document.createElement('td');
        const actionsDiv = document.createElement('div');
        actionsDiv.className = 'row-actions';

        // Download Action
        const btnDownload = document.createElement('button');
        btnDownload.type = 'button';
        btnDownload.className = 'action-btn action-btn-download';
        btnDownload.textContent = '📥';
        btnDownload.title = 'Secure Download';
        btnDownload.setAttribute('aria-label', 'Secure Download');
        btnDownload.addEventListener('click', () => handleFileDownload(file.id, file.name));

        // Share Action
        const btnShare = document.createElement('button');
        btnShare.type = 'button';
        btnShare.className = 'action-btn action-btn-share';
        btnShare.textContent = '🔗';
        btnShare.title = 'Share File';
        btnShare.setAttribute('aria-label', 'Share File');
        btnShare.addEventListener('click', () => openShareModal(file.id, file.name));

        // Delete Action
        const btnDelete = document.createElement('button');
        btnDelete.type = 'button';
        btnDelete.className = 'action-btn action-btn-delete';
        btnDelete.textContent = '🗑️';
        btnDelete.title = 'Delete File';
        btnDelete.setAttribute('aria-label', 'Delete File');
        btnDelete.addEventListener('click', () => handleFileDelete(file.id, file.name));

        actionsDiv.appendChild(btnDownload);
        actionsDiv.appendChild(btnShare);
        actionsDiv.appendChild(btnDelete);
        tdActions.appendChild(actionsDiv);

        tr.appendChild(tdName);
        tr.appendChild(tdSize);
        tr.appendChild(tdTime);
        tr.appendChild(tdActions);

        tbody.appendChild(tr);
    });

    // Update pagination buttons state
    document.getElementById('files-prev-btn').disabled = state.files.page === 0;
    document.getElementById('files-next-btn').disabled = state.files.page >= state.files.totalPages - 1;
    document.getElementById('files-page-info').textContent = `Page ${state.files.page + 1} of ${state.files.totalPages}`;
}

export function renderSharedFilesTable() {
    const tbody = document.getElementById('shared-tbody');
    const emptyState = document.getElementById('shared-empty-state');

    // Clear rows safely
    tbody.textContent = '';

    const files = state.sharedFiles.content;

    if (files.length === 0) {
        emptyState.classList.remove('hidden');
        document.getElementById('shared-prev-btn').disabled = true;
        document.getElementById('shared-next-btn').disabled = true;
        document.getElementById('shared-page-info').textContent = 'Page 1 of 1';
        return;
    }

    emptyState.classList.add('hidden');

    // Render files safely (XSS defense: use textContent and element construction)
    files.forEach(file => {
        const tr = document.createElement('tr');

        // File name & Icon
        const tdName = document.createElement('td');
        const fileDiv = document.createElement('div');
        fileDiv.className = 'file-name-cell';

        const fileIcon = document.createElement('span');
        fileIcon.className = 'file-icon-s';
        fileIcon.textContent = getFileEmoji(file.name);

        const nameSpan = document.createElement('span');
        nameSpan.className = 'file-name-text';
        nameSpan.textContent = file.name;

        fileDiv.appendChild(fileIcon);
        fileDiv.appendChild(nameSpan);
        tdName.appendChild(fileDiv);

        // Size
        const tdSize = document.createElement('td');
        tdSize.textContent = formatBytes(file.sizeBytes);

        // Shared By
        const tdSharedBy = document.createElement('td');
        tdSharedBy.textContent = file.sharedByUsername;

        // Shared Time
        const tdTime = document.createElement('td');
        tdTime.textContent = new Date(file.sharedAt).toLocaleString();

        // Actions
        const tdActions = document.createElement('td');
        const actionsDiv = document.createElement('div');
        actionsDiv.className = 'row-actions';

        // Download Action
        const btnDownload = document.createElement('button');
        btnDownload.type = 'button';
        btnDownload.className = 'action-btn action-btn-download';
        btnDownload.textContent = '📥';
        btnDownload.title = 'Secure Download';
        btnDownload.setAttribute('aria-label', 'Secure Download');
        btnDownload.addEventListener('click', () => handleFileDownload(file.id, file.name));

        actionsDiv.appendChild(btnDownload);
        tdActions.appendChild(actionsDiv);

        tr.appendChild(tdName);
        tr.appendChild(tdSize);
        tr.appendChild(tdSharedBy);
        tr.appendChild(tdTime);
        tr.appendChild(tdActions);

        tbody.appendChild(tr);
    });

    // Update pagination buttons state
    document.getElementById('shared-prev-btn').disabled = state.sharedFiles.page === 0;
    document.getElementById('shared-next-btn').disabled = state.sharedFiles.page >= state.sharedFiles.totalPages - 1;
    document.getElementById('shared-page-info').textContent = `Page ${state.sharedFiles.page + 1} of ${state.sharedFiles.totalPages}`;
}

export async function handleFileDownload(fileId, filename) {
    showToast(`Initializing secure download: ${filename}`, 'info');
    try {
        await api.downloadFile(fileId);
    } catch (err) {
        showToast(`Download failed: ${err.message}`, 'danger');
    }
}

export async function handleFileDelete(fileId, filename) {
    const confirmation = confirm(`Are you sure you want to delete ${filename}?`);
    if (!confirmation) return;

    try {
        const res = await api.deleteFile(fileId);
        if (res.success) {
            showToast(`${filename} deleted successfully.`, 'success');
            loadFilesDashboard();
        }
    } catch (err) {
        showToast(`Deletion failed: ${err.message}`, 'danger');
    }
}

/* --- Secure Upload Logic --- */

export function handleFilesUpload(filesList) {
    if (!filesList || filesList.length === 0) return;

    // Process each file in array
    Array.from(filesList).forEach(file => {
        uploadFileFlow(file);
    });
}

export async function uploadFileFlow(file) {
    const progressList = document.getElementById('upload-progress-list');

    // Create progress list UI element safely
    const progressItem = document.createElement('div');
    progressItem.className = 'progress-item';

    const infoDiv = document.createElement('div');
    infoDiv.className = 'progress-info';

    const nameSpan = document.createElement('span');
    nameSpan.className = 'progress-filename';
    nameSpan.textContent = file.name;

    const percentSpan = document.createElement('span');
    percentSpan.className = 'progress-percent';
    percentSpan.textContent = '0%';

    infoDiv.appendChild(nameSpan);
    infoDiv.appendChild(percentSpan);

    const barContainer = document.createElement('div');
    barContainer.className = 'bar-container progress-bar-container';

    const barFill = document.createElement('div');
    barFill.className = 'progress-bar-fill';

    barContainer.appendChild(barFill);
    progressItem.appendChild(infoDiv);
    progressItem.appendChild(barContainer);
    progressList.appendChild(progressItem);

    try {
        // Trigger multi-part file upload
        await api.uploadFile(file, ({ percent }) => {
            barFill.style.width = `${percent}%`;
            percentSpan.textContent = `${percent}%`;
        });

        // Upload Success
        barFill.classList.add('success');
        showToast(`Uploaded ${file.name} successfully.`, 'success');

        // Reload dashboard files list
        loadFilesDashboard();

        // Clear indicator card after short delay
        setTimeout(() => {
            progressItem.remove();
        }, 2000);

    } catch (err) {
        // Upload Failure (Virus detected, file size exceeded, or network limits)
        barFill.classList.add('danger');
        percentSpan.textContent = 'Failed';

        let displayError = err.message;
        if (err.status === 422) {
            displayError = `Security Alert: Malicious signature detected in ${file.name} by virus scanner. Upload blocked.`;
        }

        showToast(displayError, 'danger');
    }
}
