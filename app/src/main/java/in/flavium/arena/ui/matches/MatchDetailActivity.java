package in.flavium.arena.ui.matches;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import in.flavium.arena.ui.common.FormDialog;
import in.flavium.arena.ui.common.Ui;
import in.flavium.arena.scoring.MatchSummary;
import in.flavium.arena.R;
import in.flavium.arena.ui.common.BaseActivity;
import in.flavium.arena.auth.AccessControl;
import in.flavium.arena.model.AppSettings;
import in.flavium.arena.model.Category;
import in.flavium.arena.model.Match;
import in.flavium.arena.model.MatchEvent;
import in.flavium.arena.model.MatchPeriod;
import in.flavium.arena.model.Registration;
import in.flavium.arena.model.RuleSet;
import in.flavium.arena.model.SportTemplate;
import in.flavium.arena.scoring.LiveLink;
import in.flavium.arena.scoring.LiveScore;
import in.flavium.arena.scoring.SetRules;
import in.flavium.arena.scoring.SetScoring;
import in.flavium.arena.scoring.TennisGame;

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
 * checks every change again. Live scoring is done with + and − buttons: every tap is
 * saved at once (and so reaches other devices by itself); there is no Save button.
 * Who scores the match and when it was last changed are shown to organisers only.
 *
 * This class decides what is shown and what a tap does. The wording of a match's state
 * is in scoring.MatchSummary, the rules of sets are in scoring.SetScoring, and the
 * pop-up forms are ui.common.FormDialog.
 */
public class MatchDetailActivity extends BaseActivity {

    private String matchId;
    private Match match;
    private List<MatchPeriod> periods = new ArrayList<>();
    private RuleSet rules = new RuleSet();

    private EditText etReason;
    private Spinner spStatus, spPeriod;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_match_detail, "Match", true);
        matchId = getIntent().getStringExtra(EXTRA_MATCH_ID);

        etReason = findViewById(R.id.etReason);
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

        // One score each: + and − are saved at once; the period too, as soon as it is chosen.
        findViewById(R.id.btnPlusA).setOnClickListener(v -> bump(true, 1));
        findViewById(R.id.btnMinusA).setOnClickListener(v -> bump(true, -1));
        findViewById(R.id.btnPlusB).setOnClickListener(v -> bump(false, 1));
        findViewById(R.id.btnMinusB).setOnClickListener(v -> bump(false, -1));
        findViewById(R.id.btnScoreDetails).setOnClickListener(v -> showScoreDetails());
        findViewById(R.id.btnAddEvent).setOnClickListener(v -> showEventDialog());
        spPeriod.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Also called when the screen sets the current period: only a real change
                // is saved.
                if (match != null && position != match.currentPeriod) {
                    savePeriod(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

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
        refreshScreen();
    }

    /** Shows the current data. Runs when the screen opens and when shared data changes. */
    @Override
    protected void refreshScreen() {
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
            return MatchSummary.cricket(match, periods, rules);
        }
        if (uses(SportTemplate.ENGINE_RESULT)) {
            return MatchSummary.result(match);
        }
        StringBuilder caption = new StringBuilder();
        if (match.isUnderway() && match.currentPeriod > 0) {
            caption.append(MatchSummary.periodName(rules, match.currentPeriod));
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
        setText(R.id.tvMatchInfo, Ui.fixtureDetails(match.sportName + ", " + match.categoryName,
                match.round, match.date + " " + match.time, match.venue, match.assignedName,
                match.updatedAt, match.updatedByName,
                AccessControl.canViewScoreHistory(userIn(match.sportId))));
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

        // The big numbers belong to the game or set in play; + and − change that one only.
        MatchPeriod current = state.currentSet == 0 ? null : period(state.currentSet);
        int a = current == null ? 0 : current.scoreA;
        int b = current == null ? 0 : current.scoreB;
        setText(R.id.tvSetNameA, match.sideAShort());
        setText(R.id.tvSetNameB, match.sideBShort());
        setText(R.id.tvSetPointsA, String.valueOf(a));
        setText(R.id.tvSetPointsB, String.valueOf(b));

        TextView tvCurrent = findViewById(R.id.tvCurrentSet);
        if (state.currentSet == 0) {
            tvCurrent.setText("The match is decided.");
        } else if (!live) {
            tvCurrent.setText("Set the status to Live to score " + setRules.unit.toLowerCase()
                    + " " + state.currentSet + ".");
        } else {
            String text = setRules.unit + " " + state.currentSet;
            if (setRules.pointByPoint && current != null && current.ptA + current.ptB > 0) {
                text += "  ·  " + TennisGame.label(current.ptA, current.ptB);
            }
            // Say plainly when the target has been reached but the set is not won yet.
            int target = setRules.targetOf(state.currentSet);
            if (Math.max(a, b) >= target && setRules.winByOf(state.currentSet) > 1) {
                text += "  (must lead by " + setRules.winByOf(state.currentSet) + ")";
            }
            tvCurrent.setText(text);
        }

        boolean canAdd = live && state.currentSet != 0;
        enable(R.id.btnPointA, canAdd);
        enable(R.id.btnPointB, canAdd);
        // Taking a point back is for plain point counting; tennis games are corrected
        // through "Correct a set".
        show(findViewById(R.id.btnUndoA), !setRules.pointByPoint);
        show(findViewById(R.id.btnUndoB), !setRules.pointByPoint);
        enable(R.id.btnUndoA, canAdd && a > 0);
        enable(R.id.btnUndoB, canAdd && b > 0);
        ((Button) findViewById(R.id.btnEditSet)).setText("Correct a " + setRules.unit.toLowerCase());
    }

    private void showScoreControls() {
        setText(R.id.tvScoreLabelA, match.sideAShort());
        setText(R.id.tvScoreLabelB, match.sideBShort());
        int a = match.scoreA == null ? 0 : match.scoreA;
        int b = match.scoreB == null ? 0 : match.scoreB;
        setText(R.id.tvLiveScoreA, String.valueOf(a));
        setText(R.id.tvLiveScoreB, String.valueOf(b));
        boolean live = Match.STATUS_LIVE.equals(match.status);
        TextView tvHint = findViewById(R.id.tvLiveHint);
        show(tvHint, !live);
        tvHint.setText("Set the status to Live to score with + and −.");
        enable(R.id.btnPlusA, live);
        enable(R.id.btnPlusB, live);
        enable(R.id.btnMinusA, live && a > 0);
        enable(R.id.btnMinusB, live && b > 0);
        // Corrections, notes and the shoot-out, while a score may be recorded at all.
        show(findViewById(R.id.btnScoreDetails), !Match.STATUS_UPCOMING.equals(match.status)
                && !Match.STATUS_POSTPONED.equals(match.status));

        // The period list: halves or quarters, then extra time and the shoot-out.
        int count = rules.getInt("periods", 0);
        show(findViewById(R.id.tvPeriodLabel), count > 0);
        show(spPeriod, count > 0);
        if (count > 0) {
            String[] names = new String[count + 3];
            names[0] = "Not started";
            for (int i = 1; i <= count + 2; i++) {
                names[i] = MatchSummary.periodName(rules, i);
            }
            setSpinnerItems(spPeriod, names);
            spPeriod.setSelection(Math.min(match.currentPeriod, count + 2));
        }

        show(findViewById(R.id.btnAddEvent), rules.isYes("track_events"));
    }

    /** A shoot-out is recorded only where a level match has to be settled. */
    private boolean needsWinner() {
        return Category.FORMAT_KNOCKOUT.equals(match.categoryFormat)
                || RuleSet.NO.equals(rules.get("allow_draw", ""));
    }

    /** A score button that cannot be used now is also shown faded. */
    private void enable(int buttonId, boolean enabled) {
        View button = findViewById(buttonId);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
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
        FormDialog dialog = new FormDialog(this, "Correct a " + setRules.unit.toLowerCase());
        dialog.choice("Which " + setRules.unit.toLowerCase(), names, count - 1);
        dialog.numbers(labels, null);
        dialog.show(v -> change(() ->
                repo.saveSetScore(currentUser, matchId, match.version, v.choice + 1,
                        v.numbers[0], v.numbers[1], tiebreak ? v.numbers[2] : 0,
                        tiebreak ? v.numbers[3] : 0)));
    }

    // ----- One score each -----

    /**
     * One more, or one fewer, for a side, saved at once. A match without a score starts
     * at 0 - 0. A score never goes below 0. A shoot-out is kept only while the score is
     * still level.
     */
    private void bump(boolean sideA, int delta) {
        int[] score = LiveScore.tap(match.scoreA, match.scoreB, sideA, delta);
        if (score == null) {
            return;
        }
        saveScore(score[0], score[1], match.scoreNote, match.currentPeriod, match.penA,
                match.penB);
    }

    private void savePeriod(int period) {
        saveScore(match.scoreA, match.scoreB, match.scoreNote, period, match.penA, match.penB);
    }

    private void saveScore(Integer a, Integer b, String note, int period, Integer penA,
                           Integer penB) {
        boolean level = LiveScore.keepsShootOut(a, b);
        change(() -> repo.updateScore(currentUser, matchId, match.version, a, b, note,
                match.status, period, level ? penA : null, level ? penB : null));
    }

    /** Typing the score, a note and the shoot-out: for corrections, not for live scoring. */
    private void showScoreDetails() {
        boolean shootOut = needsWinner();
        int a = match.scoreA == null ? 0 : match.scoreA;
        int b = match.scoreB == null ? 0 : match.scoreB;
        FormDialog dialog = new FormDialog(this, "Score details");
        if (shootOut) {
            dialog.numbers(new String[]{match.sideAShort(), match.sideBShort(),
                            "Shoot-out: " + match.sideAShort(), "Shoot-out: " + match.sideBShort()},
                    new int[]{a, b, match.penA == null ? 0 : match.penA,
                            match.penB == null ? 0 : match.penB});
        } else {
            dialog.numbers(new String[]{match.sideAShort(), match.sideBShort()},
                    new int[]{a, b});
        }
        dialog.text("Note (optional)", match.scoreNote, InputType.TYPE_CLASS_TEXT);
        dialog.show(v -> {
            // A shoot-out of 0 - 0 means there was none.
            boolean pens = shootOut && v.numbers[2] + v.numbers[3] > 0;
            saveScore(v.numbers[0], v.numbers[1], v.text, match.currentPeriod,
                    pens ? v.numbers[2] : null, pens ? v.numbers[3] : null);
        });
    }

    private void showEventDialog() {
        FormDialog dialog = new FormDialog(this, "Record a match event");
        dialog.choice("Side", new String[]{match.sideAShort(), match.sideBShort()}, 0);
        dialog.secondChoice("Kind of event", MatchEvent.TYPES);
        dialog.numbers(new String[]{"Minute of the match"}, null);
        dialog.text("Player (optional)");
        dialog.show(v -> change(() ->
                repo.addEvent(currentUser, matchId, v.numbers[0], v.choice == 0 ? "A" : "B",
                        MatchEvent.TYPES[v.secondChoice], v.text)));
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
        FormDialog dialog = new FormDialog(this, number == 1 ? "First innings" : "Second innings");
        dialog.choice("Batting side", new String[]{match.sideAShort(), match.sideBShort()}, side);
        dialog.numbers(new String[]{"Runs", "Wickets lost", "Overs completed",
                "Balls of the over in progress (0 to 5)", "Extras (included in the runs)"}, values);
        dialog.show(v -> change(() ->
                repo.saveInnings(currentUser, matchId, match.version, number,
                        v.choice == 0 ? "A" : "B", v.numbers[0], v.numbers[1], v.numbers[2],
                        v.numbers[3], v.numbers[4])));
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
        tvAudit.setText(Ui.historyText(repo.getScoreAudit(currentUser, matchId)));
    }

}
