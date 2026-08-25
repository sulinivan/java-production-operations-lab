import { test, expect } from '@playwright/test';
import { makeTestUser, registerUser, loginUser, expectToast, readMfaSecret, waitForTotpRotation } from '../helpers';
import { generateTotp } from '../totp';

test.describe('Authentication & MFA (Scenario 1)', () => {
    test('registers, logs in, enrolls MFA, and logs in again with a TOTP code', async ({ page }) => {
        const user = makeTestUser('auth');

        // 1. Register
        await registerUser(page, user);
        await expectToast(page, 'registered successfully', 'success');

        // 2. Login with password only (no MFA enrolled yet)
        await loginUser(page, user);
        await expect(page.locator('#header-username')).toHaveText(user.username);

        // 3. Enroll in MFA
        await page.locator('#nav-mfa-btn').click();
        await expect(page.locator('#view-mfa')).not.toHaveClass(/hidden/);
        await expect(page.locator('#mfa-setup-section')).not.toHaveClass(/hidden/);

        const secret = await readMfaSecret(page);
        expect(secret, 'MFA secret text must be populated').toBeTruthy();
        await expect(page.locator('#mfa-qr-img')).toHaveAttribute('src', /^data:image\//);

        const enrollCode = generateTotp(secret);
        await page.locator('#mfa-verification-code').fill(enrollCode);
        await page.locator('#mfa-verify-form button[type="submit"]').click();

        await expectToast(page, 'Multi-Factor Authentication enabled successfully', 'success');
        await expect(page.locator('#mfa-active-section')).not.toHaveClass(/hidden/);
        await expect(page.locator('#mfa-status-banner')).toContainText('Active');

        // 4. Logout, then log back in - now MFA is required
        await page.locator('#nav-logout-btn').click();
        await expect(page.locator('#auth-gateway')).not.toHaveClass(/hidden/);

        // 4a. Password alone must now be rejected
        await page.goto('/#login');
        await page.locator('#login-username').fill(user.username);
        await page.locator('#login-password').fill(user.password);
        await page.locator('#login-form button[type="submit"]').click();
        await expectToast(page, 'Invalid username/email or password', 'danger');
        await expect(page.locator('#app-shell')).toHaveClass(/hidden/);

        // 4b. Password + valid dynamic TOTP code succeeds
        await waitForTotpRotation();
        const loginCode = generateTotp(secret);
        await page.locator('#login-mfa').fill(loginCode);
        await page.locator('#login-form button[type="submit"]').click();

        await expect(page.locator('#app-shell')).not.toHaveClass(/hidden/);
        await expect(page.locator('#header-username')).toHaveText(user.username);
    });
});