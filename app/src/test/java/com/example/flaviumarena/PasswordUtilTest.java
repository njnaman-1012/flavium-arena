package com.example.flaviumarena;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.flaviumarena.util.PasswordUtil;

import org.junit.Test;

/** Checks the local password hashing. Runs on the computer, no phone needed. */
public class PasswordUtilTest {

    @Test
    public void storedValueIsSlowHashAndNeverThePassword() {
        String stored = PasswordUtil.hash("Secret@123", PasswordUtil.newSalt());
        assertTrue(stored.startsWith("pbkdf2$"));
        assertFalse(stored.contains("Secret@123"));
        // Format: pbkdf2$<hash name>$<iterations>$<hex>
        String[] parts = stored.split("\\$");
        assertEquals(4, parts.length);
        assertTrue(Integer.parseInt(parts[2]) >= 100000);
        assertEquals(64, parts[3].length());
    }

    @Test
    public void rightPasswordVerifiesAndWrongOneDoesNot() {
        String salt = PasswordUtil.newSalt();
        String stored = PasswordUtil.hash("Secret@123", salt);
        assertTrue(PasswordUtil.verify("Secret@123", salt, stored));
        assertFalse(PasswordUtil.verify("Secret@124", salt, stored));
        assertFalse(PasswordUtil.verify("", salt, stored));
        assertFalse(PasswordUtil.verify("Secret@123", "another-salt", stored));
    }

    @Test
    public void samePasswordGivesDifferentValuesForDifferentAccounts() {
        String saltA = PasswordUtil.newSalt();
        String saltB = PasswordUtil.newSalt();
        assertNotEquals(saltA, saltB);
        assertNotEquals(PasswordUtil.hash("Secret@123", saltA),
                PasswordUtil.hash("Secret@123", saltB));
    }

    @Test
    public void valuesFromTheFirstVersionAreStillAcceptedAndMarkedForReplacement() {
        String old = PasswordUtil.legacyHash("Secret@123", "salt");
        assertTrue(PasswordUtil.isLegacy(old));
        assertTrue(PasswordUtil.verify("Secret@123", "salt", old));
        assertFalse(PasswordUtil.verify("wrong", "salt", old));
        assertFalse(PasswordUtil.isLegacy(PasswordUtil.hash("Secret@123", "salt")));
    }

    @Test
    public void damagedStoredValueNeverVerifies() {
        assertFalse(PasswordUtil.verify("x", "salt", "pbkdf2$sha256$notanumber$abcd"));
        assertFalse(PasswordUtil.verify("x", "salt", "pbkdf2$sha256"));
        assertFalse(PasswordUtil.verify("x", "salt", null));
    }

    @Test
    public void passwordRules() {
        assertNull(PasswordUtil.problemWith("Abcdef12"));
        assertNotNull(PasswordUtil.problemWith(null));
        assertNotNull(PasswordUtil.problemWith("Ab12"));          // too short
        assertNotNull(PasswordUtil.problemWith("abcdefghij"));    // no number
        assertNotNull(PasswordUtil.problemWith("1234567890"));    // no letter
    }
}
