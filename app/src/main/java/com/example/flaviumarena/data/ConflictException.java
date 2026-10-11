package com.example.flaviumarena.data;

/** Thrown when a match was changed by someone else after the screen loaded it. */
public class ConflictException extends ArenaException {
    public ConflictException(String message) {
        super(message);
    }
}
