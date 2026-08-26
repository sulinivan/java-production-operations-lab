import { test, expect } from '@playwright/test';
import { makeTestUser, registerAndLogin, loginUser, expectToast, readMfaSecret, waitForTotpRotation } from '../helpers';
import { generateTotp } from '../totp';
import { promoteUserToAdmin } from '../db';

test.describe('Admin Console: role gating & step-up authentication (Scenarios 6 & 7)', () => {
    test('a standard user is denied access to the Admin Console', async ({ page }) => {
        const user = makeTestUser('stduser');
        await registerAndLogin(page, user);

        await expect(page.locator('#nav-admin-btn')).toHaveClass(/hidden/);

        await page.goto('/#admin');

        await expectToast(page, 'Access Denied: Administrative privileges required', 'danger');
        await expect(page).toHaveURL(/#dashboard$/);
        await expect(page.locator('#view-admin')).toHaveClass(/hidden/);
    });

    test('an admin user must pass step-up MFA before the Admin Console renders users and audit logs', async ({ page }) => {
        test.setTimeout(120_000);
        const admin = makeTestUser('admintest');

        // Register, login, enroll MFA (required for step-up), logout.
        await registerAndLogin(page, admin);

        await page.locator('#nav-mfa-btn').click();
        const secret = await readMfaSecret(page);
        await page.locator('#mfa-verification-code').fill(generateTotp(secret));
        await page.locator('#mfa-verify-form button[type="submit"]').click();
        await expectToast(page, 'Multi-Factor Authentication enabled successfully', 'success');

        await page.locator('#nav-logout-btn').click();
        await expect(page.locator('#auth-gateway')).not.toHaveClass(/hidden/);

        // Promote to ROLE_ADMIN directly in Postgres. Registration always
        // defaults to ROLE_USER by design (security.md) - there is no product
        // API for self-promotion, so this is test-only SQL, not a UI/API path.
        promoteUserToAdmin(admin.username);

        // Must re-login: the JWT's roles claim is fixed at login time, so the
        // token issued before promotion still only carries ROLE_USER.
        await waitForTotpRotation();
        await loginUser(page, admin, generateTotp(secret));

        await expect(page.locator('#nav-admin-btn')).not.toHaveClass(/hidden/);

        // Navigating to #admin triggers the step-up prompt (no step-up token yet).
        await page.locator('#nav-admin-btn').click();
        await expect(page.locator('#admin-stepup-modal')).not.toHaveClass(/hidden/);
        await expect(page.locator('#view-admin')).toHaveClass(/hidden/);

        await waitForTotpRotation();
        await page.locator('#admin-stepup-code').fill(generateTotp(secret));
        await page.locator('#admin-stepup-form button[type="submit"]').click();

        await expectToast(page, 'Administrative authorization verified', 'success');
        await expect(page.locator('#admin-stepup-modal')).toHaveClass(/hidden/);
        await expect(page.locator('#view-admin')).not.toHaveClass(/hidden/);

        // Users table renders and includes the admin's own account.
        await expect(page.locator('#admin-users-tbody tr')).not.toHaveCount(0);
        await expect(page.locator('#admin-users-tbody')).toContainText(admin.username);

        // Audit logs table renders with entries, and the action filter narrows results.
        await expect(page.locator('#admin-logs-tbody tr')).not.toHaveCount(0);

        await page.locator('#admin-logs-action-filter').selectOption('LOGIN_SUCCESS');

        await expect(async () => {
            const actionBadges = page.locator('#admin-logs-tbody .badge-action');
            const actionTexts = await actionBadges.allTextContents();
            expect(actionTexts.length, 'filtering by LOGIN_SUCCESS should return at least one row').toBeGreaterThan(0);
            for (const text of actionTexts) {
                expect(text).toBe('LOGIN_SUCCESS');
            }
        }).toPass({ timeout: 7_000 });

        // User ID filter narrows to the admin's own logs (debounced input).
        const adminUserId = await page
            .locator('#admin-users-tbody tr', { hasText: admin.username })
            .locator('td')
            .first()
            .textContent();
        await page.locator('#admin-logs-user-filter').fill(adminUserId!.trim());
        await page.waitForTimeout(500); // clear the 300ms debounce
        const userIdCells = page.locator('#admin-logs-tbody tr td:nth-child(2)');
        await expect(userIdCells.first()).toHaveText(adminUserId!.trim(), { timeout: 7_000 });
    });
});