import { test, expect } from '@playwright/test';
import { makeTestUser, registerAndLogin, expectToast, createTempFile, sha256Of, fileRow, uploadFileViaUI, downloadFileViaUI } from '../helpers';

test.describe('File Dashboard (Scenario 2)', () => {
    test('uploads a file, sees it listed, downloads it, and verifies integrity', async ({ page }) => {
        const user = makeTestUser('dash');
        await registerAndLogin(page, user);

        const fixture = createTempFile('dashboard-upload');

        // Upload
        await uploadFileViaUI(page, fixture.path);
        await expectToast(page, 'Uploaded', 'success');

        // Appears in the dashboard list
        const row = fileRow(page, fixture.name);
        await expect(row).toBeVisible();
        await expect(row.locator('td').nth(1)).not.toHaveText(''); // size column populated

        // Download and verify the round-tripped bytes are byte-identical
        // (this is what actually proves the AES-256-GCM decrypt pipeline on the
        // way out matches the encrypt pipeline on the way in - a checksum
        // computed locally against our own known-good source file, not a value
        // the server reports about itself).
        const downloadedPath = await downloadFileViaUI(page, fixture.name);
        const downloadedChecksum = sha256Of(downloadedPath);

        expect(downloadedChecksum).toBe(fixture.sha256);
    });

    test('deletes a file and it disappears from the dashboard', async ({ page }) => {
        const user = makeTestUser('dashdel');
        await registerAndLogin(page, user);

        const fixture = createTempFile('dashboard-delete');
        await uploadFileViaUI(page, fixture.path);
        await expectToast(page, 'Uploaded', 'success');

        const row = fileRow(page, fixture.name);
        await expect(row).toBeVisible();

        page.once('dialog', (dialog) => dialog.accept());
        await row.locator('.action-btn-delete').click();

        await expectToast(page, 'deleted successfully', 'success');
        await expect(fileRow(page, fixture.name)).toHaveCount(0);
    });
});