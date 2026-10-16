package in.flavium.arena.ui.common;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import in.flavium.arena.R;
import in.flavium.arena.data.ArenaException;
import in.flavium.arena.data.DevData;
import in.flavium.arena.ui.admin.SelfTestActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * DEVELOPMENT ONLY. This file lives in app/src/debug: the login helper and the
 * permission self-test are part of debug builds only. A release build gets the empty
 * class of the same name in app/src/release, so none of this is in it.
 */
public final class DevTools {

    /** A development screen listed on the More tab. */
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

    /** Development screens for the More tab. */
    public static List<Link> moreLinks() {
        List<Link> links = new ArrayList<>();
        links.add(new Link("Permission self-test", SelfTestActivity.class));
        return links;
    }

    /**
     * Shows, in the given empty slot of the login form, a button that signs in with the
     * development account of the role. The password of a development account is made
     * anew each time the app starts (none is written in the source code), so the button
     * first creates the account or sets it to the current password, then fills in the
     * form. Nothing else in the database is changed.
     */
    public static void showLoginHelper(BaseActivity activity, ViewGroup slot, String role,
                                       EditText email, EditText password) {
        slot.removeAllViews();
        final String[] account = DevData.account(role);
        boolean devBuild = activity.getResources().getBoolean(R.bool.seed_dev_data);
        if (account == null || !devBuild) {
            slot.setVisibility(View.GONE);
            return;
        }
        View panel = activity.getLayoutInflater().inflate(R.layout.panel_dev_login, slot, true);
        panel.findViewById(R.id.btnFillDev).setOnClickListener(v -> {
            try {
                String done = DevData.createOrRepair(activity, role);
                email.setText(account[0]);
                password.setText(account[1]);
                Toast.makeText(activity, done, Toast.LENGTH_SHORT).show();
            } catch (ArenaException e) {
                activity.showMessage(e.getMessage());
            }
        });
        slot.setVisibility(View.VISIBLE);
    }
}
