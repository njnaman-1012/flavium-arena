package com.example.flaviumarena.data;

/**
 * Thrown at login when the account already has a live session somewhere else.
 * The login screen then offers to sign that session out and continue.
 */
public class SessionActiveException extends ArenaException {
    public SessionActiveException(String message) {
        super(message);
    }
}
