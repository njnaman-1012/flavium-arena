package in.flavium.arena.ui.common;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.EditText;

import java.util.Collections;
import java.util.List;

/**
 * Release builds have no development tools: no development login helper and no
 * permission self-test. The debug twin is in app/src/debug.
 */
public final class DevTools {

    /** A development screen listed on the More tab (never one in a release build). */
    public static final class Link {
        public final String title;
        public final Class<? extends Activity> screen;

        Link(String title, Class<? extends Activity> screen) {
            this.title = title;
            this.screen = screen;
        }
    }

    private DevTools() {
    }

    public static List<Link> moreLinks() {
        return Collections.emptyList();
    }

    public static void showLoginHelper(BaseActivity activity, ViewGroup slot, String role,
                                       EditText email, EditText password) {
        // Nothing: the slot stays hidden.
    }
}
