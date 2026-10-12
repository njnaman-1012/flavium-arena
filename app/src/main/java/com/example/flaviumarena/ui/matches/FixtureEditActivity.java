package com.example.flaviumarena.ui.matches;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Spinner;

import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.data.ArenaException;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.model.SportTemplate;
import com.example.flaviumarena.model.User;
import com.example.flaviumarena.util.DateUtil;

import java.util.ArrayList;
import java.util.List;

/** Form used by a Core to create or edit a fixture. */
public class FixtureEditActivity extends BaseActivity {

    private Spinner spTeamA, spTeamB, spAssigned;
    private EditText etDate, etTime, etVenue, etRound, etLiveLink;

    private Match match;
    private final List<Registration> teams = new ArrayList<>();
    private List<User> asooCores = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String matchId = getIntent().getStringExtra(EXTRA_MATCH_ID);
        setupScreen(R.layout.activity_fixture_edit,
                matchId == null ? "Add fixture" : "Edit fixture", true);

        spTeamA = findViewById(R.id.spTeamA);
        spTeamB = findViewById(R.id.spTeamB);
        spAssigned = findViewById(R.id.spAssigned);
        etDate = findViewById(R.id.etMatchDate);
        etTime = findViewById(R.id.etMatchTime);
        etVenue = findViewById(R.id.etVenue);
        etRound = findViewById(R.id.etRound);
        etLiveLink = findViewById(R.id.etLiveLink);
        attachDatePicker(etDate);
        attachTimePicker(etTime);

        if (matchId == null) {
            match = new Match();
            match.categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
            match.date = DateUtil.today();
            match.time = "16:00";
            match.venue = "";
            match.round = "";
        } else {
            match = repo.getMatch(matchId);
            if (match == null) {
                finish();
                return;
            }
        }

        // Only approved registrations can play. Only a Core may load the Asoo Core list.
        try {
            for (Registration r : repo.getRegistrations(currentUser, match.categoryId)) {
                if (r.isApproved()) {
                    teams.add(r);
                }
            }
            asooCores = repo.getAsooCores(currentUser);
        } catch (ArenaException e) {
            toast(e.getMessage());
            finish();
            return;
        }
        if (teams.size() < 2) {
            toast("Approve at least two registrations before adding a fixture.");
            finish();
            return;
        }

        String[] teamNames = new String[teams.size()];
        for (int i = 0; i < teams.size(); i++) {
            Registration team = teams.get(i);
            // An individual is listed with the code of the branch they represent.
            teamNames[i] = team.memberCount() == 0 && !team.branchCode.isEmpty()
                    ? team.name + " (" + team.branchCode + ")" : team.name;
        }
        setSpinnerItems(spTeamA, teamNames);
        setSpinnerItems(spTeamB, teamNames);

        String[] asooNames = new String[asooCores.size() + 1];
        asooNames[0] = "Not assigned";
        for (int i = 0; i < asooCores.size(); i++) {
            asooNames[i + 1] = asooCores.get(i).name;
        }
        setSpinnerItems(spAssigned, asooNames);

        // Show the current values.
        spTeamA.setSelection(Math.max(0, indexOfTeam(match.teamAId)));
        spTeamB.setSelection(match.teamBId == null ? 1 : Math.max(0, indexOfTeam(match.teamBId)));
        for (int i = 0; i < asooCores.size(); i++) {
            if (asooCores.get(i).id.equals(match.assignedTo)) {
                spAssigned.setSelection(i + 1);
            }
        }
        etDate.setText(match.date);
        etTime.setText(match.time);
        etVenue.setText(match.venue);
        etRound.setText(match.round);
        etLiveLink.setText(match.externalUrl);

        // Only a cricket fixture can carry a CricHeroes link.
        Category category = repo.getCategory(match.categoryId);
        show(findViewById(R.id.panelLiveLink), category != null
                && SportTemplate.ENGINE_CRICKET.equals(category.engine));

        findViewById(R.id.btnSaveFixture).setOnClickListener(v -> save());
    }

    private int indexOfTeam(String registrationId) {
        for (int i = 0; i < teams.size(); i++) {
            if (teams.get(i).id.equals(registrationId)) {
                return i;
            }
        }
        return -1;
    }

    private void save() {
        match.teamAId = teams.get(spTeamA.getSelectedItemPosition()).id;
        match.teamBId = teams.get(spTeamB.getSelectedItemPosition()).id;
        match.date = textOf(etDate);
        match.time = textOf(etTime);
        match.venue = textOf(etVenue);
        match.round = textOf(etRound);
        match.externalUrl = textOf(etLiveLink);
        int assigned = spAssigned.getSelectedItemPosition();
        match.assignedTo = assigned <= 0 ? null : asooCores.get(assigned - 1).id;

        if (attempt(() -> repo.saveFixture(currentUser, match))) {
            toast("Fixture saved");
            finish();
        }
    }
}
