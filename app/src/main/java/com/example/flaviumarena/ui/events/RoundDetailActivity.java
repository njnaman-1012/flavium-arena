package com.example.flaviumarena.ui.events;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.ui.common.RowAdapter;
import com.example.flaviumarena.ui.common.Ui;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.data.ArenaException;
import com.example.flaviumarena.model.AppSettings;
import com.example.flaviumarena.model.EventEntry;
import com.example.flaviumarena.model.EventRound;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.model.RuleSet;
import com.example.flaviumarena.model.ScoreAudit;
import com.example.flaviumarena.scoring.Measure;
import com.example.flaviumarena.util.DateUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * One heat or final.
 *
 * Everyone sees the entrants with their results and positions. How a result is entered
 * depends on the scoring engine of the category:
 *
 *   TIMED     a lane and a finishing time for each entrant (races, relays, swimming)
 *   MEASURED  several attempts for each entrant; the best valid one counts (throws)
 *
 * Positions and qualifiers are worked out by the repository after every change. The
 * controls appear only for someone who may use them, and the repository checks again.
 */
public class RoundDetailActivity extends BaseActivity {

    private String roundId;
    private EventRound round;
    private List<EventEntry> entries = new ArrayList<>();
    private RuleSet rules = new RuleSet();

    private Spinner spStatus;
    private EditText etReason;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_round_detail, "Event", true);
        roundId = getIntent().getStringExtra(EXTRA_ROUND_ID);

        spStatus = findViewById(R.id.spStatus);
        etReason = findViewById(R.id.etReason);
        setSpinnerItems(spStatus, Match.STATUS_LABELS);

        findViewById(R.id.btnSetStatus).setOnClickListener(v -> change(() ->
                repo.setRoundStatus(currentUser, roundId, round.version,
                        Match.STATUSES[spStatus.getSelectedItemPosition()])));
        findViewById(R.id.btnAddEntrant).setOnClickListener(v -> showAddEntrantDialog());

        findViewById(R.id.btnFinalise).setOnClickListener(v ->
                showDialog("Make these results official? Afterwards they can only be changed "
                                + "by reopening them with a recorded reason.",
                        R.drawable.ic_check, false, "Make official", "Cancel", () -> change(() ->
                                repo.finaliseRound(currentUser, roundId, round.version))));
        findViewById(R.id.btnReopen).setOnClickListener(v -> change(() ->
                repo.reopenRound(currentUser, roundId, round.version, textOf(etReason))));
        findViewById(R.id.btnEditRound).setOnClickListener(v -> {
            Intent intent = new Intent(this, RoundEditActivity.class);
            intent.putExtra(EXTRA_ROUND_ID, roundId);
            startActivity(intent);
        });
        findViewById(R.id.btnDeleteRound).setOnClickListener(v ->
                confirm("Delete this event with its entrants, results and history?", () -> {
                    if (attempt(() -> repo.deleteRound(currentUser, roundId))) {
                        toast("Event deleted");
                        finish();
                    }
                }));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /**
     * Runs one change and then redraws the screen. The screen is redrawn even when the
     * change was refused, so after a conflict the other person's latest results are shown.
     */
    private void change(Runnable action) {
        attempt(action);
        refresh();
    }

    /** Reads the event again and redraws the whole screen. */
    private void refresh() {
        round = repo.getRound(roundId);
        if (round == null) {
            finish();
            return;
        }
        entries = repo.getEntries(roundId);
        rules = round.rules();

        showHeader();
        showEntrants();
        showDetails();
        showControls();
        showHistory();
    }

    private boolean mayScore() {
        return AccessControl.canScore(userIn(round.sportId), round.assignedTo, round.isFinal)
                && !round.isFinal;
    }

    private boolean mayManage() {
        return AccessControl.canManageFixtures(userIn(round.sportId)) && !round.isFinal;
    }

    // =====================================================================
    // What everyone sees
    // =====================================================================

    private void showHeader() {
        setText(R.id.tvStatus, round.statusLabel());
        setText(R.id.tvRoundTitle, round.title());
        setText(R.id.tvRoundCaption, round.sportName + ", " + round.categoryName);

        // Completed results stay provisional until they have been made official.
        boolean provisional = !round.isFinal && Match.STATUS_COMPLETED.equals(round.status);
        TextView tvNote = findViewById(R.id.tvStatusNote);
        show(tvNote, provisional);
        tvNote.setText("Provisional results. They become official when Faculty or an "
                + "authorised Core confirms them.");
    }

    private void showEntrants() {
        LinearLayout rows = findViewById(R.id.entryRows);
        rows.removeAllViews();
        boolean tappable = mayScore() || mayManage();

        String hint;
        if (entries.isEmpty()) {
            hint = "Nobody has been entered yet.";
        } else if (tappable) {
            hint = "Tap an entrant to enter or correct a result.";
        } else {
            hint = round.isMeasured() ? "The best valid attempt counts."
                    : "The fastest time wins.";
        }
        setText(R.id.tvEntrantsHint, hint);

        for (EventEntry e : entries) {
            RowAdapter.Row values = new RowAdapter.Row(e.id, e.name, subtitleOf(e), badgeOf(e));
            // The circle shows the position once the entrant has one.
            values.circleText = e.position == null ? "-" : String.valueOf(e.position);
            Ui.addCard(rows, values, tappable ? () -> showEntrantMenu(e) : null);
        }
    }

    /** Second line of an entrant's card: branch, lane and (for throws) every attempt. */
    private String subtitleOf(EventEntry e) {
        StringBuilder text = new StringBuilder();
        // A relay team is already named after its branch and year.
        if (!e.team) {
            text.append(Registration.branchLine(e.branchName, e.year));
        }
        if (!round.isMeasured()) {
            text.append(text.length() > 0 ? "\n" : "").append("Lane ").append(e.lane);
            return text.toString();
        }
        if (!e.attempts.isEmpty()) {
            text.append(text.length() > 0 ? "\n" : "");
            for (EventEntry.Attempt a : e.attempts) {
                text.append(a.number > 1 ? "   " : "").append(a.number).append(": ")
                        .append(a.foul || a.distanceCm == null ? "foul"
                                : Measure.formatDistance(a.distanceCm));
            }
        }
        return text.toString();
    }

    /** Right-hand side of an entrant's card: the result that counts. */
    private String badgeOf(EventEntry e) {
        if (!e.hasNormalStatus()) {
            return e.resultStatus;
        }
        if (round.isMeasured()) {
            return e.bestCm == null ? "" : Measure.formatDistance(e.bestCm) + " m";
        }
        if (e.timeMs == null) {
            return "";
        }
        return Measure.formatTime(e.timeMs) + (e.qualified ? "\nQualified" : "");
    }

    private void showDetails() {
        StringBuilder info = new StringBuilder();
        info.append("When: ").append(round.date)
                .append(round.time.isEmpty() ? "" : " " + round.time);
        info.append("\nVenue: ").append(round.venue.isEmpty() ? "to be announced" : round.venue);
        info.append("\nScorer: ")
                .append(round.assignedName.isEmpty() ? "not assigned" : round.assignedName);

        if (round.isMeasured()) {
            int attempts = Math.max(1, rules.getInt("attempts", 3));
            int finalists = Math.max(0, rules.getInt("finalists", 0));
            int extra = Math.max(0, rules.getInt("extra_attempts", 0));
            info.append("\nAttempts: ").append(attempts);
            if (finalists > 0 && extra > 0) {
                info.append(", then ").append(extra).append(" more for the best ")
                        .append(finalists);
            }
        } else {
            info.append("\nLanes: ").append(Math.max(1, rules.getInt("lanes", 8)));
            if (round.isHeat()) {
                info.append("\nQualifying: first ").append(rules.getInt("qualify_per_heat", 0))
                        .append(" of each heat");
                int fastest = rules.getInt("fastest_losers", 0);
                if (fastest > 0) {
                    info.append(", plus the ").append(fastest).append(" fastest of the rest");
                }
            }
        }
        info.append("\nLast updated: ").append(DateUtil.formatTimestamp(round.updatedAt));
        if (!round.updatedByName.isEmpty()) {
            info.append(" by ").append(round.updatedByName);
        }
        setText(R.id.tvRoundInfo, info.toString());
    }

    // =====================================================================
    // Controls
    // =====================================================================

    private void showControls() {
        // Faculty always; a Core only when Faculty has allowed it.
        boolean canFinalise = AccessControl.canFinaliseResults(userIn(round.sportId),
                repo.getFlag(AppSettings.CORE_CAN_FINALISE));

        show(findViewById(R.id.scorePanel), mayScore());
        show(findViewById(R.id.btnAddEntrant), mayManage());
        show(findViewById(R.id.btnFinalise), canFinalise && !round.isFinal
                && Match.STATUS_COMPLETED.equals(round.status));
        show(findViewById(R.id.correctionPanel), canFinalise && round.isFinal);
        show(findViewById(R.id.corePanel), AccessControl.canManageFixtures(userIn(round.sportId)));
        etReason.setText("");

        for (int i = 0; i < Match.STATUSES.length; i++) {
            if (Match.STATUSES[i].equals(round.status)) {
                spStatus.setSelection(i);
            }
        }
    }

    /** The things that can be done with one entrant, as a short list. */
    private void showEntrantMenu(EventEntry e) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (mayScore()) {
            if (round.isMeasured()) {
                labels.add("Enter or correct an attempt");
                actions.add(() -> showAttemptDialog(e));
            } else {
                labels.add("Enter or correct the time");
                actions.add(() -> showTimeDialog(e));
            }
            labels.add("Disqualified, did not start, did not finish");
            actions.add(() -> showStatusDialog(e));
        }
        if (mayManage()) {
            labels.add("Remove from this event");
            actions.add(() -> confirm("Remove " + e.name + " from this event?", () ->
                    change(() -> repo.removeEntry(currentUser, e.id))));
        }
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(labels.toArray(new String[0]),
                        (dialog, which) -> actions.get(which).run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ----- Races -----

    private void showTimeDialog(EventEntry e) {
        EntryDialog dialog = new EntryDialog("Lane " + e.lane + ": " + e.name);
        dialog.value("Time (12.34 or 1:02.35)",
                e.timeMs == null ? "" : Measure.formatTime(e.timeMs), InputType.TYPE_CLASS_TEXT);
        dialog.show((choice, text, ticked) -> {
            Integer time = null;
            if (!text.isEmpty()) {
                int parsed = Measure.parseTimeMs(text);
                if (parsed == Measure.INVALID) {
                    showMessage("\"" + text + "\" is not a time. Write seconds such as 12.34, "
                            + "or minutes and seconds such as 1:02.35.");
                    return;
                }
                time = parsed;
            }
            // Entering a time for someone marked "did not start" means they did run.
            String status = EventEntry.STATUS_DQ.equals(e.resultStatus) || time == null
                    ? e.resultStatus : EventEntry.STATUS_OK;
            Integer timeMs = time;
            change(() -> repo.saveTimedResult(currentUser, e.id, round.version, timeMs, status));
        });
    }

    // ----- Throws -----

    private void showAttemptDialog(EventEntry e) {
        int attempts = Math.max(1, rules.getInt("attempts", 3));
        int finalists = Math.max(0, rules.getInt("finalists", 0));
        int allowed = attempts + (finalists > 0 ? Math.max(0, rules.getInt("extra_attempts", 0)) : 0);
        // Attempts already taken can be corrected; the next one can be entered.
        int count = Math.min(e.attempts.size() + 1, allowed);
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            EventEntry.Attempt taken = e.attempt(i + 1);
            names[i] = "Attempt " + (i + 1) + (taken == null ? ""
                    : taken.foul ? " (foul)" : " (" + Measure.formatDistance(taken.distanceCm) + ")");
        }
        EntryDialog dialog = new EntryDialog(e.name);
        dialog.choice("Which attempt", names, count - 1);
        dialog.value("Distance in metres (12.45)", "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        dialog.tickBox();
        dialog.show((choice, text, foul) -> {
            Integer distance = null;
            if (!foul) {
                int parsed = Measure.parseDistanceCm(text);
                if (parsed == Measure.INVALID) {
                    showMessage("Enter the distance in metres, such as 12.45, or tick Foul.");
                    return;
                }
                distance = parsed;
            }
            Integer distanceCm = distance;
            change(() -> repo.saveAttempt(currentUser, e.id, round.version, choice + 1,
                    distanceCm, foul));
        });
    }

    private void showStatusDialog(EventEntry e) {
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(EventEntry.STATUS_LABELS, (dialog, which) -> change(() ->
                        repo.setEntryStatus(currentUser, e.id, round.version,
                                EventEntry.STATUSES[which])))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ----- Entrants -----

    private void showAddEntrantDialog() {
        // Approved registrations of the category that are not in this event yet.
        List<Registration> candidates = new ArrayList<>();
        try {
            for (Registration r : repo.getRegistrations(currentUser, round.categoryId)) {
                boolean entered = false;
                for (EventEntry e : entries) {
                    entered = entered || e.registrationId.equals(r.id);
                }
                if (r.isApproved() && !entered) {
                    candidates.add(r);
                }
            }
        } catch (ArenaException e) {
            showMessage(e.getMessage());
            return;
        }
        if (candidates.isEmpty()) {
            showMessage("Every approved registration of this category is already in this "
                    + "event. Approve more registrations first.");
            return;
        }
        String[] names = new String[candidates.size()];
        for (int i = 0; i < names.length; i++) {
            Registration r = candidates.get(i);
            names[i] = r.memberCount() == 0 && !r.branchCode.isEmpty()
                    ? r.name + " (" + r.branchCode + ")" : r.name;
        }

        EntryDialog dialog = new EntryDialog("Add an entrant");
        dialog.choice("Who", names, 0);
        dialog.value(round.isMeasured() ? "Throwing order" : "Lane",
                String.valueOf(firstFreeLane()), InputType.TYPE_CLASS_NUMBER);
        dialog.show((choice, text, ticked) -> {
            int lane;
            try {
                lane = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                lane = 0;
            }
            int chosenLane = lane;
            change(() -> repo.addEntry(currentUser, roundId, candidates.get(choice).id,
                    chosenLane));
        });
    }

    private int firstFreeLane() {
        for (int lane = 1; ; lane++) {
            boolean taken = false;
            for (EventEntry e : entries) {
                taken = taken || e.lane == lane;
            }
            if (!taken) {
                return lane;
            }
        }
    }

    // =====================================================================
    // Result history: staff only
    // =====================================================================

    private void showHistory() {
        boolean history = AccessControl.canViewScoreHistory(userIn(round.sportId));
        TextView tvAudit = findViewById(R.id.tvAudit);
        show(findViewById(R.id.tvAuditTitle), history);
        show(tvAudit, history);
        if (!history) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (ScoreAudit a : repo.getRoundAudit(currentUser, roundId)) {
            text.append(DateUtil.formatTimestamp(a.changedAt)).append("  ")
                    .append(a.changedByName).append("\n").append(a.newScore);
            if (!a.reason.isEmpty()) {
                text.append("\nReason: ").append(a.reason);
            }
            text.append("\n\n");
        }
        tvAudit.setText(text.length() == 0 ? "No changes recorded yet." : text.toString().trim());
    }

    private void setText(int viewId, String text) {
        ((TextView) findViewById(viewId)).setText(text);
    }

    // =====================================================================
    // A small pop-up form, built from dialog_entry.xml
    // =====================================================================

    private interface EntryResult {
        void onSave(int choice, String text, boolean ticked);
    }

    /** Shows a list, one value and a tick box; the parts that are not asked for are hidden. */
    private class EntryDialog {
        private final String title;
        private final View form;
        private final Spinner spinner;
        private final EditText value;
        private final CheckBox tick;

        EntryDialog(String title) {
            this.title = title;
            form = getLayoutInflater().inflate(R.layout.dialog_entry, null);
            spinner = form.findViewById(R.id.spEntryChoice);
            value = form.findViewById(R.id.etEntryValue);
            tick = form.findViewById(R.id.cbEntryFoul);
            form.findViewById(R.id.tvEntryChoiceLabel).setVisibility(View.GONE);
            spinner.setVisibility(View.GONE);
            tick.setVisibility(View.GONE);
        }

        void choice(String label, String[] options, int selected) {
            TextView tv = form.findViewById(R.id.tvEntryChoiceLabel);
            tv.setText(label);
            tv.setVisibility(View.VISIBLE);
            spinner.setVisibility(View.VISIBLE);
            setSpinnerItems(spinner, options);
            spinner.setSelection(selected);
        }

        void value(String label, String text, int inputType) {
            ((TextView) form.findViewById(R.id.tvEntryValueLabel)).setText(label);
            value.setInputType(inputType);
            value.setText(text);
        }

        void tickBox() {
            tick.setVisibility(View.VISIBLE);
        }

        void show(EntryResult result) {
            new AlertDialog.Builder(RoundDetailActivity.this)
                    .setTitle(title)
                    .setView(form)
                    .setPositiveButton("Save", (dialog, which) -> result.onSave(
                            Math.max(0, spinner.getSelectedItemPosition()), textOf(value),
                            tick.isChecked()))
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    // ----- Downloads -----

    /** The entrants and results of this heat or final as a file. */
    @Override
    protected String[] downloadNames() {
        return new String[]{"Results of this event (CSV)"};
    }

    @Override
    protected Download downloadAt(int position) {
        return exports -> exports.eventResults(roundId);
    }
}
