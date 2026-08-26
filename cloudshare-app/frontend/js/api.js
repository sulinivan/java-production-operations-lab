/**
 * CloudShare Secure API Client - Module
 * Handles all REST endpoints, in-memory tokens, token rotation (RTR),
 * rate-limiting (429) bubble up, and Content-Disposition filename parsing.
 */

class ApiClient {
    constructor() {
        this.accessToken = null;
        this.stepUpToken = null;
        this.onAuthFailureCallback = null;
        this.isRefreshing = false;
        this.refreshQueue = [];
    }

    setAccessToken(token) {
        this.accessToken = token;
    }

    getAccessToken() {
        return this.accessToken;
    }

    setStepUpToken(token) {
        this.stepUpToken = token;
    }

    getStepUpToken() {
        return this.stepUpToken;
    }

    registerAuthFailureCallback(callback) {
        this.onAuthFailureCallback = callback;
    }

    clearTokens() {
        this.accessToken = null;
        this.stepUpToken = null;
    }

    /**
     * Parse filename from Content-Disposition header
     */
    parseFilename(contentDispositionHeader, fallbackId) {
        if (!contentDispositionHeader) {
            return `file-${fallbackId}`;
        }

        // Support standard RFC 6266 filename* and filename parameters
        // Example: attachment; filename="report.pdf"
        // Example: attachment; filename*=UTF-8''report.pdf
        let filename = '';

        const filenameStarMatch = contentDispositionHeader.match(/filename\*=UTF-8''([^;]+)/i);
        if (filenameStarMatch && filenameStarMatch[1]) {
            try {
                filename = decodeURIComponent(filenameStarMatch[1]);
            } catch (e) {
                filename = filenameStarMatch[1]; // Fallback to raw value if malformed
            }
        } else {
            const filenameMatch = contentDispositionHeader.match(/filename="?([^";]+)"?/i);
            if (filenameMatch && filenameMatch[1]) {
                filename = filenameMatch[1];
            }
        }

        return filename ? filename : `file-${fallbackId}`;
    }

    /**
     * Custom Rate Limit Error
     */
    createRateLimitError(payload) {
        let message = 'Too many requests. Please slow down and try again.';
        if (payload && payload.error && payload.error.message) {
            message = payload.error.message;
        }
        const err = new Error(message);
        err.isRateLimit = true;
        err.status = 429;
        return err;
    }

    /**
     * Core Fetch wrapper that handles Bearer headers, refresh rotation, and rate-limiting
     */
    async request(url, options = {}) {
        options.headers = options.headers || {};

        // Inject standard JSON request content type if body is present
        if (options.body && !(options.body instanceof FormData) && !options.headers['Content-Type']) {
            options.headers['Content-Type'] = 'application/json';
        }

        // Inject Access Token from memory if present
        if (this.accessToken) {
            options.headers['Authorization'] = `Bearer ${this.accessToken}`;
        }

        // Inject Step-up Token for administrative actions
        if (url.includes('/api/v1/admin') && this.stepUpToken) {
            options.headers['X-StepUp-Token'] = this.stepUpToken;
        }

        try {
            const response = await fetch(url, options);

            // Rotate step-up token if the backend issued a fresh single-use successor
            // for this admin session (see StepUpAuthenticationFilter's absolute
            // session cap — rotation stops once the session's origin MFA verification
            // ages past the configured cap, requiring a fresh MFA prompt).
            const nextStepUpToken = response.headers.get('X-StepUp-Token');
            if (nextStepUpToken) {
                this.setStepUpToken(nextStepUpToken);
            }

            // Handle success
            if (response.ok) {
                return response;
            }

            // Handle 401 Unauthorized
            if (response.status === 401) {
                // Parse error response if available
                const body = await response.clone().json().catch(() => null);
                if (body?.error?.code === 'STEP_UP_REQUIRED') {
                    this.setStepUpToken(null);
                    const err = new Error(body.error.message || 'Step-up authorization required');
                    err.status = 401;
                    err.stepUpRequired = true;
                    throw err;
                }

                // If this request was already retried once after refresh, do not loop
                if (options._retriedAfterRefresh) {
                    const err = new Error('Session expired');
                    err.status = 401;
                    throw err;
                }

                // If it is the refresh endpoint itself failing, clean state and bubble up
                if (url.includes('/api/v1/auth/refresh')) {
                    this.clearTokens();
                    if (this.onAuthFailureCallback) {
                        this.onAuthFailureCallback('Session expired. Please log in again.');
                    }
                    const err = new Error('Refresh token expired or invalid');
                    err.status = 401;
                    throw err;
                }

                if (!this.accessToken) {
                    const errorMsg = (body && body.error && body.error.message)
                        ? body.error.message
                        : `Request failed with status ${response.status}`;
                    const err = new Error(errorMsg);
                    err.status = 401;
                    err.data = body;
                    throw err;
                }

                options._retriedAfterRefresh = true;

                // Attempt to refresh the token
                return new Promise((resolve, reject) => {
                    this.enqueueRequest(
                        () => {
                            this.request(url, options).then(resolve).catch(reject);
                        },
                        (err) => {
                            reject(err);
                        }
                    );

                    if (!this.isRefreshing) {
                        this.isRefreshing = true;
                        this.performTokenRefresh()
                            .then(() => {
                                this.isRefreshing = false;
                                this.resolveQueue();
                            })
                            .catch((err) => {
                                this.isRefreshing = false;
                                this.rejectQueue(err);
                                this.clearTokens();
                                if (this.onAuthFailureCallback) {
                                    this.onAuthFailureCallback('Session expired. Please log in again.');
                                }
                            });
                    }
                });
            }

            // Handle 429 Too Many Requests - Rate Limiting
            if (response.status === 429) {
                let payload = null;
                try {
                    payload = await response.json();
                } catch (e) {
                    // Ignore JSON parse issues for generic error
                }
                throw this.createRateLimitError(payload);
            }

            // Parse generic error messages from standard JSON envelope
            let errorData = null;
            try {
                errorData = await response.json();
            } catch (e) {
                // Not JSON
            }

            const errorMsg = (errorData && errorData.error && errorData.error.message)
                ? errorData.error.message
                : `Request failed with status ${response.status}`;

            const err = new Error(errorMsg);
            err.status = response.status;
            err.data = errorData;
            throw err;

        } catch (error) {
            // Re-throw rate limit or API custom errors directly
            if (error.isRateLimit || error.status) {
                throw error;
            }
            throw new Error(`Network connection error: ${error.message}`);
        }
    }

    /**
     * Queue requests while silent refresh is in progress
     */
    enqueueRequest(resolveCb, rejectCb) {
        this.refreshQueue.push({ resolve: resolveCb, reject: rejectCb });
    }

    resolveQueue() {
        this.refreshQueue.forEach(item => item.resolve());
        this.refreshQueue = [];
    }

    rejectQueue(error) {
        this.refreshQueue.forEach(item => item.reject(error));
        this.refreshQueue = []; // Clear queue on absolute failure
    }

    /**
     * Silent token refresh call
     */
    async performTokenRefresh() {
        // Calls POST /refresh. HttpOnly cookie refresh_token is sent automatically by the browser
        const response = await fetch('/api/v1/auth/refresh', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        if (!response.ok) {
            throw new Error('Refresh failed');
        }

        const body = await response.json();
        if (body.success && body.data && body.data.accessToken) {
            this.setAccessToken(body.data.accessToken);
        } else {
            throw new Error('Invalid refresh response structure');
        }
    }

    /* --- Auth endpoints --- */

    async register(username, email, password) {
        const res = await this.request('/api/v1/auth/register', {
            method: 'POST',
            body: JSON.stringify({ username, email, password })
        });
        return await res.json();
    }

    async login(usernameOrEmail, password, mfaCode = null) {
        const payload = { usernameOrEmail, password };
        if (mfaCode) {
            payload.mfaCode = mfaCode;
        }

        const res = await this.request('/api/v1/auth/login', {
            method: 'POST',
            body: JSON.stringify(payload)
        });
        const body = await res.json();
        if (body.success && body.data && body.data.accessToken) {
            this.setAccessToken(body.data.accessToken);
        }
        return body;
    }

    async logout() {
        try {
            await this.request('/api/v1/auth/logout', {
                method: 'POST'
            });
        } finally {
            // Local state must be cleared even if API call fails
            this.clearTokens();
        }
    }

    async initMfa() {
        const res = await this.request('/api/v1/auth/mfa/setup', {
            method: 'POST'
        });
        return await res.json();
    }

    async verifyMfa(code) {
        const res = await this.request('/api/v1/auth/mfa/verify', {
            method: 'POST',
            body: JSON.stringify({ code })
        });
        return await res.json();
    }

    async stepUpMfa(code) {
        const res = await this.request('/api/v1/auth/mfa/step-up', {
            method: 'POST',
            body: JSON.stringify({ code })
        });
        const body = await res.json();
        if (body.success && body.data && body.data.stepUpToken) {
            this.setStepUpToken(body.data.stepUpToken);
        }
        return body;
    }

    /* --- File endpoints --- */

    async listFiles(page = 0, size = 10, sort = 'createdAt,desc') {
        const res = await this.request(`/api/v1/files?page=${page}&size=${size}&sort=${sort}`, {
            method: 'GET'
        });
        return await res.json();
    }

    async listSharedWithMe(page = 0, size = 10) {
        const res = await this.request(`/api/v1/files/shared-with-me?page=${page}&size=${size}&sort=createdAt,desc`, {
            method: 'GET'
        });
        return await res.json();
    }

    /**
     * File upload leveraging XMLHttpRequest to support progress tracking
     */
    uploadFile(file, onProgress) {
        return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            xhr.open('POST', '/api/v1/files/upload', true);

            // Handle progress monitoring
            if (xhr.upload && onProgress) {
                xhr.upload.addEventListener('progress', (e) => {
                    if (e.lengthComputable) {
                        const percent = Math.round((e.loaded / e.total) * 100);
                        onProgress({ loaded: e.loaded, total: e.total, percent });
                    }
                });
            }

            // Headers
            if (this.accessToken) {
                xhr.setRequestHeader('Authorization', `Bearer ${this.accessToken}`);
            }

            xhr.onload = () => {
                let jsonResponse = null;
                try {
                    jsonResponse = JSON.parse(xhr.responseText);
                } catch (e) {
                    // Ignore JSON parsing failures
                }

                if (xhr.status === 201 || xhr.status === 200) {
                    resolve(jsonResponse);
                } else if (xhr.status === 401) {
                    // Silent refresh inside upload is difficult due to file stream reuse.
                    // We fail the upload with a clear authentication expired flag, forcing retry.
                    const err = new Error('Authentication expired. Please retry your upload.');
                    err.status = 401;
                    reject(err);
                } else if (xhr.status === 429) {
                    reject(this.createRateLimitError(jsonResponse));
                } else {
                    const message = (jsonResponse && jsonResponse.error && jsonResponse.error.message)
                        ? jsonResponse.error.message
                        : `Upload failed with status ${xhr.status}`;
                    const err = new Error(message);
                    err.status = xhr.status;
                    err.data = jsonResponse;
                    reject(err);
                }
            };

            xhr.onerror = () => {
                reject(new Error('Network connection error during file upload'));
            };

            const formData = new FormData();
            formData.append('file', file);
            xhr.send(formData);
        });
    }

    /**
     * Secure download fetching the file stream as Blob with headers,
     * and parsing the dynamic Content-Disposition filename.
     */
    async downloadFile(fileId) {
        const response = await this.request(`/api/v1/files/${fileId}/download`, {
            method: 'GET'
        });

        const contentDisposition = response.headers.get('Content-Disposition');
        const filename = this.parseFilename(contentDisposition, fileId);

        const blob = await response.blob();
        this.triggerBlobDownload(blob, filename);
    }

    async deleteFile(fileId) {
        const res = await this.request(`/api/v1/files/${fileId}`, {
            method: 'DELETE'
        });
        // 204 No Content has no body, return success envelope manually
        if (res.status === 204) {
            return { success: true };
        }
        return await res.json();
    }

    /* --- Sharing endpoints --- */

    async shareInternal(fileId, targetUsernameOrEmail, permissionType) {
        const res = await this.request('/api/v1/shares/internal', {
            method: 'POST',
            body: JSON.stringify({ fileId, targetUsernameOrEmail, permissionType })
        });
        return await res.json();
    }

    async createPublicLink(fileId, expiresInSeconds, password = null, downloadLimit = null) {
        const payload = { fileId, expiresInSeconds };
        if (password) {
            payload.password = password;
        }
        if (downloadLimit) {
            payload.downloadLimit = parseInt(downloadLimit);
        }

        const res = await this.request('/api/v1/shares/link', {
            method: 'POST',
            body: JSON.stringify(payload)
        });
        return await res.json();
    }

    async getPublicLinkInfo(shareCode) {
        const res = await this.request(`/api/v1/shares/link/${shareCode}/info`, {
            method: 'GET'
        });
        return await res.json();
    }

    /**
     * Public link file download - does not require Bearer token authorization
     */
    async downloadPublicLink(shareCode, password = null) {
        const headers = {};
        if (password) {
            headers['X-Share-Password'] = password;
        }

        const response = await this.request(`/api/v1/shares/link/${shareCode}/download`, {
            method: 'GET',
            headers: headers
        });

        const contentDisposition = response.headers.get('Content-Disposition');
        const filename = this.parseFilename(contentDisposition, shareCode);

        const blob = await response.blob();
        this.triggerBlobDownload(blob, filename);
    }

    /**
     * DOM elements creator to trigger download of Blob objects
     */
    triggerBlobDownload(blob, filename) {
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.style.display = 'none';
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();

        // Clean resources
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);
    }

    /* --- Admin & Auditing endpoints --- */

    async listUsers(page = 0, size = 10) {
        const res = await this.request(`/api/v1/admin/users?page=${page}&size=${size}`, {
            method: 'GET'
        });
        return await res.json();
    }

    async getAuditLogs(page = 0, size = 20, userId = null, action = null) {
        let query = `/api/v1/admin/audit-logs?page=${page}&size=${size}`;
        if (userId) {
            query += `&userId=${encodeURIComponent(userId)}`;
        }
        if (action) {
            query += `&action=${encodeURIComponent(action)}`;
        }

        const res = await this.request(query, {
            method: 'GET'
        });
        return await res.json();
    }
}

export const api = new ApiClient();
window.api = api;
