package com.example.flaviumarena.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.example.flaviumarena.ui.common.BaseFragment;
import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.auth.Session;
import com.example.flaviumarena.cloud.Cloud;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * The shell of the app: a bottom bar with four tabs (Home, Discover, Matches, More).
 * Each tab is a Fragment shown inside the container above the bar.
 */
public class MainActivity extends BaseActivity {

    private BottomNavigationView bottomNav;

    // Set by a tab before it switches to the Matches tab, then read by MatchesFragment.
    public boolean openResultsTab;
    public boolean openOnlyMine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (databaseFailed) {
            return; // BaseActivity is already showing the error message.
        }

        // First launch: show the welcome pages instead.
        if (!Session.hasSeenOnboarding(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        applyInsets();

        bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });
        if (savedInstanceState == null) {
            showTab(R.id.nav_home);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        showSyncState();
    }

    /** Shared data changed: the tab that is showing loads it again. */
    @Override
    protected void refreshScreen() {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof BaseFragment) {
                ((BaseFragment) fragment).reload();
            }
        }
        showSyncState();
    }

    @Override
    public void onSyncStatusChanged() {
        showSyncState();
    }

    /**
     * A line above the tabs while a connected device is offline or has changes that
     * have not reached the server yet. Hidden when everything is sent.
     */
    private void showSyncState() {
        TextView banner = findViewById(R.id.tvSyncBanner);
        if (banner == null) {
            return;
        }
        Cloud cloud = Cloud.get(this);
        if (!cloud.isConnected()) {
            show(banner, false);
            return;
        }
        int pending = cloud.status().pending;
        String changes = pending == 1 ? "1 change" : pending + " changes";
        String text;
        if (!cloud.isOnline()) {
            text = pending == 0
                    ? "You are offline. You can keep working; changes sync automatically "
                            + "when you are back online."
                    : "You are offline. " + changes + " will sync automatically when you are "
                            + "back online.";
        } else if (pending > 0) {
            text = cloud.status().lastError.isEmpty()
                    ? "Syncing " + changes + "..."
                    : "Sync temporarily unavailable. We'll retry automatically.";
        } else {
            text = null;
        }
        show(banner, text != null);
        if (text != null) {
            banner.setText(text);
        }
    }

    private void showTab(int itemId) {
        Fragment fragment;
        if (itemId == R.id.nav_discover) {
            fragment = new DiscoverFragment();
        } else if (itemId == R.id.nav_matches) {
            fragment = new MatchesFragment();
        } else if (itemId == R.id.nav_more) {
            fragment = new MoreFragment();
        } else {
            fragment = new HomeFragment();
        }
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
    }

    /** Lets a tab switch to another tab, as if the user had tapped the bottom bar. */
    public void selectTab(int itemId) {
        bottomNav.setSelectedItemId(itemId);
    }
}
