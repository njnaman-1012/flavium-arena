package com.example.flaviumarena.sync;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the public may see of a shared row. Pure Java, covered by unit tests.
 *
 * The server cannot hide single columns of a document, so every row is stored there in
 * up to two documents: the complete row, readable by organisers who are allowed to, and
 * a public copy, readable by everyone. This class decides what the public copy holds.
 *
 *   Public tables      branches, sports, categories, fixtures, events, settings:
 *                      the public copy is the row itself and there is no other.
 *   Private tables     histories, the administrative log, coach assignments:
 *                      there is no public copy at all.
 *   Split tables       registrations: public only once approved, and never with the
 *                      phone number. Accounts: the name and role, never the email.
 */
public final class PublicView {

    private static final List<String> PRIVATE = Arrays.asList("score_audit", "event_audit",
            "admin_audit", "coach_sports");
    private static final List<String> SPLIT = Arrays.asList("registrations", "users");

    private PublicView() {
    }

    /** Tables that are never public. */
    public static boolean isPrivate(String table) {
        return PRIVATE.contains(table);
    }

    /** Tables with a complete row for organisers and a reduced copy for the public. */
    public static boolean isSplit(String table) {
        return SPLIT.contains(table);
    }

    /**
     * The public copy of a complete row: the row itself, a reduced row, a removal (the
     * public must stop seeing it), or null when the table has no public copy.
     */
    public static Change of(Change complete) {
        if (isPrivate(complete.table)) {
            return null;
        }
        Change copy;
        if (complete.deleted) {
            copy = new Change(complete.table, complete.key, true, null);
        } else if ("registrations".equals(complete.table)) {
            // A registration that is waiting or was rejected is nobody else's business.
            if (!"APPROVED".equals(complete.values.get("status"))) {
                copy = new Change(complete.table, complete.key, true, null);
            } else {
                Map<String, Object> values = new LinkedHashMap<>(complete.values);
                values.put("contact_phone", "");
                copy = new Change(complete.table, complete.key, false, values);
            }
        } else if ("users".equals(complete.table)) {
            // Fixtures show who scores them, so the name is public. The email is not;
            // the column must still be filled and unique, so it gets a placeholder.
            Map<String, Object> values = new LinkedHashMap<>(complete.values);
            values.put("email", complete.key + "@hidden.invalid");
            copy = new Change(complete.table, complete.key, false, values);
        } else {
            copy = new Change(complete.table, complete.key, false,
                    new LinkedHashMap<>(complete.values));
        }
        copy.rev = complete.rev;
        copy.complete = !isSplit(complete.table);
        // A registration or account the public must not see says nothing about itself,
        // not even which branch or sport it belonged to.
        boolean hidden = copy.deleted && isSplit(complete.table);
        copy.branch = hidden ? "" : complete.branch;
        copy.sport = hidden ? "" : complete.sport;
        return copy;
    }
}
