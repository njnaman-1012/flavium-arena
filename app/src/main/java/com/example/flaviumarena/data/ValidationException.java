package com.example.flaviumarena.data;

/** Thrown when the data given is missing, wrong, duplicated or breaks a rule. */
public class ValidationException extends ArenaException {
    public ValidationException(String message) {
        super(message);
    }
}
