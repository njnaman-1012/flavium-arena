package com.example.flaviumarena.ui.branches;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.ui.common.RowAdapter;
import com.example.flaviumarena.ui.common.Ui;
import com.example.flaviumarena.ui.matches.MatchDetailActivity;
import com.example.flaviumarena.ui.registrations.RegistrationEditActivity;
import com.example.flaviumarena.ui.standings.MedalsActivity;
import com.example.flaviumarena.ui.standings.StandingsActivity;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.Branch;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.MedalWin;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.model.Sport;
import com.example.flaviumarena.scoring.Medals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything about one branch: its teams and participants in every sport, its fixtures
 * and its results.
 *
 * The public sees the approved entries. The captain of this branch (and Faculty and
 * Cores) also see pending and rejected ones, and can register new teams from here.
 * This is the captain's dashboard.
 */
public class BranchPageActivity extends BaseActivity {

    private String branchId;
    private Branch branch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_branch, "Branch", true);
        branchId = getIntent().getStringExtra(EXTRA_BRANCH_ID);
        findViewById(R.id.btnRegisterTeam).setOnClickListener(v -> chooseCategory());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScreen();
    }

    /** Shows the current data. Runs when the screen opens and when shared data changes. */
    @Override
    protected void refreshScreen() {
        branch = repo.getBranch(branchId);
        if (branch == null) {
            finish();
            return;
        }
        ((TextView) findViewById(R.id.tvBranchName)).setText(branch.name);
        show(findViewById(R.id.btnRegisterTeam), branch.active
                && AccessControl.canRegisterForBranch(currentUser, branchId));

        List<Registration> registrations = repo.getRegistrationsOfBranch(currentUser, branchId);
        List<Match> matches = repo.getMatchesOfBranch(branchId);
        // Medals of this branch, from the official results of the whole festival.
        List<MedalWin> medals = new ArrayList<>();
        for (MedalWin win : repo.getMedals(null)) {
            if (branchId.equals(win.branchId)) {
                medals.add(win);
            }
        }
        showSummary(registrations, matches, medals);
        showMedals(medals);
        showTeams(registrations);
        showMatches(matches);
    }

    private void showSummary(List<Registration> registrations, List<Match> matches,
                             List<MedalWin> medals) {
        boolean sensitive = AccessControl.canViewPrivateRegistrationData(currentUser, branchId);
        int approved = 0;
        int pending = 0;
        int rejected = 0;
        Set<String> sports = new HashSet<>();
        for (Registration r : registrations) {
            if (r.isApproved()) {
                approved++;
                sports.add(r.sportName);
            } else if (Registration.STATUS_PENDING.equals(r.status)) {
                pending++;
            } else {
                rejected++;
            }
        }

        // Record of this branch in finalised matches.
        int upcoming = 0;
        int won = 0;
        int drawn = 0;
        int lost = 0;
        for (Match m : matches) {
            if (!m.isFinal) {
                upcoming++;
                continue;
            }
            boolean sideA = branch.name.equals(m.branchAName);
            boolean sideB = branch.name.equals(m.branchBName);
            if (!m.hasScore() || (sideA && sideB)) {
                continue; // two entries of the same branch met: no branch result
            }
            int mine = sideA ? m.scoreA : m.scoreB;
            int theirs = sideA ? m.scoreB : m.scoreA;
            if (mine > theirs) {
                won++;
            } else if (mine < theirs) {
                lost++;
            } else {
                drawn++;
            }
        }

        StringBuilder text = new StringBuilder();
        text.append("Approved entries: ").append(approved);
        if (sensitive) {
            text.append("\nWaiting for approval: ").append(pending);
            text.append("\nRejected: ").append(rejected);
        }
        text.append("\nSports taking part in: ").append(sports.size());
        text.append("\nFixtures still to be decided: ").append(upcoming);
        text.append("\nFinal results: ").append(won).append(" won, ").append(drawn)
                .append(" drawn, ").append(lost).append(" lost");
        int gold = 0;
        int silver = 0;
        int bronze = 0;
        for (MedalWin win : medals) {
            if (Medals.GOLD.equals(win.medal)) {
                gold++;
            } else if (Medals.SILVER.equals(win.medal)) {
                silver++;
            } else {
                bronze++;
            }
        }
        text.append("\nMedals: ").append(gold).append(" gold, ").append(silver)
                .append(" silver, ").append(bronze).append(" bronze");
        if (!branch.active) {
            text.append("\nThis branch is deactivated.");
        }
        ((TextView) findViewById(R.id.tvBranchInfo)).setText(text.toString());
    }

    private void showMedals(List<MedalWin> medals) {
        LinearLayout list = findViewById(R.id.branchMedals);
        list.removeAllViews();
        show(findViewById(R.id.tvBranchMedalsTitle), !medals.isEmpty());
        for (MedalWin win : medals) {
            Ui.addCard(list, MedalsActivity.winRow(win), () -> {
                Intent intent = new Intent(this, StandingsActivity.class);
                intent.putExtra(EXTRA_CATEGORY_ID, win.categoryId);
                startActivity(intent);
            });
        }
    }

    private void showTeams(List<Registration> registrations) {
        LinearLayout list = findViewById(R.id.branchTeams);
        list.removeAllViews();
        boolean sensitive = AccessControl.canViewPrivateRegistrationData(currentUser, branchId);
        for (Registration r : registrations) {
            RowAdapter.Row row = new RowAdapter.Row(r.id, r.name,
                    r.sportName + ", " + r.categoryName, sensitive ? r.status : "",
                    Ui.sportPicture(r.sportName));
            row.imagePath = r.logoPath;
            Ui.addCard(list, row, () -> openRegistration(r));
        }
        show(findViewById(R.id.tvNoTeams), registrations.isEmpty());
    }

    private void showMatches(List<Match> matches) {
        LinearLayout list = findViewById(R.id.branchMatches);
        list.removeAllViews();
        for (Match m : matches) {
            Ui.addCard(list, Ui.matchRow(m), () -> {
                Intent intent = new Intent(this, MatchDetailActivity.class);
                intent.putExtra(EXTRA_MATCH_ID, m.id);
                startActivity(intent);
            });
        }
        show(findViewById(R.id.tvNoBranchMatches), matches.isEmpty());
    }

    private void openRegistration(Registration r) {
        if (AccessControl.canEditRegistration(userIn(r.sportId), r)
                || AccessControl.canSetLogo(userIn(r.sportId), r)) {
            Intent intent = new Intent(this, RegistrationEditActivity.class);
            intent.putExtra(EXTRA_CATEGORY_ID, r.categoryId);
            intent.putExtra(EXTRA_REGISTRATION_ID, r.id);
            startActivity(intent);
            return;
        }
        showMessage(Ui.registrationSummary(r, r.memberCount() > 0,
                AccessControl.canViewPrivateRegistrationData(currentUser, branchId)));
    }

    /** A registration needs a category, so the user picks one before the form opens. */
    private void chooseCategory() {
        List<String> names = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Sport sport : repo.getSports()) {
            for (Category c : repo.getCategories(sport.id)) {
                names.add(sport.name + ": " + c.name);
                ids.add(c.id);
            }
        }
        if (ids.isEmpty()) {
            showMessage("No categories have been added yet.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Register in")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    Intent intent = new Intent(this, RegistrationEditActivity.class);
                    intent.putExtra(EXTRA_CATEGORY_ID, ids.get(which));
                    intent.putExtra(EXTRA_BRANCH_ID, branchId);
                    startActivity(intent);
                })
                .show();
    }

    // ----- Downloads -----

    /** Everything this screen shows about the branch can be saved as a file. */
    @Override
    protected String[] downloadNames() {
        return new String[]{"Registrations (CSV)", "Fixtures and results (CSV)", "Medals (CSV)", "Branch report (PDF)"};
    }

    @Override
    protected Download downloadAt(int position) {
        if (position == 0) {
            return exports -> exports.branchRegistrations(branchId);
        }
        if (position == 1) {
            return exports -> exports.branchFixtures(branchId);
        }
        if (position == 2) {
            return exports -> exports.branchMedals(branchId);
        }
        return exports -> exports.branchReport(branchId);
    }
}
