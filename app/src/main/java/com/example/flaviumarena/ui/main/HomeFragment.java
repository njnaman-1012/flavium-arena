package com.example.flaviumarena.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.auth.LoginActivity;
import com.example.flaviumarena.ui.branches.BranchPageActivity;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.ui.common.BaseFragment;
import com.example.flaviumarena.ui.common.RowAdapter;
import com.example.flaviumarena.ui.common.Ui;
import com.example.flaviumarena.ui.matches.MatchDetailActivity;
import com.example.flaviumarena.ui.sports.CategoriesActivity;
import com.example.flaviumarena.ui.sports.SportsActivity;
import com.example.flaviumarena.ui.standings.MedalsActivity;
import com.example.flaviumarena.ui.standings.StandingsActivity;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.DashboardStats;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.MedalRow;
import com.example.flaviumarena.model.Sport;

import java.util.ArrayList;
import java.util.List;

/** Home tab: greeting, quick actions, banners, next matches, a role card and sports. */
public class HomeFragment extends BaseFragment {

    private static final int MAX_MEDAL_ROWS = 3;
    private static final int MAX_MATCHES = 3;
    private static final int MAX_SPORTS = 4;

    private View root;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        root = inflater.inflate(R.layout.fragment_home, container, false);

        root.findViewById(R.id.actSports).setOnClickListener(v ->
                main().selectTab(R.id.nav_discover));
        root.findViewById(R.id.actFixtures).setOnClickListener(v -> openMatches(false, false));
        root.findViewById(R.id.actResults).setOnClickListener(v -> openMatches(true, false));
        root.findViewById(R.id.actTables).setOnClickListener(v -> choosePointsTable());
        root.findViewById(R.id.btnAllMatches).setOnClickListener(v -> openMatches(false, false));
        root.findViewById(R.id.btnAllMedals).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), MedalsActivity.class)));
        root.findViewById(R.id.btnAllSports).setOnClickListener(v ->
                main().selectTab(R.id.nav_discover));

        setUpBanners();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        showGreeting();
        showMedals();
        showMatches();
        showAccessCard();
        showSports();
    }

    private void openMatches(boolean results, boolean onlyMine) {
        main().openResultsTab = results;
        main().openOnlyMine = onlyMine;
        main().selectTab(R.id.nav_matches);
    }

    // ----- Greeting -----

    private void showGreeting() {
        TextView tvAvatar = root.findViewById(R.id.tvAvatar);
        TextView tvGreeting = root.findViewById(R.id.tvGreeting);
        if (user() == null) {
            tvAvatar.setText("G");
            tvGreeting.setText(Ui.greeting());
        } else {
            String firstName = user().name.split(" ")[0];
            tvAvatar.setText(Ui.initial(user().name));
            tvGreeting.setText(Ui.greeting() + ", " + firstName);
        }
    }

    // ----- Banners -----

    private void setUpBanners() {
        ViewPager2 pager = root.findViewById(R.id.bannerPager);
        LinearLayout dots = root.findViewById(R.id.bannerDots);
        BannerAdapter adapter = new BannerAdapter();
        pager.setAdapter(adapter);
        Ui.showDots(dots, adapter.getItemCount(), 0);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                Ui.showDots(dots, adapter.getItemCount(), position);
            }
        });
    }

    /** Two fixed banners. Each page uses item_banner.xml. */
    private static class BannerAdapter extends RecyclerView.Adapter<BannerAdapter.Holder> {

        private static final String[] TAGS = {"FLAVIUM ARENA", "POINTS TABLES"};
        private static final String[] TITLES = {"Every match, live", "Who is on top?"};
        private static final String[] TEXTS = {
                "Follow scores as the organisers update them.",
                "Standings change as soon as a result is finalised."};
        private static final int[] BACKGROUNDS = {
                R.drawable.bg_banner_indigo, R.drawable.bg_banner_teal};
        private static final int[] PICTURES = {R.drawable.ill_football, R.drawable.ill_board};

        static class Holder extends RecyclerView.ViewHolder {
            Holder(View view) {
                super(view);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_banner, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            View v = holder.itemView;
            v.findViewById(R.id.bannerRoot).setBackgroundResource(BACKGROUNDS[position]);
            ((TextView) v.findViewById(R.id.tvBannerTag)).setText(TAGS[position]);
            ((TextView) v.findViewById(R.id.tvBannerTitle)).setText(TITLES[position]);
            ((TextView) v.findViewById(R.id.tvBannerText)).setText(TEXTS[position]);
            ((ImageView) v.findViewById(R.id.ivBanner)).setImageResource(PICTURES[position]);
        }

        @Override
        public int getItemCount() {
            return TITLES.length;
        }
    }

    // ----- Medal table -----

    /** The leading branches of the medal table, once at least one medal is decided. */
    private void showMedals() {
        LinearLayout list = root.findViewById(R.id.homeMedals);
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        int shown = 0;
        for (MedalRow m : repo().getMedalTable()) {
            if (m.total() == 0 || shown == MAX_MEDAL_ROWS) {
                break; // the table is ordered, so the rest have nothing either
            }
            View row = inflater.inflate(R.layout.item_medal_row, list, false);
            MedalsActivity.bind(row, m);
            row.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), MedalsActivity.class)));
            list.addView(row);
            shown++;
        }
        root.findViewById(R.id.tvNoHomeMedals)
                .setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    // ----- Live and upcoming matches -----

    private void showMatches() {
        LinearLayout list = root.findViewById(R.id.homeMatches);
        list.removeAllViews();

        // Live matches first, then upcoming ones, in date order.
        List<Match> all = repo().getMatches(null);
        List<Match> chosen = new ArrayList<>();
        for (Match m : all) {
            if (m.isUnderway()) {
                chosen.add(m);
            }
        }
        for (Match m : all) {
            if (Match.STATUS_UPCOMING.equals(m.status)) {
                chosen.add(m);
            }
        }

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        int margin = (int) (10 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < chosen.size() && i < MAX_MATCHES; i++) {
            Match m = chosen.get(i);
            View card = inflater.inflate(R.layout.row_item, list, false);
            RowAdapter.bind(card, Ui.matchRow(m));
            card.setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(), MatchDetailActivity.class);
                intent.putExtra(BaseActivity.EXTRA_MATCH_ID, m.id);
                startActivity(intent);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = margin;
            list.addView(card, params);
        }
        root.findViewById(R.id.tvNoMatches)
                .setVisibility(chosen.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ----- Lavender card that depends on the role -----

    private void showAccessCard() {
        TextView tvText = root.findViewById(R.id.tvAccessText);
        TextView tvAction = root.findViewById(R.id.tvAccessAction);
        View card = root.findViewById(R.id.cardAccess);

        if (AccessControl.canViewDashboard(user())) {
            DashboardStats s = repo().getStats(user());
            tvText.setText(s.pendingRegistrations + " registrations waiting for approval and "
                    + s.awaitingFinalisation + " results waiting to be finalised.");
            tvAction.setText("Manage sports");
            card.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), SportsActivity.class)));
        } else if (AccessControl.isAsooCore(user())) {
            int mine = 0;
            for (Match m : repo().getMatches(null)) {
                if (!m.isFinal && user().id.equals(m.assignedTo)) {
                    mine++;
                }
            }
            tvText.setText("You have " + mine + " assigned matches that are not final yet.");
            tvAction.setText("Open my matches");
            card.setOnClickListener(v -> openMatches(false, true));
        } else if (AccessControl.isCoach(user())) {
            tvText.setText(user().coachSportNames.isEmpty()
                    ? "You are a Coach. Faculty has not assigned a sport to you yet."
                    : "You are the Coach for " + user().coachSportNames + ". You manage "
                    + "their categories, registrations, fixtures and results.");
            tvAction.setText("Open sports");
            card.setOnClickListener(v -> main().selectTab(R.id.nav_discover));
        } else if (AccessControl.isCaptain(user())) {
            tvText.setText("You are the Branch CR of " + user().branchName
                    + ". Register your teams and follow their fixtures.");
            tvAction.setText("Manage my branch");
            card.setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(), BranchPageActivity.class);
                intent.putExtra(BaseActivity.EXTRA_BRANCH_ID, user().branchId);
                startActivity(intent);
            });
        } else {
            tvText.setText("Are you an organiser? Log in to update scores and manage the "
                    + "festival.");
            tvAction.setText("Organiser login");
            card.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), LoginActivity.class)));
        }
    }

    // ----- Sports -----

    private void showSports() {
        LinearLayout tiles = root.findViewById(R.id.homeSports);
        tiles.removeAllViews();
        List<Sport> sports = repo().getSports();
        int count = Math.min(sports.size(), MAX_SPORTS);
        for (int i = 0; i < count; i++) {
            Sport sport = sports.get(i);
            int categories = repo().getCategories(sport.id).size();
            View tile = Ui.addTile(tiles, i, Ui.sportPicture(sport.name), sport.name,
                    categories + (categories == 1 ? " category" : " categories"));
            tile.setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(), CategoriesActivity.class);
                intent.putExtra(BaseActivity.EXTRA_SPORT_ID, sport.id);
                startActivity(intent);
            });
        }
        Ui.padLastRow(tiles, count);
    }

    // ----- Points tables -----

    /** Lists every category so the user can pick whose points table to open. */
    private void choosePointsTable() {
        List<String> names = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Sport sport : repo().getSports()) {
            for (Category c : repo().getCategories(sport.id)) {
                names.add(sport.name + ": " + c.name);
                ids.add(c.id);
            }
        }
        if (ids.isEmpty()) {
            main().showMessage("No categories have been added yet.");
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Points table for")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    Intent intent = new Intent(requireContext(), StandingsActivity.class);
                    intent.putExtra(BaseActivity.EXTRA_CATEGORY_ID, ids.get(which));
                    startActivity(intent);
                })
                .show();
    }
}
