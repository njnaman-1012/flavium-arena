package in.flavium.arena.sync;

import in.flavium.arena.data.ArenaException;

/** A synchronisation step that cannot be done in the current state, with the reason. */
public class SyncException extends ArenaException {
    public SyncException(String message) {
        super(message);
    }
}
