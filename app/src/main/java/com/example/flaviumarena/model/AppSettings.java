package com.example.flaviumarena.model;

/**
 * Names of the switches kept in the settings table. Only Faculty can change them.
 * Each one decides how much authority a Core has.
 */
public final class AppSettings {

    /** When on, a Core may finalise results and correct final results. */
    public static final String CORE_CAN_FINALISE = "core_can_finalise";

    /** When on, a Core may appoint, change and deactivate Branch Captains. */
    public static final String CORE_CAN_APPOINT_CAPTAINS = "core_can_appoint_captains";

    public static final String[] ALL = {CORE_CAN_FINALISE, CORE_CAN_APPOINT_CAPTAINS};

    private AppSettings() {
    }

    public static boolean isValid(String key) {
        return CORE_CAN_FINALISE.equals(key) || CORE_CAN_APPOINT_CAPTAINS.equals(key);
    }
}
