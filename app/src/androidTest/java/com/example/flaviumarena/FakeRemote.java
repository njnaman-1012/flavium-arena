package com.example.flaviumarena;

import com.example.flaviumarena.sync.Change;
import com.example.flaviumarena.sync.RemoteStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A stand-in for the shared server, kept in memory, for the synchronisation tests.
 * It follows the same contract the real server will: one revision counter, a change is
 * accepted only when it quotes the row's current revision, and removed rows are
 * remembered as removed.
 */
public class FakeRemote implements RemoteStore {

    private final Map<String, Change> rows = new LinkedHashMap<>();
    private long revision;

    /** While true the server cannot be reached, as when a phone has no connection. */
    public boolean offline;
    /** Tables this server refuses changes to, as its access rules would. */
    public final Set<String> refusedTables = new HashSet<>();
    /** How many changes were accepted, to prove nothing is stored twice. */
    public int accepted;
    /** When 0 or more: the connection drops once this many changes have been accepted. */
    public int dropAfter = -1;

    private static Change copy(Change c) {
        Change copy = new Change(c.table, c.key, c.deleted, new LinkedHashMap<>(c.values));
        copy.rev = c.rev;
        return copy;
    }

    @Override
    public long push(Change change, long baseRev) {
        if (offline || (dropAfter >= 0 && accepted >= dropAfter)) {
            throw new Unavailable("No connection.");
        }
        if (refusedTables.contains(change.table)) {
            throw new Refused("You are not allowed to change this.");
        }
        Change current = rows.get(change.table + "/" + change.key);
        long currentRev = current == null ? 0 : current.rev;
        if (currentRev != baseRev) {
            throw new Conflict(current == null
                    ? new Change(change.table, change.key, true, null) : copy(current));
        }
        Change stored = copy(change);
        stored.rev = ++revision;
        rows.put(change.table + "/" + change.key, stored);
        accepted++;
        return stored.rev;
    }

    @Override
    public List<Change> changesSince(long since) {
        if (offline) {
            throw new Unavailable("No connection.");
        }
        List<Change> list = new ArrayList<>();
        for (Change c : rows.values()) {
            if (c.rev > since) {
                list.add(copy(c));
            }
        }
        Collections.sort(list, new Comparator<Change>() {
            @Override
            public int compare(Change x, Change y) {
                return Long.compare(x.rev, y.rev);
            }
        });
        return list;
    }

    @Override
    public Change get(String table, String key) {
        if (offline) {
            throw new Unavailable("No connection.");
        }
        Change current = rows.get(table + "/" + key);
        return current == null ? null : copy(current);
    }

    // ----- The festival as a whole -----

    private long owner;
    private boolean ready;

    @Override
    public Dataset dataset() {
        if (offline) {
            throw new Unavailable("No connection.");
        }
        return new Dataset(owner, ready);
    }

    @Override
    public synchronized boolean claim(long deviceId) {
        if (offline) {
            throw new Unavailable("No connection.");
        }
        if (ready || (owner != 0 && owner != deviceId)) {
            return false;
        }
        owner = deviceId;
        return true;
    }

    @Override
    public void markReady(long deviceId) {
        if (offline) {
            throw new Unavailable("No connection.");
        }
        if (owner == deviceId) {
            ready = true;
        }
    }

    /** Every row the server holds, including removed ones. */
    public List<Change> all() {
        return new ArrayList<>(rows.values());
    }
}
