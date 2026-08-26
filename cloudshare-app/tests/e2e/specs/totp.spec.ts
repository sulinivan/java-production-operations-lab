import { test, expect } from '@playwright/test';
import { base32Decode, hotp, generateTotp } from '../totp';

/**
 * Validates the hand-rolled totp.ts module against the official RFC 6238
 * Appendix B test vectors before anything else in this suite (auth.spec.ts,
 * admin.spec.ts) is allowed to depend on it for real MFA/step-up codes.
 *
 * RFC 6238 test vectors use the ASCII secret "12345678901234567890" (SHA-1
 * case), 8-digit codes, and a 30s step. The backend uses a base32 secret,
 * 6 digits, and a 30s step - so here we base32-encode the RFC's ASCII
 * secret and generate 8-digit codes to match the vectors exactly, while
 * separately confirming the 6-digit/30s path (the backend's actual config)
 * produces stable, well-formed output.
 */

// base32(ASCII "12345678901234567890"), independently verified via Python's
// base64.b32encode - see PR description for derivation.
const RFC_6238_SECRET_BASE32 = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ';

const RFC_6238_VECTORS: Array<{ timeSeconds: number; expected: string }> = [
    { timeSeconds: 59, expected: '94287082' },
    { timeSeconds: 1111111109, expected: '07081804' },
    { timeSeconds: 1111111111, expected: '14050471' },
    { timeSeconds: 1234567890, expected: '89005924' },
    { timeSeconds: 2000000000, expected: '69279037' },
];

test.describe('totp.ts correctness (RFC 6238 Appendix B vectors)', () => {
    for (const vector of RFC_6238_VECTORS) {
        test(`generates ${vector.expected} at t=${vector.timeSeconds}s`, () => {
            const code = generateTotp(RFC_6238_SECRET_BASE32, {
                digits: 8,
                period: 30,
                timestamp: vector.timeSeconds * 1000,
            });
            expect(code).toBe(vector.expected);
        });
    }

    test('base32Decode round-trips the RFC test secret to the correct ASCII bytes', () => {
        const decoded = base32Decode(RFC_6238_SECRET_BASE32);
        expect(decoded.toString('ascii')).toBe('12345678901234567890');
    });

    test('hotp() matches RFC 4226 Appendix D test vectors (6-digit, counters 0-9)', () => {
        // RFC 4226 Appendix D truncated values, same ASCII secret as above.
        const rfc4226Vectors = ['755224', '287082', '359152', '969429', '338314', '254676', '287922', '162583', '399871', '520489'];
        const secretBytes = base32Decode(RFC_6238_SECRET_BASE32);

        rfc4226Vectors.forEach((expected, counter) => {
            expect(hotp(secretBytes, counter, 6)).toBe(expected);
        });
    });

    test('backend-matching config (SHA-1, 6 digits, 30s period) produces a stable 6-digit code', () => {
        const fixedTimestamp = 1_700_000_000_000;
        const codeA = generateTotp(RFC_6238_SECRET_BASE32, { timestamp: fixedTimestamp });
        const codeB = generateTotp(RFC_6238_SECRET_BASE32, { timestamp: fixedTimestamp });

        expect(codeA).toMatch(/^\d{6}$/);
        expect(codeA).toBe(codeB); // deterministic for a fixed timestamp/counter

        // Next 30s window must differ (overwhelmingly likely; guards against a
        // counter-computation bug that ignores the period entirely).
        const codeNextWindow = generateTotp(RFC_6238_SECRET_BASE32, { timestamp: fixedTimestamp + 30_000 });
        expect(codeNextWindow).not.toBe(codeA);
    });

    test('rejects invalid base32 characters', () => {
        expect(() => base32Decode('not-valid-base32!!!')).toThrow();
    });
});