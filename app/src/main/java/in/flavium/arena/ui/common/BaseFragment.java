package in.flavium.arena.ui.common;

import androidx.fragment.app.Fragment;

import in.flavium.arena.ui.main.MainActivity;
import in.flavium.arena.data.ArenaRepository;
import in.flavium.arena.model.User;

/** Parent of the four tabs. Gives them the repository and user held by MainActivity. */
public abstract class BaseFragment extends Fragment {

    protected MainActivity main() {
        return (MainActivity) requireActivity();
    }

    protected ArenaRepository repo() {
        return main().repo;
    }

    /**
     * Shows the current data. Each tab puts its loading here; it runs when the tab
     * opens and again when shared data changes while the tab is on screen.
     */
    protected void refreshScreen() {
    }

    /** Lets MainActivity ask the tab that is showing to reload. */
    public final void reload() {
        if (isResumed()) {
            refreshScreen();
        }
    }

    /** The logged-in user, or null for a guest. */
    protected User user() {
        return main().currentUser;
    }
}
