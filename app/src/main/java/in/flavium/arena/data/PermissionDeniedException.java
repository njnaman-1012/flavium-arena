package in.flavium.arena.data;

/** Thrown when a user's role does not allow the requested operation. */
public class PermissionDeniedException extends ArenaException {
    public PermissionDeniedException(String message) {
        super(message);
    }
}
