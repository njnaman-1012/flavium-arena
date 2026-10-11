package com.example.flaviumarena;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Spinner;

import com.example.flaviumarena.data.ArenaException;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.EventRound;
import com.example.flaviumarena.model.SportTemplate;
import com.example.flaviumarena.model.User;
import com.example.flaviumarena.util.DateUtil;

import java.util.ArrayList;
import java.util.List;

/** Form used by Faculty or a Core to schedule a heat or a final. */
public class RoundEditActivity extends BaseActivity {

    private Spinner spStage, spAssigned;
    private EditText etHeatNumber, etDate, etTime, etVenue;

    private EventRound round;
    private boolean heatsUsed;
    private List<User> asooCores = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String roundId = getIntent().getStringExtra(EXTRA_ROUND_ID);
        setupScreen(R.layout.activity_round_edit,
                roundId == null ? "Schedule an event" : "Edit event", true);

        spStage = findViewById(R.id.spStage);
        spAssigned = findViewById(R.id.spRoundAssigned);
        etHeatNumber = findViewById(R.id.etHeatNumber);
        etDate = findViewById(R.id.etRoundDate);
        etTime = findViewById(R.id.etRoundTime);
        etVenue = findViewById(R.id.etRoundVenue);
        attachDatePicker(etDate);
        attachTimePicker(etTime);

        if (roundId == null) {
            round = new EventRound();
            round.categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
            round.date = DateUtil.today();
            round.time = "16:00";
            round.venue = "";
        } else {
            round = repo.getRound(roundId);
        }
        Category category = round == null ? null : repo.getCategory(round.categoryId);
        if (category == null) {
            finish();
            return;
        }
        // Only a Core may load the list of Asoo Cores.
        try {
            asooCores = repo.getAsooCores(currentUser);
        } catch (ArenaException e) {
            toast(e.getMessage());
            finish();
            return;
        }

        // Only a race that is run in heats has the choice between heat and final.
        heatsUsed = SportTemplate.ENGINE_TIMED.equals(category.engine)
                && category.ruleSet().isYes("heats");
        if (roundId == null) {
            int heatCount = 0;
            for (EventRound existing : repo.getRounds(category.id)) {
                if (existing.isHeat()) {
                    heatCount++;
                }
            }
            round.stage = heatsUsed ? EventRound.STAGE_HEAT : EventRound.STAGE_FINAL;
            round.heatNumber = heatsUsed ? heatCount + 1 : 0;
        }
        show(findViewById(R.id.panelStage), heatsUsed);
        setSpinnerItems(spStage, EventRound.STAGE_LABELS);
        spStage.setSelection(round.isHeat() ? 0 : 1);
        onSpinnerChanged(spStage, this::showHeatNumber);

        String[] asooNames = new String[asooCores.size() + 1];
        asooNames[0] = "Not assigned";
        for (int i = 0; i < asooCores.size(); i++) {
            asooNames[i + 1] = asooCores.get(i).name;
        }
        setSpinnerItems(spAssigned, asooNames);
        for (int i = 0; i < asooCores.size(); i++) {
            if (asooCores.get(i).id.equals(round.assignedTo)) {
                spAssigned.setSelection(i + 1);
            }
        }

        etHeatNumber.setText(round.heatNumber > 0 ? String.valueOf(round.heatNumber) : "");
        etDate.setText(round.date);
        etTime.setText(round.time);
        etVenue.setText(round.venue);
        showHeatNumber();

        findViewById(R.id.btnSaveRound).setOnClickListener(v -> save());
    }

    /** The heat number is asked only for a heat. */
    private void showHeatNumber() {
        boolean heat = spStage.getSelectedItemPosition() == 0;
        show(findViewById(R.id.tvHeatNumberLabel), heat);
        show(etHeatNumber, heat);
    }

    private void save() {
        boolean heat = heatsUsed && spStage.getSelectedItemPosition() == 0;
        round.stage = heat ? EventRound.STAGE_HEAT : EventRound.STAGE_FINAL;
        round.heatNumber = heat ? numberOf(etHeatNumber, 0) : 0;
        round.date = textOf(etDate);
        round.time = textOf(etTime);
        round.venue = textOf(etVenue);
        int assigned = spAssigned.getSelectedItemPosition();
        round.assignedTo = assigned <= 0 ? null : asooCores.get(assigned - 1).id;

        if (attempt(() -> repo.saveRound(currentUser, round))) {
            toast("Saved");
            finish();
        }
    }
}
