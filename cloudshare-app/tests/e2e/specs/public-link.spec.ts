import { test, expect } from '@playwright/test';
import {
    makeTestUser,
    registerAndLogin,
    expectToast,
    createTempFile,
    uploadFileViaUI,
    openShareModalFor,
    createPublicLink,
    openPublicShareLink,
    apiLogin,
} from '../helpers';

test.describe('Public Share Links (Scenario 4 & Public Link Revocation)', () => {
    test('guest downloads a password-protected link, then it self-destructs at the download limit', async ({ page, browser }) => {
        const owner = makeTestUser('pubowner');
        await registerAndLogin(page, owner);

        const fixture = createTempFile('public-link');
        await uploadFileViaUI(page, fixture.path);
        await expectToast(page, 'Uploaded', 'success');

        await openShareModalFor(page, fixture.name);
        const password = 'GuestPasscode!42';
        const shareCode = await createPublicLink(page, {
            expiresInSeconds: 3600,
            password,
            downloadLimit: 1,
        });
        expect(shareCode).toBeTruthy();
        await expectToast(page, 'Secure public link generated successfully', 'success');
        await expect(page.locator('#public-link-url-display')).toHaveValue(new RegExp(`#share/${shareCode}$`));

        // Guest context: unauthenticated, separate browser context entirely.
        const guestContext = await browser.newContext({ ignoreHTTPSErrors: true });
        try {
            // First download: wrong password is rejected (download fails with generic 404)
            const guestPage1 = await openPublicShareLink(guestContext, shareCode);
            await guestPage1.locator('#public-password').fill('wrong-password');
            await guestPage1.locator('#public-download-btn').click();
            await expectToast(guestPage1, /Shared link does not exist or invalid passcode/i, 'danger');
            await guestPage1.close();

            // Second attempt: correct password succeeds and consumes the one allowed download
            const guestPage2 = await openPublicShareLink(guestContext, shareCode);
            await guestPage2.locator('#public-password').fill(password);
            const [download] = await Promise.all([
                guestPage2.waitForEvent('download'),
                guestPage2.locator('#public-download-btn').click(),
            ]);
            expect(download.suggestedFilename()).toBe(fixture.name);
            await guestPage2.close();

            // Third attempt: limit already reached -> rejected
            const guestPage3 = await openPublicShareLink(guestContext, shareCode);
            await guestPage3.locator('#public-password').fill(password);
            await guestPage3.locator('#public-download-btn').click();
            await expectToast(guestPage3, /expired or reached download limit/i, 'danger');
            await guestPage3.close();
        } finally {
            await guestContext.close();
        }
    });

    test('guest is rejected once the owner revokes the public link', async ({ page, browser, baseURL, request }) => {
        const owner = makeTestUser('pubrevoke');
        await registerAndLogin(page, owner);

        const fixture = createTempFile('public-link-revoke');
        await uploadFileViaUI(page, fixture.path);
        await expectToast(page, 'Uploaded', 'success');

        await openShareModalFor(page, fixture.name);
        const shareCode = await createPublicLink(page, { expiresInSeconds: 86400 });
        expect(shareCode).toBeTruthy();

        // Guest can download while the link is active.
        const guestContext = await browser.newContext({ ignoreHTTPSErrors: true });
        const guestPage = await openPublicShareLink(guestContext, shareCode);
        const [downloadBefore] = await Promise.all([
            guestPage.waitForEvent('download'),
            guestPage.locator('#public-download-btn').click(),
        ]);
        expect(downloadBefore.suggestedFilename()).toBe(fixture.name);

        // Owner revokes the link (API - there is no revoke UI; see Phase 12 plan review).
        const token = await apiLogin(request, baseURL!, owner);
        const revokeRes = await request.delete(`${baseURL}/api/v1/shares/link/${shareCode}`, {
            headers: { Authorization: `Bearer ${token}` },
        });
        expect(revokeRes.ok(), `link revoke failed: ${revokeRes.status()} ${await revokeRes.text()}`).toBeTruthy();

        // Guest tries again after revocation - /info fails on load, showing toast and hiding gateway
        await guestPage.reload();
        await expectToast(guestPage, /Shared link does not exist/i, 'danger');
        await expect(guestPage.locator('#public-share-gateway')).toHaveClass(/hidden/);

        await guestContext.close();
    });

    test('requesting malformed public link route does not throw 500 exception and downstream routing returns 404', async ({ request, baseURL }) => {
        const response1 = await request.get(`${baseURL}/api/v1/shares/link/`);
        expect(response1.status()).toBe(404);

        const response2 = await request.get(`${baseURL}/api/v1/shares/link`);
        expect(response2.status()).toBe(404);
    });
});