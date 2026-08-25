import { test, expect } from '@playwright/test';
import {
    makeTestUser,
    registerUser,
    loginUser,
    expectToast,
    createTempFile,
    uploadFileViaUI,
    openShareModalFor,
    shareInternally,
    apiLogin,
} from '../helpers';
import { createHash } from 'node:crypto';

/**
 * NOTE: The dashboard now has a "Shared with Me" view in the frontend,
 * allowing recipients to view and download files shared with them.
 *
 * However, to maintain test focus and stability, User B's side of this
 * scenario (downloading the shared file, and later losing access after
 * revocation) is still exercised via the API here. User A's side
 * (uploading and sharing through the actual share modal) represents
 * UI-driven coverage. Re-pointing User B to the UI represents a potential
 * future test enhancement.
 */
test.describe('Internal Sharing & Revocation (Scenarios 3 & 5)', () => {
    test('User A shares a file with User B via the UI; B can access it via the API while active, loses access after A revokes', async ({
        page,
        browser,
        baseURL,
        request,
    }) => {
        const userA = makeTestUser('shareA');
        const userB = makeTestUser('shareB');

        // B just needs to exist as a registered user before A can share with them.
        const contextB = await browser.newContext({ ignoreHTTPSErrors: true });
        const pageB = await contextB.newPage();
        await registerUser(pageB, userB);
        await contextB.close();

        // A: real UI flow - register, login, upload, open share modal, share with B.
        await registerUser(page, userA);
        await loginUser(page, userA);

        const fixture = createTempFile('internal-share');
        await uploadFileViaUI(page, fixture.path);
        await expectToast(page, 'Uploaded', 'success');

        await openShareModalFor(page, fixture.name);
        const shareId = await shareInternally(page, userB.username, 'READ');
        expect(shareId).toBeTruthy();
        await expectToast(page, `Shared file successfully with ${userB.username}`, 'success');

        // Resolve the file's ID from A's own file list (the share response only
        // carries shareId/fileId/sharedWith/permission, but we look it up
        // independently here to also confirm the upload is visible to its owner).
        const tokenA = await apiLogin(request, baseURL!, userA);
        const listRes = await request.get(`${baseURL}/api/v1/files?page=0&size=50`, {
            headers: { Authorization: `Bearer ${tokenA}` },
        });
        const files = (await listRes.json()).data.content as Array<{ id: string; name: string }>;
        const fileId = files.find((f) => f.name === fixture.name)?.id;
        expect(fileId, 'uploaded file must be findable in owner file list').toBeTruthy();

        // B: confirm the share actually grants access (API - see note above).
        const tokenB = await apiLogin(request, baseURL!, userB);
        const downloadRes = await request.get(`${baseURL}/api/v1/files/${fileId}/download`, {
            headers: { Authorization: `Bearer ${tokenB}` },
        });
        expect(downloadRes.ok(), `B's download should succeed while share is active: ${downloadRes.status()}`).toBeTruthy();

        const downloadedBytes = await downloadRes.body();
        const downloadedChecksum = createHash('sha256').update(downloadedBytes).digest('hex');
        expect(downloadedChecksum).toBe(fixture.sha256);

        // A revokes the share (API - there is no revoke UI either; see Phase 12
        // plan review. This step's job is to change state, not re-test the
        // revoke endpoint itself, which ShareServiceTest/api_test.py already cover).
        const revokeRes = await request.delete(`${baseURL}/api/v1/shares/internal/${shareId}`, {
            headers: { Authorization: `Bearer ${tokenA}` },
        });
        expect(revokeRes.ok(), `revoke failed: ${revokeRes.status()} ${await revokeRes.text()}`).toBeTruthy();

        // B tries again - access is now denied.
        const downloadAfterRevoke = await request.get(`${baseURL}/api/v1/files/${fileId}/download`, {
            headers: { Authorization: `Bearer ${tokenB}` },
        });
        expect(downloadAfterRevoke.status()).toBe(404);
    });
});