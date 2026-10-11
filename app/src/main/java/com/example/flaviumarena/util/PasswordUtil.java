package com.example.flaviumarena.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password storage for the LOCAL prototype. Online, Firebase Authentication keeps the
 * passwords and this class is not used.
 *
 * A password is never stored. What is stored is the result of PBKDF2, a function that
 * is deliberately slow: it repeats a keyed hash many thousands of times. Someone who
 * copies the database must spend that time for every single guess, which makes trying
 * millions of common passwords impractical. Each account also has its own random salt,
 * so two people with the same password get different stored values.
 *
 * Stored format:   pbkdf2$<hash name>$<iterations>$<result in hex>
 * The iteration count is written into the value, so it can be raised later without
 * breaking the accounts that already exist.
 *
 * The first version of the app stored one round of SHA-256 (64 hex characters with no
 * "$"). Those values are still accepted once, and replaced on the next successful login.
 */
public final class PasswordUtil {

    private static final String PREFIX = "pbkdf2";
    private static final int ITERATIONS = 120000;
    private static final int KEY_BITS = 256;

    private PasswordUtil() {
    }

    public static String newSalt() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return toHex(bytes);
    }

    /** The value to store for a new or changed password. */
    public static String hash(String password, String salt) {
        // SHA-256 is preferred. Android 7 only offers the SHA-1 form of PBKDF2,
        // which is still a sound choice for password hashing.
        try {
            return encode("sha256", pbkdf2("PBKDF2WithHmacSHA256", password, salt, ITERATIONS));
        } catch (NoSuchAlgorithmException e) {
            try {
                return encode("sha1", pbkdf2("PBKDF2WithHmacSHA1", password, salt, ITERATIONS));
            } catch (NoSuchAlgorithmException e2) {
                throw new IllegalStateException("PBKDF2 is not available on this device", e2);
            }
        }
    }

    /** True when the password matches the stored value. Works for both formats. */
    public static boolean verify(String password, String salt, String stored) {
        if (password == null || salt == null || stored == null) {
            return false;
        }
        String actual;
        if (isLegacy(stored)) {
            actual = legacyHash(password, salt);
        } else {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) {
                return false;
            }
            try {
                String algorithm = "sha1".equals(parts[1])
                        ? "PBKDF2WithHmacSHA1" : "PBKDF2WithHmacSHA256";
                actual = PREFIX + "$" + parts[1] + "$" + parts[2] + "$"
                        + toHex(pbkdf2(algorithm, password, salt, Integer.parseInt(parts[2])));
            } catch (NoSuchAlgorithmException | NumberFormatException e) {
                return false;
            }
        }
        // isEqual takes the same time whether the first or the last character differs.
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),
                stored.getBytes(StandardCharsets.UTF_8));
    }

    /** True for a value written by the first version of the app (weak, to be replaced). */
    public static boolean isLegacy(String stored) {
        return stored == null || !stored.startsWith(PREFIX + "$");
    }

    /** The old single-round SHA-256 value. Only used to recognise old accounts. */
    public static String legacyHash(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest((salt + password).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The rules every new password must meet. Returns null when the password is fine. */
    public static String problemWith(String password) {
        if (password == null || password.length() < 8) {
            return "Password must have at least 8 characters.";
        }
        boolean letter = false;
        boolean digit = false;
        for (char c : password.toCharArray()) {
            letter |= Character.isLetter(c);
            digit |= Character.isDigit(c);
        }
        if (!letter || !digit) {
            return "Password must contain at least one letter and one number.";
        }
        return null;
    }

    private static String encode(String name, byte[] result) {
        return PREFIX + "$" + name + "$" + ITERATIONS + "$" + toHex(result);
    }

    private static byte[] pbkdf2(String algorithm, String password, String salt, int iterations)
            throws NoSuchAlgorithmException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(),
                salt.getBytes(StandardCharsets.UTF_8), iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded();
        } catch (InvalidKeySpecException e) {
            throw new IllegalStateException("Could not hash the password", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
