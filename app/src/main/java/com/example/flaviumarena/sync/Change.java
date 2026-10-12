package com.example.flaviumarena.sync;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of one shared table, as it travels between a device and the server: either
 * its current values, or the fact that it was removed.
 */
public class Change {
    public final String table;
    /** The row's id, or its key columns joined with "|". */
    public final String key;
    public final boolean deleted;
    /** Column name to value (text, whole number, decimal number or null). Empty for a removal. */
    public final Map<String, Object> values;
    /** The server's revision of this row. 0 on a change that has not reached the server. */
    public long rev;

    public Change(String table, String key, boolean deleted, Map<String, Object> values) {
        this.table = table;
        this.key = key;
        this.deleted = deleted;
        this.values = values == null ? new LinkedHashMap<String, Object>() : values;
    }

    /** True when both describe the same state of the same row, whatever their revisions. */
    public boolean sameContentAs(Change other) {
        return other != null && table.equals(other.table) && key.equals(other.key)
                && deleted == other.deleted && values.equals(other.values);
    }
}
