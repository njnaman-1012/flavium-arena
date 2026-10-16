package com.example.flaviumarena.ui.events;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.example.flaviumarena.ui.common.FormDialog;
import com.example.flaviumarena.scoring.EventSummary;
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
 *
 * This class decides what is shown and what a tap does. The wording of results and
 * rules is in scoring.EventSummary, and the pop-up forms are ui.common.FormDialog.
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
        refreshScreen();
    }

    /** Shows the current data. Runs when the screen opens and when shared data changes. */
    @Override
    protected void refreshScreen() {
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
            text.append(text.length() > 0 ? "\n" : "").append(EventSummary.attemptList(e));
        }
        return text.toString();
    }

    /** Right-hand side of an entrant's card: the result that counts. */
    private String badgeOf(EventEntry e) {
        String badge = EventSummary.badge(round.isMeasured(), e);
        return e.qualified && e.hasNormalStatus() && !badge.isEmpty()
                ? badge + "\nQualified" : badge;
    }

    private void showDetails() {
        // Who scores it and when it last changed are organiser information.
        boolean organiser = AccessControl.canViewScoreHistory(userIn(round.sportId));
        StringBuilder info = new StringBuilder();
        info.append("When: ").append(round.date)
                .append(round.time.isEmpty() ? "" : " " + round.time);
        info.append("\nVenue: ").append(round.venue.isEmpty() ? "to be announced" : round.venue);
        if (organiser) {
            info.append("\nScorer: ")
                    .append(round.assignedName.isEmpty() ? "not assigned" : round.assignedName);
        }
        info.append("\n").append(EventSummary.rules(round));
        if (organiser) {
            info.append("\nLast updated: ").append(DateUtil.formatTimestamp(round.updatedAt));
            if (!round.updatedByName.isEmpty()) {
                info.append(" by ").append(round.updatedByName);
            }
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
        FormDialog dialog = new FormDialog(this, "Lane " + e.lane + ": " + e.name);
        dialog.text("Time (12.34 or 1:02.35)",
                e.timeMs == null ? "" : Measure.formatTime(e.timeMs), InputType.TYPE_CLASS_TEXT);
        dialog.show(v -> {
            String text = v.text;
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
        int allowed = EventSummary.attemptsAllowed(rules);
        // Attempts already taken can be corrected; the next one can be entered.
        int count = Math.min(e.attempts.size() + 1, allowed);
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            EventEntry.Attempt taken = e.attempt(i + 1);
            names[i] = "Attempt " + (i + 1)
                    + (taken == null ? "" : " (" + EventSummary.attempt(taken) + ")");
        }
        FormDialog dialog = new FormDialog(this, e.name);
        dialog.choice("Which attempt", names, count - 1);
        dialog.text("Distance in metres (12.45)", "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        dialog.tickBox("Foul: this attempt does not count");
        dialog.show(v -> {
            boolean foul = v.ticked;
            String text = v.text;
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
            change(() -> repo.saveAttempt(currentUser, e.id, round.version, v.choice + 1,
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

        FormDialog dialog = new FormDialog(this, "Add an entrant");
        dialog.choice("Who", names, 0);
        dialog.text(round.isMeasured() ? "Throwing order" : "Lane",
                String.valueOf(firstFreeLane()), InputType.TYPE_CLASS_NUMBER);
        dialog.show(v -> {
            String text = v.text;
            int lane;
            try {
                lane = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                lane = 0;
            }
            int chosenLane = lane;
            change(() -> repo.addEntry(currentUser, roundId, candidates.get(v.choice).id,
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
        tvAudit.setText(Ui.historyText(repo.getRoundAudit(currentUser, roundId)));
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
