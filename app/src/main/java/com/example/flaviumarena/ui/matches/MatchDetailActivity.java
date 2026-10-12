package com.example.flaviumarena.ui.matches;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.AppSettings;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.MatchEvent;
import com.example.flaviumarena.model.MatchPeriod;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.model.RuleSet;
import com.example.flaviumarena.model.ScoreAudit;
import com.example.flaviumarena.model.SportTemplate;
import com.example.flaviumarena.scoring.LiveLink;
import com.example.flaviumarena.scoring.SetRules;
import com.example.flaviumarena.scoring.SetScoring;
import com.example.flaviumarena.scoring.TennisGame;
import com.example.flaviumarena.util.DateUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * One match.
 *
 * The top of the screen is the same for everyone: who is playing, the score, the
 * breakdown (sets, innings or events) and the details. How the score is shown and
 * entered depends on the scoring engine of the match's category:
 *
 *   SETS     points are added to the set in play; sets won are worked out from them
 *   SCORE    one score each, with the period in play, a shoot-out and match events
 *   RESULT   win, draw or loss
 *   CRICKET  innings totals, plus a button that opens the match on CricHeroes
 *
 * The controls appear only for someone who may score this match, and the repository
 * checks every change again.
 */
public class MatchDetailActivity extends BaseActivity {

    private String matchId;
    private Match match;
    private List<MatchPeriod> periods = new ArrayList<>();
    private RuleSet rules = new RuleSet();

    private EditText etScoreA, etScoreB, etNote, etReason, etPenA, etPenB;
    private Spinner spStatus, spPeriod;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_match_detail, "Match", true);
        matchId = getIntent().getStringExtra(EXTRA_MATCH_ID);

        etScoreA = findViewById(R.id.etScoreA);
        etScoreB = findViewById(R.id.etScoreB);
        etNote = findViewById(R.id.etNote);
        etReason = findViewById(R.id.etReason);
        etPenA = findViewById(R.id.etPenA);
        etPenB = findViewById(R.id.etPenB);
        spStatus = findViewById(R.id.spStatus);
        spPeriod = findViewById(R.id.spPeriod);
        setSpinnerItems(spStatus, Match.STATUS_LABELS);

        findViewById(R.id.btnSetStatus).setOnClickListener(v -> change(() ->
                repo.setMatchStatus(currentUser, matchId, match.version,
                        Match.STATUSES[spStatus.getSelectedItemPosition()])));

        // Sets
        findViewById(R.id.btnPointA).setOnClickListener(v -> addPoint(true));
        findViewById(R.id.btnPointB).setOnClickListener(v -> addPoint(false));
        findViewById(R.id.btnUndoA).setOnClickListener(v -> takeBack(true));
        findViewById(R.id.btnUndoB).setOnClickListener(v -> takeBack(false));
        findViewById(R.id.btnEditSet).setOnClickListener(v -> showSetDialog());

        // One score each
        findViewById(R.id.btnSaveScore).setOnClickListener(v -> saveScore());
        findViewById(R.id.btnAddEvent).setOnClickListener(v -> showEventDialog());

        // Win, draw, loss
        findViewById(R.id.btnWinA).setOnClickListener(v -> saveResult(1, 0));
        findViewById(R.id.btnDraw).setOnClickListener(v -> saveResult(1, 1));
        findViewById(R.id.btnWinB).setOnClickListener(v -> saveResult(0, 1));

        // Cricket
        findViewById(R.id.btnInnings1).setOnClickListener(v -> showInningsDialog(1));
        findViewById(R.id.btnInnings2).setOnClickListener(v -> showInningsDialog(2));
        findViewById(R.id.btnOpenLive).setOnClickListener(v -> openLiveScore());

        // Finalising and fixture management
        findViewById(R.id.btnFinalise).setOnClickListener(v ->
                showDialog("Finalise this result? Afterwards it can only be changed by "
                                + "reopening it with a recorded reason.", R.drawable.ic_check,
                        false, "Finalise", "Cancel", () -> change(() ->
                                repo.finaliseMatch(currentUser, matchId, match.version))));
        findViewById(R.id.btnReopen).setOnClickListener(v -> change(() ->
                repo.reopenMatch(currentUser, matchId, match.version, textOf(etReason))));
        findViewById(R.id.btnEditFixture).setOnClickListener(v -> {
            Intent intent = new Intent(this, FixtureEditActivity.class);
            intent.putExtra(EXTRA_MATCH_ID, matchId);
            startActivity(intent);
        });
        findViewById(R.id.btnDeleteFixture).setOnClickListener(v ->
                confirm("Delete this fixture and its score history?", () -> {
                    if (attempt(() -> repo.deleteFixture(currentUser, matchId))) {
                        toast("Fixture deleted");
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
     * change was refused, so after a conflict the other person's latest score is shown.
     */
    private void change(Runnable action) {
        attempt(action);
        refresh();
    }

    private boolean uses(String engine) {
        return match.usesEngine(engine);
    }

    /** Reads the match again and redraws the whole screen. */
    private void refresh() {
        match = repo.getMatch(matchId);
        if (match == null) {
            finish();
            return;
        }
        periods = repo.getPeriods(matchId);
        rules = match.rules();

        showScoreboard();
        showBreakdown();
        showEvents();
        showDetails();
        showControls();
        showHistory();
    }

    // =====================================================================
    // What everyone sees
    // =====================================================================

    private void showScoreboard() {
        // A team's name is its branch and year. An individual's branch is shown below.
        boolean individual = Category.TYPE_INDIVIDUAL.equals(match.categoryType);
        setText(R.id.tvTeamAName, individual ? match.teamAName + "\n"
                + Registration.branchLine(match.branchAName, match.yearA) : match.teamAName);
        setText(R.id.tvTeamBName, individual ? match.teamBName + "\n"
                + Registration.branchLine(match.branchBName, match.yearB) : match.teamBName);
        setText(R.id.tvStatus, match.statusLabel());
        setText(R.id.tvScore, match.scoreText());
        setText(R.id.tvScoreCaption, scoreCaption());

        // A completed match stays provisional until it has been finalised.
        boolean provisional = !match.isFinal && Match.STATUS_COMPLETED.equals(match.status);
        TextView tvNote = findViewById(R.id.tvStatusNote);
        show(tvNote, provisional);
        tvNote.setText("Provisional result. It becomes official when Faculty or an "
                + "authorised Core finalises it.");
    }

    /** One line under the big score that says what the numbers mean. */
    private String scoreCaption() {
        if (uses(SportTemplate.ENGINE_SETS)) {
            SetRules setRules = SetRules.from(rules);
            return setRules.unit + "s won (best of " + setRules.bestOf + ")";
        }
        if (uses(SportTemplate.ENGINE_CRICKET)) {
            return cricketSummary();
        }
        if (uses(SportTemplate.ENGINE_RESULT)) {
            if (!match.hasScore()) {
                return "No result yet";
            }
            int winner = match.winnerSide();
            return winner == 0 ? "Draw" : (winner == 1 ? match.sideAShort() : match.sideBShort())
                    + " won";
        }
        StringBuilder caption = new StringBuilder();
        if (match.isUnderway() && match.currentPeriod > 0) {
            caption.append(periodName(match.currentPeriod));
        }
        if (match.penA != null && match.penB != null) {
            caption.append(caption.length() > 0 ? "\n" : "").append("Penalty shoot-out: ")
                    .append(match.penA).append(" - ").append(match.penB);
        }
        if (!match.scoreNote.isEmpty()) {
            caption.append(caption.length() > 0 ? "\n" : "").append(match.scoreNote);
        }
        return caption.toString();
    }

    private String periodName(int number) {
        int count = rules.getInt("periods", 0);
        if (count > 0 && number == count + 1) {
            return "Extra time";
        }
        if (count > 0 && number == count + 2) {
            return "Shoot-out";
        }
        return rules.get("period_name", "Period") + " " + number
                + (count > 0 ? " of " + count : "");
    }

    /** Cricket result in words, worked out from the innings totals. */
    private String cricketSummary() {
        if (periods.isEmpty()) {
            return "Runs. No innings entered yet";
        }
        MatchPeriod first = periods.get(0);
        if (periods.size() == 1) {
            return "Runs. Target for the side batting second: " + (first.scoreA + 1);
        }
        MatchPeriod second = periods.get(1);
        if (!Match.STATUS_COMPLETED.equals(match.status)) {
            return "Runs. Chasing " + (first.scoreA + 1);
        }
        String battedFirst = "A".equals(first.side) ? match.sideAShort() : match.sideBShort();
        String battedSecond = "A".equals(second.side) ? match.sideAShort() : match.sideBShort();
        if (second.scoreA > first.scoreA) {
            int wicketsLeft = Math.max(1, rules.getInt(RuleSet.TEAM_MIN, 11) - 1) - second.wickets;
            return battedSecond + " won by " + wicketsLeft + " wickets";
        }
        if (second.scoreA < first.scoreA) {
            return battedFirst + " won by " + (first.scoreA - second.scoreA) + " runs";
        }
        return "Scores level";
    }

    /** The set-by-set or innings-by-innings rows. Sports with one score have none. */
    private void showBreakdown() {
        LinearLayout rows = findViewById(R.id.breakdownRows);
        rows.removeAllViews();
        TextView title = findViewById(R.id.tvBreakdownTitle);

        if (uses(SportTemplate.ENGINE_SETS)) {
            SetRules setRules = SetRules.from(rules);
            title.setText(setRules.unit + " by " + setRules.unit.toLowerCase());
            show(title, !periods.isEmpty());
            for (MatchPeriod p : periods) {
                int winner = SetScoring.winnerOfSet(setRules, p.number, p.scoreA, p.scoreB);
                String score = p.scoreA + " - " + p.scoreB;
                if (p.tbA + p.tbB > 0) {
                    score += " (" + p.tbA + "-" + p.tbB + ")";
                }
                String state;
                if (winner == SetScoring.SIDE_A) {
                    state = match.sideAShort();
                } else if (winner == SetScoring.SIDE_B) {
                    state = match.sideBShort();
                } else if (setRules.pointByPoint && p.ptA + p.ptB > 0) {
                    state = "In play: " + TennisGame.label(p.ptA, p.ptB);
                } else {
                    state = "In play";
                }
                addBreakdownRow(rows, setRules.unit + " " + p.number, score, state);
            }
        } else if (uses(SportTemplate.ENGINE_CRICKET)) {
            title.setText("Innings");
            show(title, !periods.isEmpty());
            for (MatchPeriod p : periods) {
                addBreakdownRow(rows, "A".equals(p.side) ? match.sideAShort() : match.sideBShort(),
                        p.scoreA + "/" + p.wickets, p.oversText() + " overs");
            }
        } else {
            show(title, false);
        }

        // Cricket: the link to the live scorecard on CricHeroes.
        boolean cricket = uses(SportTemplate.ENGINE_CRICKET);
        boolean hasLink = !match.externalUrl.isEmpty();
        show(findViewById(R.id.btnOpenLive), cricket && hasLink);
        TextView tvLiveNote = findViewById(R.id.tvLiveNote);
        show(tvLiveNote, cricket);
        tvLiveNote.setText(hasLink
                ? "Opens this match on CricHeroes. Scores there are not copied into this app."
                : "No CricHeroes link has been added to this fixture yet.");
    }

    private void addBreakdownRow(LinearLayout rows, String name, String score, String state) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_breakdown_row, rows, false);
        ((TextView) row.findViewById(R.id.tvBreakdownName)).setText(name);
        ((TextView) row.findViewById(R.id.tvBreakdownScore)).setText(score);
        ((TextView) row.findViewById(R.id.tvBreakdownState)).setText(state);
        rows.addView(row);
    }

    private void showEvents() {
        LinearLayout rows = findViewById(R.id.eventRows);
        rows.removeAllViews();
        List<MatchEvent> events = repo.getEvents(matchId);
        show(findViewById(R.id.tvEventsTitle), !events.isEmpty());
        boolean canDelete = AccessControl.canUpdateScore(userIn(match.sportId), match) && !match.isFinal;
        for (MatchEvent e : events) {
            String side = "A".equals(e.side) ? match.sideAShort() : match.sideBShort();
            View row = LayoutInflater.from(this).inflate(R.layout.item_breakdown_row, rows, false);
            ((TextView) row.findViewById(R.id.tvBreakdownName))
                    .setText(e.type + (e.player.isEmpty() ? "" : "\n" + e.player));
            ((TextView) row.findViewById(R.id.tvBreakdownScore))
                    .setText(e.minute > 0 ? e.minute + "'" : "");
            ((TextView) row.findViewById(R.id.tvBreakdownState)).setText(side);
            if (canDelete) {
                row.setOnClickListener(v -> confirm("Remove this event?", () -> change(() ->
                        repo.deleteEvent(currentUser, e.id))));
            }
            rows.addView(row);
        }
    }

    private void showDetails() {
        StringBuilder info = new StringBuilder();
        info.append(match.sportName).append(", ").append(match.categoryName).append("\n");
        info.append("Round: ").append(match.round).append("\n");
        info.append("When: ").append(match.date).append(" ").append(match.time).append("\n");
        info.append("Venue: ").append(match.venue.isEmpty() ? "to be announced" : match.venue);
        info.append("\nScorer: ")
                .append(match.assignedName.isEmpty() ? "not assigned" : match.assignedName);
        info.append("\nLast updated: ").append(DateUtil.formatTimestamp(match.updatedAt));
        if (!match.updatedByName.isEmpty()) {
            info.append(" by ").append(match.updatedByName);
        }
        setText(R.id.tvMatchInfo, info.toString());
    }

    // =====================================================================
    // Controls for whoever may score
    // =====================================================================

    private void showControls() {
        // Faculty always; a Core only when Faculty has allowed it.
        boolean canFinalise = AccessControl.canFinaliseResults(userIn(match.sportId),
                repo.getFlag(AppSettings.CORE_CAN_FINALISE));
        boolean canScore = AccessControl.canUpdateScore(userIn(match.sportId), match) && !match.isFinal;

        show(findViewById(R.id.scorePanel), canScore);
        show(findViewById(R.id.btnFinalise), canFinalise && !match.isFinal
                && Match.STATUS_COMPLETED.equals(match.status) && match.hasScore());
        show(findViewById(R.id.correctionPanel), canFinalise && match.isFinal);
        show(findViewById(R.id.corePanel), AccessControl.canManageFixtures(userIn(match.sportId)));
        etReason.setText("");
        if (!canScore) {
            return;
        }

        for (int i = 0; i < Match.STATUSES.length; i++) {
            if (Match.STATUSES[i].equals(match.status)) {
                spStatus.setSelection(i);
            }
        }

        boolean sets = uses(SportTemplate.ENGINE_SETS);
        boolean result = uses(SportTemplate.ENGINE_RESULT);
        boolean cricket = uses(SportTemplate.ENGINE_CRICKET);
        boolean score = uses(SportTemplate.ENGINE_SCORE);
        show(findViewById(R.id.panelSets), sets);
        show(findViewById(R.id.panelResult), result);
        show(findViewById(R.id.panelCricket), cricket);
        show(findViewById(R.id.panelScore), score);

        if (sets) {
            showSetControls();
        } else if (result) {
            ((Button) findViewById(R.id.btnWinA)).setText(match.sideAShort() + " won");
            ((Button) findViewById(R.id.btnWinB)).setText(match.sideBShort() + " won");
        } else if (score) {
            showScoreControls();
        }
    }

    private void showSetControls() {
        SetRules setRules = SetRules.from(rules);
        SetScoring.State state = setState();
        boolean live = Match.STATUS_LIVE.equals(match.status);

        TextView tvCurrent = findViewById(R.id.tvCurrentSet);
        if (state.currentSet == 0) {
            tvCurrent.setText("The match is decided.");
        } else if (!live) {
            tvCurrent.setText("Set the status to Live to score " + setRules.unit.toLowerCase()
                    + " " + state.currentSet + ".");
        } else {
            MatchPeriod current = period(state.currentSet);
            int a = current == null ? 0 : current.scoreA;
            int b = current == null ? 0 : current.scoreB;
            String text = setRules.unit + " " + state.currentSet + ": " + a + " - " + b;
            // Say plainly when the target has been reached but the set is not won yet.
            int target = setRules.targetOf(state.currentSet);
            if (Math.max(a, b) >= target && setRules.winByOf(state.currentSet) > 1) {
                text += "  (must lead by " + setRules.winByOf(state.currentSet) + ")";
            }
            tvCurrent.setText(text);
        }

        boolean canAdd = live && state.currentSet != 0;
        Button btnA = findViewById(R.id.btnPointA);
        Button btnB = findViewById(R.id.btnPointB);
        btnA.setText((setRules.pointByPoint ? "Point: " : "+1  ") + match.sideAShort());
        btnB.setText((setRules.pointByPoint ? "Point: " : "+1  ") + match.sideBShort());
        btnA.setEnabled(canAdd);
        btnB.setEnabled(canAdd);
        // Taking a point back is for plain point counting; tennis games are corrected
        // through "Correct a set".
        show(findViewById(R.id.btnUndoA), !setRules.pointByPoint);
        show(findViewById(R.id.btnUndoB), !setRules.pointByPoint);
        findViewById(R.id.btnUndoA).setEnabled(canAdd);
        findViewById(R.id.btnUndoB).setEnabled(canAdd);
        ((Button) findViewById(R.id.btnEditSet)).setText("Correct a " + setRules.unit.toLowerCase());
    }

    private void showScoreControls() {
        setText(R.id.tvScoreLabelA, match.sideAShort());
        setText(R.id.tvScoreLabelB, match.sideBShort());
        etScoreA.setText(match.scoreA == null ? "" : String.valueOf(match.scoreA));
        etScoreB.setText(match.scoreB == null ? "" : String.valueOf(match.scoreB));
        etNote.setText(match.scoreNote);
        etPenA.setText(match.penA == null ? "" : String.valueOf(match.penA));
        etPenB.setText(match.penB == null ? "" : String.valueOf(match.penB));

        // The period list: halves or quarters, then extra time and the shoot-out.
        int count = rules.getInt("periods", 0);
        show(findViewById(R.id.tvPeriodLabel), count > 0);
        show(spPeriod, count > 0);
        if (count > 0) {
            String[] names = new String[count + 3];
            names[0] = "Not started";
            for (int i = 1; i <= count + 2; i++) {
                names[i] = periodName(i);
            }
            setSpinnerItems(spPeriod, names);
            spPeriod.setSelection(Math.min(match.currentPeriod, count + 2));
        }

        // A shoot-out is offered only where a level match has to be settled.
        boolean needsWinner = Category.FORMAT_KNOCKOUT.equals(match.categoryFormat)
                || RuleSet.NO.equals(rules.get("allow_draw", ""));
        show(findViewById(R.id.panelPenalties), needsWinner);
        show(findViewById(R.id.btnAddEvent), rules.isYes("track_events"));
    }

    private SetScoring.State setState() {
        List<int[]> sets = new ArrayList<>();
        for (MatchPeriod p : periods) {
            sets.add(new int[]{p.scoreA, p.scoreB});
        }
        return SetScoring.evaluate(SetRules.from(rules), sets);
    }

    private MatchPeriod period(int number) {
        return number >= 1 && number <= periods.size() ? periods.get(number - 1) : null;
    }

    // ----- Sets -----

    /** One point to a side in the set that is being played. */
    private void addPoint(boolean toA) {
        SetRules setRules = SetRules.from(rules);
        if (setRules.pointByPoint) {
            // Lawn tennis: the repository works out the game, the set and the tie-break.
            change(() -> repo.scoreTennisPoint(currentUser, matchId, match.version,
                    toA ? "A" : "B"));
            return;
        }
        int number = setState().currentSet;
        MatchPeriod current = period(number);
        int a = (current == null ? 0 : current.scoreA) + (toA ? 1 : 0);
        int b = (current == null ? 0 : current.scoreB) + (toA ? 0 : 1);
        change(() -> repo.saveSetScore(currentUser, matchId, match.version, number, a, b, 0, 0));
    }

    /** Takes back one point that was entered by mistake in the set being played. */
    private void takeBack(boolean fromA) {
        int number = setState().currentSet;
        MatchPeriod current = period(number);
        if (current == null || (fromA ? current.scoreA : current.scoreB) == 0) {
            showMessage("There is no point to take back in this part of the match. To change "
                    + "an earlier one, use the correct button below.");
            return;
        }
        int a = current.scoreA - (fromA ? 1 : 0);
        int b = current.scoreB - (fromA ? 0 : 1);
        change(() -> repo.saveSetScore(currentUser, matchId, match.version, number, a, b, 0, 0));
    }

    /** Type the score of any set directly, to correct a mistake. */
    private void showSetDialog() {
        SetRules setRules = SetRules.from(rules);
        SetScoring.State state = setState();
        // Existing sets, plus the next one if the match is still open.
        int count = periods.size() + (state.currentSet > periods.size() ? 1 : 0);
        if (count == 0) {
            showMessage("Nothing has been scored yet.");
            return;
        }
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            names[i] = setRules.unit + " " + (i + 1);
        }
        boolean tiebreak = setRules.tiebreak;
        String[] labels = tiebreak
                ? new String[]{match.sideAShort(), match.sideBShort(),
                "Tie-break: " + match.sideAShort(), "Tie-break: " + match.sideBShort()}
                : new String[]{match.sideAShort(), match.sideBShort()};
        FormDialog dialog = new FormDialog("Correct a " + setRules.unit.toLowerCase());
        dialog.choice("Which " + setRules.unit.toLowerCase(), names, count - 1);
        dialog.numbers(labels, null);
        dialog.show((choice, choice2, numbers, text) -> change(() ->
                repo.saveSetScore(currentUser, matchId, match.version, choice + 1, numbers[0],
                        numbers[1], tiebreak ? numbers[2] : 0, tiebreak ? numbers[3] : 0)));
    }

    // ----- One score each -----

    /** Empty box means "no score yet". */
    private Integer numberIn(EditText field) {
        String text = textOf(field);
        return text.isEmpty() ? null : Integer.valueOf(numberOf(field, -1));
    }

    private void saveScore() {
        int period = spPeriod.getVisibility() == View.VISIBLE
                ? spPeriod.getSelectedItemPosition() : match.currentPeriod;
        boolean penalties = findViewById(R.id.panelPenalties).getVisibility() == View.VISIBLE;
        change(() -> repo.updateScore(currentUser, matchId, match.version, numberIn(etScoreA),
                numberIn(etScoreB), textOf(etNote), match.status, period,
                penalties ? numberIn(etPenA) : match.penA,
                penalties ? numberIn(etPenB) : match.penB));
    }

    private void showEventDialog() {
        FormDialog dialog = new FormDialog("Record a match event");
        dialog.choice("Side", new String[]{match.sideAShort(), match.sideBShort()}, 0);
        dialog.secondChoice("Kind of event", MatchEvent.TYPES);
        dialog.numbers(new String[]{"Minute of the match"}, null);
        dialog.text("Player (optional)");
        dialog.show((choice, choice2, numbers, text) -> change(() ->
                repo.addEvent(currentUser, matchId, numbers[0], choice == 0 ? "A" : "B",
                        MatchEvent.TYPES[choice2], text)));
    }

    // ----- Win, draw, loss -----

    private void saveResult(int scoreA, int scoreB) {
        change(() -> repo.updateScore(currentUser, matchId, match.version, scoreA, scoreB, "",
                Match.STATUS_COMPLETED));
    }

    // ----- Cricket -----

    private void showInningsDialog(int number) {
        MatchPeriod existing = period(number);
        // By default the sides bat in turn: A first, then B.
        int side = existing != null ? ("A".equals(existing.side) ? 0 : 1) : (number == 1 ? 0 : 1);
        int[] values = existing == null ? null : new int[]{existing.scoreA, existing.wickets,
                existing.balls / 6, existing.balls % 6, existing.extras};
        FormDialog dialog = new FormDialog(number == 1 ? "First innings" : "Second innings");
        dialog.choice("Batting side", new String[]{match.sideAShort(), match.sideBShort()}, side);
        dialog.numbers(new String[]{"Runs", "Wickets lost", "Overs completed",
                "Balls of the over in progress (0 to 5)", "Extras (included in the runs)"}, values);
        dialog.show((choice, choice2, numbers, text) -> change(() ->
                repo.saveInnings(currentUser, matchId, match.version, number,
                        choice == 0 ? "A" : "B", numbers[0], numbers[1], numbers[2], numbers[3],
                        numbers[4])));
    }

    /** Opens the match page on CricHeroes in its app or in the browser. */
    private void openLiveScore() {
        // The link was checked when it was saved; it is checked again before it is opened.
        if (match.externalUrl.isEmpty() || LiveLink.problemWith(match.externalUrl) != null) {
            showMessage("This fixture does not have a valid CricHeroes link.");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(match.externalUrl)));
        } catch (ActivityNotFoundException e) {
            showMessage("No app on this phone can open the link:\n" + match.externalUrl);
        }
    }

    // =====================================================================
    // Score history: staff only
    // =====================================================================

    private void showHistory() {
        boolean history = AccessControl.canViewScoreHistory(userIn(match.sportId));
        TextView tvAudit = findViewById(R.id.tvAudit);
        show(findViewById(R.id.tvAuditTitle), history);
        show(tvAudit, history);
        if (!history) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (ScoreAudit a : repo.getScoreAudit(currentUser, matchId)) {
            text.append(DateUtil.formatTimestamp(a.changedAt)).append("  ")
                    .append(a.changedByName).append("\n")
                    .append(a.action).append(": ").append(a.oldScore)
                    .append("  ->  ").append(a.newScore);
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
    // A small pop-up form, built from dialog_two_numbers.xml
    // =====================================================================

    private interface FormResult {
        void onSave(int choice, int secondChoice, int[] numbers, String text);
    }

    /** Shows up to two lists, five number boxes and one text box; unused rows are hidden. */
    private class FormDialog {
        private final String title;
        private final View form;
        private final EditText[] boxes;
        private int numberCount;

        FormDialog(String title) {
            this.title = title;
            form = getLayoutInflater().inflate(R.layout.dialog_two_numbers, null);
            boxes = new EditText[]{form.findViewById(R.id.etNumber1),
                    form.findViewById(R.id.etNumber2), form.findViewById(R.id.etNumber3),
                    form.findViewById(R.id.etNumber4), form.findViewById(R.id.etNumber5)};
            int[] hidden = {R.id.tvDialogChoiceLabel, R.id.spDialogChoice,
                    R.id.tvDialogChoice2Label, R.id.spDialogChoice2, R.id.tvNumber1Label,
                    R.id.etNumber1, R.id.tvNumber2Label, R.id.etNumber2, R.id.tvNumber3Label,
                    R.id.etNumber3, R.id.tvNumber4Label, R.id.etNumber4, R.id.tvNumber5Label,
                    R.id.etNumber5, R.id.tvDialogTextLabel, R.id.etDialogText};
            for (int id : hidden) {
                form.findViewById(id).setVisibility(View.GONE);
            }
        }

        void choice(String label, String[] options, int selected) {
            list(R.id.tvDialogChoiceLabel, R.id.spDialogChoice, label, options, selected);
        }

        void secondChoice(String label, String[] options) {
            list(R.id.tvDialogChoice2Label, R.id.spDialogChoice2, label, options, 0);
        }

        private void list(int labelId, int spinnerId, String label, String[] options,
                          int selected) {
            TextView tv = form.findViewById(labelId);
            Spinner spinner = form.findViewById(spinnerId);
            tv.setText(label);
            tv.setVisibility(View.VISIBLE);
            spinner.setVisibility(View.VISIBLE);
            setSpinnerItems(spinner, options);
            spinner.setSelection(selected);
        }

        void numbers(String[] labels, int[] values) {
            int[] labelIds = {R.id.tvNumber1Label, R.id.tvNumber2Label, R.id.tvNumber3Label,
                    R.id.tvNumber4Label, R.id.tvNumber5Label};
            numberCount = labels.length;
            for (int i = 0; i < labels.length; i++) {
                TextView tv = form.findViewById(labelIds[i]);
                tv.setText(labels[i]);
                tv.setVisibility(View.VISIBLE);
                boxes[i].setVisibility(View.VISIBLE);
                boxes[i].setText(values == null ? "" : String.valueOf(values[i]));
            }
        }

        void text(String label) {
            TextView tv = form.findViewById(R.id.tvDialogTextLabel);
            tv.setText(label);
            tv.setVisibility(View.VISIBLE);
            form.findViewById(R.id.etDialogText).setVisibility(View.VISIBLE);
        }

        void show(FormResult result) {
            new AlertDialog.Builder(MatchDetailActivity.this)
                    .setTitle(title)
                    .setView(form)
                    .setPositiveButton("Save", (dialog, which) -> {
                        int[] numbers = new int[5];
                        for (int i = 0; i < numberCount; i++) {
                            numbers[i] = numberOf(boxes[i], 0); // an empty box counts as 0
                        }
                        Spinner first = form.findViewById(R.id.spDialogChoice);
                        Spinner second = form.findViewById(R.id.spDialogChoice2);
                        EditText text = form.findViewById(R.id.etDialogText);
                        result.onSave(Math.max(0, first.getSelectedItemPosition()),
                                Math.max(0, second.getSelectedItemPosition()), numbers,
                                textOf(text));
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }
}
