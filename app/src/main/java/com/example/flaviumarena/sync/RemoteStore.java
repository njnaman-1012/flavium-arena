package com.example.flaviumarena.sync;

import com.example.flaviumarena.data.ArenaException;

import java.util.List;

/**
 * The shared server, as the synchronisation engine sees it. The engine does not know
 * what is behind this interface: in the tests it is a simple in-memory store, and in
 * the online app it will be Cloud Firestore.
 *
 * Every row on the server has a revision number that grows each time the row changes.
 */
public interface RemoteStore {

    /**
     * Stores a change, but only if the server's copy of the row still has the revision
     * the device last saw ("baseRev"; 0 means the device believes the row is new).
     * Returns the row's new revision.
     *
     * @throws Conflict    the server's copy has changed since; it is carried in the exception
     * @throws Refused     the server's rules do not allow this account to make the change
     * @throws Unavailable the server could not be reached; nothing was stored
     */
    long push(Change change, long baseRev);

    /** Every change the server has seen after the given revision, oldest first. */
    List<Change> changesSince(long revision);

    /** The server's copy of one row (a removal if it was removed), or null if it never existed. */
    Change get(String table, String key);

    // ----- The shared festival as a whole -----

    /** Who uploaded (or is uploading) the festival, and whether that upload is complete. */
    Dataset dataset();

    /**
     * Reserves an empty server for one device, so that two devices cannot both upload
     * their own festival. Returns false when another device holds the reservation or
     * the festival is already complete. Asking again with the same device is allowed
     * (that is how an interrupted upload continues).
     */
    boolean claim(long deviceId);

    /** Declares the upload of that device complete: other devices may now join. */
    void markReady(long deviceId);

    class Dataset {
        /** 0 while nobody has started an upload. */
        public final long owner;
        public final boolean ready;

        public Dataset(long owner, boolean ready) {
            this.owner = owner;
            this.ready = ready;
        }
    }

    /** Someone else changed the row first. */
    class Conflict extends ArenaException {
        public final Change current;

        public Conflict(Change current) {
            super("Someone else changed this record first.");
            this.current = current;
        }
    }

    /** The server's access rules said no. */
    class Refused extends ArenaException {
        public Refused(String message) {
            super(message);
        }
    }

    /** No connection, or the server did not answer. The change stays queued. */
    class Unavailable extends ArenaException {
        public Unavailable(String message) {
            super(message);
        }
    }
}
