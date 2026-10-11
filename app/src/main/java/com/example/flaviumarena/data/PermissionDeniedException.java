package com.example.flaviumarena.data;

/** Thrown when a user's role does not allow the requested operation. */
public class PermissionDeniedException extends ArenaException {
    public PermissionDeniedException(String message) {
        super(message);
    }
}
