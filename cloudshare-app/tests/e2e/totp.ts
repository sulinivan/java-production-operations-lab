/**
 * Minimal, self-contained RFC 4226 (HOTP) + RFC 6238 (TOTP) implementation
 * using Node's built-in `crypto` module. No third-party OTP dependency, so
 * this suite has no extra native/npm surface area to break in restricted
 * or offline CI runners.
 *
 * Matches the backend's TOTP configuration exactly (see
 * src/main/java/com/cloudshare/service/MfaService.java):
 *   - algorithm: SHA-1
 *   - digits:    6
 *   - period:    30 seconds
 *
 * Correctness is verified independently in specs/totp.spec.ts against the
 * official RFC 6238 Appendix B test vectors before this module is trusted
 * by any other spec (auth.spec.ts, admin.spec.ts).
 */

import { createHmac } from 'node:crypto';

const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

/**
 * Decodes an RFC 4648 base32 string (case-insensitive, padding optional)
 * into raw bytes.
 */
export function base32Decode(input: string): Buffer {
    const clean = input.trim().toUpperCase().replace(/=+$/g, '').replace(/\s+/g, '');
    if (clean.length === 0) {
        throw new Error('base32Decode: empty input');
    }

    let bitBuffer = '';
    for (const char of clean) {
        const value = BASE32_ALPHABET.indexOf(char);
        if (value === -1) {
            throw new Error(`base32Decode: invalid base32 character "${char}"`);
        }
        bitBuffer += value.toString(2).padStart(5, '0');
    }

    const bytes: number[] = [];
    for (let i = 0; i + 8 <= bitBuffer.length; i += 8) {
        bytes.push(parseInt(bitBuffer.substring(i, i + 8), 2));
    }

    return Buffer.from(bytes);
}

/**
 * RFC 4226 HOTP: computes a truncated HMAC-based one-time password for a
 * given secret and counter value.
 */
export function hotp(secretBytes: Buffer, counter: number, digits = 6): string {
    if (!Number.isSafeInteger(counter) || counter < 0) {
        throw new Error(`hotp: counter must be a non-negative safe integer, got ${counter}`);
    }

    // 8-byte big-endian counter. JS numbers are safe up to 2^53, so split
    // into high/low 32-bit halves rather than using BigInt for simplicity.
    const counterBuffer = Buffer.alloc(8);
    const high = Math.floor(counter / 0x100000000);
    const low = counter % 0x100000000;
    counterBuffer.writeUInt32BE(high, 0);
    counterBuffer.writeUInt32BE(low, 4);

    const hmac = createHmac('sha1', secretBytes).update(counterBuffer).digest();

    const offset = hmac[hmac.length - 1] & 0x0f;
    const binaryCode =
        ((hmac[offset] & 0x7f) << 24) |
        ((hmac[offset + 1] & 0xff) << 16) |
        ((hmac[offset + 2] & 0xff) << 8) |
        (hmac[offset + 3] & 0xff);

    return String(binaryCode % 10 ** digits).padStart(digits, '0');
}

export interface TotpOptions {
    /** Step size in seconds. Defaults to 30 (backend default). */
    period?: number;
    /** Number of digits in the resulting code. Defaults to 6 (backend default). */
    digits?: number;
    /** Unix timestamp in milliseconds. Defaults to Date.now(). */
    timestamp?: number;
}

/**
 * RFC 6238 TOTP: generates the current time-based one-time password for a
 * base32-encoded secret (the format the backend's QR/secret text uses).
 */
export function generateTotp(base32Secret: string, options: TotpOptions = {}): string {
    const period = options.period ?? 30;
    const digits = options.digits ?? 6;
    const timestamp = options.timestamp ?? Date.now();

    const counter = Math.floor(Math.floor(timestamp / 1000) / period);
    const secretBytes = base32Decode(base32Secret);

    return hotp(secretBytes, counter, digits);
}