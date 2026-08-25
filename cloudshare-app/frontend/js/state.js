/**
 * Single, shared application state object.
 * Exported once here and imported by reference everywhere else — every module
 * that mutates `state.*` is mutating this same object, not a per-module copy.
 * Do not re-declare or clone this object in any importing module.
 */
export const state = {
    user: null, // User details: { id, username, email, roles, mfaRequired }
    files: {
        content: [],
        page: 0,
        size: 10,
        sort: 'createdAt,desc',
        searchQuery: '',
        totalPages: 1
    },
    sharedFiles: {
        content: [],
        page: 0,
        size: 10,
        totalPages: 1
    },
    admin: {
        users: { content: [], page: 0, totalPages: 1 },
        logs: { content: [], page: 0, totalPages: 1, userIdFilter: '', actionFilter: '' }
    },
    stepUpTimer: null
};
