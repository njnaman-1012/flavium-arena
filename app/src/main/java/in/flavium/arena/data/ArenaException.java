package in.flavium.arena.data;

/** Base class for every error the repository reports to the screens. */
public class ArenaException extends RuntimeException {
    public ArenaException(String message) {
        super(message);
    }
}
