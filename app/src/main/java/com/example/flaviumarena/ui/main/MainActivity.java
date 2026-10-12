package com.example.flaviumarena.ui.main;

import android.content.Intent;
import android.os.Bundle;

import androidx.fragment.app.Fragment;

import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.auth.Session;
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
