package in.flavium.arena.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import in.flavium.arena.auth.AccessControl;
import in.flavium.arena.model.AppSettings;
import in.flavium.arena.model.Category;
import in.flavium.arena.model.Match;
import in.flavium.arena.model.MatchEvent;
import in.flavium.arena.model.MatchPeriod;
import in.flavium.arena.model.Registration;
import in.flavium.arena.model.ScoreAudit;
import in.flavium.arena.model.RuleSet;
import in.flavium.arena.model.SportTemplate;
import in.flavium.arena.model.User;
import in.flavium.arena.scoring.LiveLink;
import in.flavium.arena.scoring.SetRules;
import in.flavium.arena.scoring.SetScoring;
import in.flavium.arena.scoring.TennisGame;
import in.flavium.arena.util.DateUtil;
import java.util.ArrayList;
import java.util.List;
import static in.flavium.arena.data.Sql.*;

/**
 * Fixtures between two sides and everything that happens to them: scheduling, live
 * scoring for each kind of sport, finalising, reopening and the score history.
 * SqliteArenaRepository hands these calls on to this class.
 */
final class MatchStore extends Store {

    MatchStore(SqliteArenaRepository repo, DbHelper helper) {
        super(repo, helper);
    }

    // =====================================================================
    // Fixtures and scores
    // =====================================================================

    /** Reads a match together with the names needed to display it. */
    private static final String MATCH_SELECT =
            "SELECT m.*, ra.name AS team_a_name, rb.name AS team_b_name, "
                    + "ra.year AS year_a, rb.year AS year_b, "
                    + "ra.logo_path AS logo_a, rb.logo_path AS logo_b, "
                    + "ba.name AS branch_a_name, ba.code AS branch_a_code, "
                    + "bb.name AS branch_b_name, bb.code AS branch_b_code, "
                    + "c.type AS category_type, c.engine AS category_engine, "
                    + "c.rules AS category_rules, "
                    + "c.name AS category_name, c.format AS category_format, "
                    + "s.name AS sport_name, s.id AS sport_id, ua.name AS assigned_name, "
                    + "ub.name AS updated_by_name "
                    + "FROM matches m "
                    + "JOIN registrations ra ON ra.id = m.team_a_id "
                    + "JOIN registrations rb ON rb.id = m.team_b_id "
                    + "LEFT JOIN branches ba ON ba.id = ra.branch_id "
                    + "LEFT JOIN branches bb ON bb.id = rb.branch_id "
                    + "JOIN categories c ON c.id = m.category_id "
                    + "JOIN sports s ON s.id = c.sport_id "
                    + "LEFT JOIN users ua ON ua.id = m.assigned_to "
                    + "LEFT JOIN users ub ON ub.id = m.updated_by ";

    private static final String MATCH_ORDER = " ORDER BY m.match_date, m.match_time, m.round";

    public List<Match> getMatches(String categoryId) {
        Cursor c;
        if (categoryId == null) {
            c = query(MATCH_SELECT + MATCH_ORDER);
        } else {
            c = query(MATCH_SELECT + "WHERE m.category_id = ?" + MATCH_ORDER, categoryId);
        }
        List<Match> list = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                list.add(readMatch(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    /** Every match in which a team or participant of this branch plays. */
    public List<Match> getMatchesOfBranch(String branchId) {
        List<Match> list = new ArrayList<>();
        Cursor c = query(MATCH_SELECT + "WHERE ra.branch_id = ? OR rb.branch_id = ?"
                + MATCH_ORDER, branchId, branchId);
        try {
            while (c.moveToNext()) {
                list.add(readMatch(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    public Match getMatch(String id) {
        if (id == null) {
            return null;
        }
        Cursor c = query(MATCH_SELECT + "WHERE m.id = ?", id);
        try {
            return c.moveToFirst() ? readMatch(c) : null;
        } finally {
            c.close();
        }
    }

    public String saveFixture(User actorArg, Match match) {
        User actor = repo.actorIn(actorArg,
                match.id == null ? repo.sportOfCategory(match.categoryId) : repo.sportOfMatch(match.id));
        AccessControl.require(AccessControl.canManageFixtures(actor), actor,
                "create or edit fixtures");

        // An existing fixture stays in its category, whatever the screen sent.
        Match storedFixture = match.id == null ? null : getMatch(match.id);
        if (storedFixture != null) {
            match.categoryId = storedFixture.categoryId;
        }
        Category category = repo.getCategory(match.categoryId);
        if (category == null) {
            throw new ValidationException("The category of this fixture no longer exists.");
        }
        if (match.teamAId == null || match.teamBId == null) {
            throw new ValidationException("Choose both sides of the fixture.");
        }
        if (match.teamAId.equals(match.teamBId)) {
            throw new ValidationException("A team cannot play against itself.");
        }
        checkSide(match.teamAId, category);
        checkSide(match.teamBId, category);

        String date = clean(match.date);
        String time = clean(match.time);
        String round = clean(match.round);
        if (!DateUtil.isValidDate(date)) {
            throw new ValidationException("Choose the match date.");
        }
        if (!time.isEmpty() && !DateUtil.isValidTime(time)) {
            throw new ValidationException("The match time must look like 16:30.");
        }
        if (round.isEmpty()) {
            throw new ValidationException("Round or stage is required.");
        }
        if (match.assignedTo != null) {
            User assignee = repo.getUser(match.assignedTo);
            if (assignee == null || !AccessControl.isAsooCore(assignee)) {
                throw new ValidationException("A match can only be assigned to an Asoo Core.");
            }
        }

        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("team_a_id", match.teamAId);
        cv.put("team_b_id", match.teamBId);
        cv.put("match_date", date);
        cv.put("match_time", time);
        cv.put("venue", clean(match.venue));

        // The CricHeroes link: optional, cricket only, and it must be a CricHeroes address.
        String link = clean(match.externalUrl);
        if (!link.isEmpty()) {
            if (!SportTemplate.ENGINE_CRICKET.equals(category.engine)) {
                throw new ValidationException("A live-score link can only be added to a "
                        + "cricket fixture.");
            }
            String problem = LiveLink.problemWith(link);
            if (problem != null) {
                throw new ValidationException(problem);
            }
        }
        cv.put("external_url", link);
        cv.put("round", round);
        if (match.assignedTo == null) {
            cv.putNull("assigned_to");
        } else {
            cv.put("assigned_to", match.assignedTo);
        }
        cv.put("updated_at", System.currentTimeMillis());
        cv.put("updated_by", actor.id);

        if (match.id == null) {
            String id = newId();
            cv.put("id", id);
            cv.put("category_id", category.id);
            cv.put("status", Match.STATUS_UPCOMING);
            cv.put("is_final", 0);
            cv.put("version", 1);
            db.insertOrThrow("matches", null, cv);
            return id;
        }

        Match existing = requireMatch(match.id);
        boolean sidesChanged = !existing.teamAId.equals(match.teamAId)
                || !existing.teamBId.equals(match.teamBId);
        if (sidesChanged && existing.hasScore()) {
            throw new ValidationException("This match already has a score, so its teams "
                    + "cannot be changed.");
        }
        cv.put("version", match.version + 1);
        int rows = db.update("matches", cv, "id = ? AND version = ?",
                new String[]{match.id, String.valueOf(match.version)});
        if (rows == 0) {
            throw new ConflictException("Someone else changed this match while you were "
                    + "editing it. Your changes were not saved. Open it again and retry.");
        }
        return match.id;
    }

    /** A side of a fixture must be an approved registration of the same category. */
    private void checkSide(String registrationId, Category category) {
        Cursor c = query(RegistrationStore.REG_SELECT + "WHERE r.id = ?", registrationId);
        try {
            if (!c.moveToFirst()) {
                throw new ValidationException("One of the chosen sides no longer exists.");
            }
            Registration r = readRegistration(c);
            if (!category.id.equals(r.categoryId)) {
                throw new ValidationException(r.name + " is not registered in this category.");
            }
            if (!r.isApproved()) {
                throw new ValidationException(r.name + " is not approved yet.");
            }
        } finally {
            c.close();
        }
    }

    public void deleteFixture(User actorArg, String id) {
        User actor = repo.actorIn(actorArg, repo.sportOfMatch(id));
        AccessControl.require(AccessControl.canManageFixtures(actor), actor, "delete fixtures");
        // The score history of the match is removed with it (ON DELETE CASCADE).
        helper.getWritableDatabase().delete("matches", "id = ?", new String[]{id});
    }

    // ----- Who may score, and when -----

    /**
     * Common checks before any score is written: the actor may score this match, the
     * result is not final, and the match has actually started.
     */
    private void requireScoring(User actor, Match match) {
        AccessControl.require(AccessControl.canUpdateScore(actor, match), actor,
                "update the score of this match");
        if (match.isFinal) {
            throw new ValidationException("This result is final. It has to be reopened with "
                    + "a reason before the score can change.");
        }
        if (!Match.STATUS_LIVE.equals(match.status)
                && !Match.STATUS_COMPLETED.equals(match.status)) {
            throw new ValidationException("Scores can only be entered while the match is "
                    + "Live. Its status is " + match.status + ": start or resume it first.");
        }
    }

    private void requireEngine(Match match, String engine, String what) {
        if (!match.usesEngine(engine)) {
            throw new ValidationException("This category is not scored " + what + ".");
        }
    }

    // ----- Status -----

    /** Start, pause, resume, postpone or complete a match without touching its score. */
    public void setMatchStatus(User actorArg, String matchId, int expectedVersion,
                               String status) {
        User actor = repo.actorIn(actorArg, repo.sportOfMatch(matchId));
        Match match = requireMatch(matchId);
        AccessControl.require(AccessControl.canUpdateScore(actor, match), actor,
                "change the status of this match");
        if (match.isFinal) {
            throw new ValidationException("This result is final, so its status cannot change.");
        }
        if (!Match.isValidStatus(status)) {
            throw new ValidationException("Choose a valid match status.");
        }
        if (Match.STATUS_COMPLETED.equals(status)) {
            checkCanComplete(match);
        }
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        writeMatch(match, expectedVersion, cv, actor, ScoreAudit.ACTION_STATUS,
                describe(match.scoreA, match.scoreB, status), "");
    }

    /** A match can be marked completed only when it has a result. */
    private void checkCanComplete(Match match) {
        if (!match.hasScore()) {
            throw new ValidationException("Enter the score before marking the match as "
                    + "completed.");
        }
        if (match.usesEngine(SportTemplate.ENGINE_SETS)
                && setState(match, getPeriods(match.id)).winner == SetScoring.NOBODY) {
            throw new ValidationException("This match is not decided yet: neither side has "
                    + "won enough " + SetRules.from(match.rules()).unit.toLowerCase() + "s.");
        }
    }

    // ----- One score for each side: football, basketball; and win/draw/loss: chess -----

    public void updateScore(User actorArg, String matchId, int expectedVersion,
                            Integer scoreA, Integer scoreB, String note, String status) {
        Match current = requireMatch(matchId);
        updateScore(actorArg, matchId, expectedVersion, scoreA, scoreB, note, status,
                current.currentPeriod, current.penA, current.penB);
    }

    public void updateScore(User actorArg, String matchId, int expectedVersion,
                            Integer scoreA, Integer scoreB, String note, String status,
                            int currentPeriod, Integer penA, Integer penB) {
        User actor = repo.actorIn(actorArg, repo.sportOfMatch(matchId));
        Match match = requireMatch(matchId);
        AccessControl.require(AccessControl.canUpdateScore(actor, match), actor,
                "update the score of this match");

        // An ordinary update must never overwrite a final result.
        if (match.isFinal) {
            throw new ValidationException("This result is final. Use \"Correct final result\" "
                    + "and give a reason.");
        }
        boolean result = match.usesEngine(SportTemplate.ENGINE_RESULT);
        if (!result && !match.usesEngine(SportTemplate.ENGINE_SCORE)) {
            throw new ValidationException("This category has its own way of scoring. Use the "
                    + "scoring controls shown for it.");
        }
        if (!Match.isValidStatus(status)) {
            throw new ValidationException("Choose a valid match status.");
        }
        checkScores(scoreA, scoreB, Match.STATUS_COMPLETED.equals(status));
        if (scoreA != null && (Match.STATUS_UPCOMING.equals(status)
                || Match.STATUS_POSTPONED.equals(status))) {
            throw new ValidationException("A match that is " + status.toLowerCase()
                    + " cannot have a score. Set it to Live first.");
        }
        if (result && scoreA != null && !isResultScore(scoreA, scoreB)) {
            throw new ValidationException("Choose a result: a win for one side, or a draw.");
        }

        RuleSet rules = match.rules();
        int periods = rules.getInt("periods", 0);
        if (currentPeriod < 0 || (periods > 0 && currentPeriod > periods + 2)) {
            throw new ValidationException("That period does not exist in this match.");
        }
        if ((penA == null) != (penB == null)) {
            throw new ValidationException("Enter the penalty score of both sides.");
        }
        if (penA != null) {
            if (penA < 0 || penB < 0) {
                throw new ValidationException("Penalty scores cannot be negative.");
            }
            if (scoreA == null || !scoreA.equals(scoreB)) {
                throw new ValidationException("A penalty shoot-out is only recorded when "
                        + "the score is level.");
            }
        }

        ContentValues cv = new ContentValues();
        putScore(cv, scoreA, scoreB);
        cv.put("score_note", clean(note));
        cv.put("status", status);
        cv.put("current_period", currentPeriod);
        if (penA == null) {
            cv.putNull("pen_a");
            cv.putNull("pen_b");
        } else {
            cv.put("pen_a", penA);
            cv.put("pen_b", penB);
        }
        String newScore = describe(scoreA, scoreB, status)
                + (penA == null ? "" : ", penalties " + penA + " - " + penB);
        writeMatch(match, expectedVersion, cv, actor, ScoreAudit.ACTION_SCORE_UPDATE, newScore, "");
    }

    /** Win/draw/loss results are stored as 1-0, 0-1 and 1-1. */
    private static boolean isResultScore(int a, int b) {
        return (a == 1 && b == 0) || (a == 0 && b == 1) || (a == 1 && b == 1);
    }

    // ----- Sets, games, frames, pulls -----

    public List<MatchPeriod> getPeriods(String matchId) {
        List<MatchPeriod> list = new ArrayList<>();
        Cursor c = query("SELECT * FROM match_periods WHERE match_id = ? ORDER BY number",
                matchId);
        try {
            while (c.moveToNext()) {
                MatchPeriod p = new MatchPeriod();
                p.id = text(c, "id");
                p.matchId = text(c, "match_id");
                p.number = number(c, "number");
                p.side = text(c, "side");
                p.scoreA = number(c, "score_a");
                p.scoreB = number(c, "score_b");
                p.wickets = number(c, "wickets");
                p.balls = number(c, "balls");
                p.extras = number(c, "extras");
                p.tbA = number(c, "tb_a");
                p.tbB = number(c, "tb_b");
                p.ptA = number(c, "pt_a");
                p.ptB = number(c, "pt_b");
                list.add(p);
            }
        } finally {
            c.close();
        }
        return list;
    }

    private static SetScoring.State setState(Match match, List<MatchPeriod> periods) {
        List<int[]> sets = new ArrayList<>();
        for (MatchPeriod p : periods) {
            sets.add(new int[]{p.scoreA, p.scoreB});
        }
        return SetScoring.evaluate(SetRules.from(match.rules()), sets);
    }

    /**
     * Saves the score of one set. Used for every point (the screen sends the new total of
     * the set) and for correcting an earlier set. The whole match is then recalculated:
     * sets won by each side, and "Completed" as soon as one side has won enough sets.
     */
    public void saveSetScore(User actorArg, String matchId, int expectedVersion, int setNumber,
                             int scoreA, int scoreB, int tieBreakA, int tieBreakB) {
        User actor = repo.actorIn(actorArg, repo.sportOfMatch(matchId));
        Match match = requireMatch(matchId);
        requireScoring(actor, match);
        requireEngine(match, SportTemplate.ENGINE_SETS, "in sets");

        List<MatchPeriod> periods = getPeriods(matchId);
        if (setNumber < 1 || setNumber > periods.size() + 1) {
            throw new ValidationException("Score the parts of the match in order.");
        }
        MatchPeriod set = setNumber <= periods.size() ? periods.get(setNumber - 1)
                : newPeriod(matchId, setNumber);
        if (setNumber > periods.size()) {
            periods.add(set);
        }
        set.scoreA = scoreA;
        set.scoreB = scoreB;
        set.tbA = tieBreakA;
        set.tbB = tieBreakB;
        // Editing the totals directly ends any game that was being counted point by point.
        set.ptA = 0;
        set.ptB = 0;
        storeSets(match, expectedVersion, actor, periods, set);
    }

    /**
     * Lawn tennis, point by point. One call is one point won by side "A" or "B". The
     * point is added to the game in play; a won game is added to the set; at the
     * tie-break score the points count towards the tie-break instead.
     */
    public void scoreTennisPoint(User actorArg, String matchId, int expectedVersion,
                                 String side) {
        User actor = repo.actorIn(actorArg, repo.sportOfMatch(matchId));
        Match match = requireMatch(matchId);
        requireScoring(actor, match);
        requireEngine(match, SportTemplate.ENGINE_SETS, "in sets");
        SetRules rules = SetRules.from(match.rules());
        if (!rules.pointByPoint) {
            throw new ValidationException("This category is not scored point by point.");
        }
        boolean toA = "A".equals(side);
        if (!toA && !"B".equals(side)) {
            throw new ValidationException("Choose which side won the point.");
        }

        List<MatchPeriod> periods = getPeriods(matchId);
        SetScoring.State state = setState(match, periods);
        if (state.error != null) {
            throw new ValidationException(state.error);
        }
        if (state.currentSet == 0) {
            throw new ValidationException("This match is already decided.");
        }
        MatchPeriod set;
        if (state.currentSet <= periods.size()) {
            set = periods.get(state.currentSet - 1);
        } else {
            set = newPeriod(matchId, state.currentSet);
            periods.add(set);
        }

        int n = set.number;
        if (rules.isDeciding(n) && rules.decidingMatchTiebreak) {
            // The deciding "set" is a single tie-break: every point counts directly.
            if (toA) {
                set.scoreA++;
            } else {
                set.scoreB++;
            }
        } else if (rules.tiebreakIn(n) && set.scoreA == rules.tiebreakAt
                && set.scoreB == rules.tiebreakAt) {
            // Tie-break game at, for example, 6-6.
            if (toA) {
                set.tbA++;
            } else {
                set.tbB++;
            }
            int won = TennisGame.tiebreakWinner(set.tbA, set.tbB, rules.tiebreakPoints);
            if (won == SetScoring.SIDE_A) {
                set.scoreA++;
            } else if (won == SetScoring.SIDE_B) {
                set.scoreB++;
            }
        } else {
            // An ordinary game: 0, 15, 30, 40, deuce, advantage.
            if (toA) {
                set.ptA++;
            } else {
                set.ptB++;
            }
            int won = TennisGame.winner(set.ptA, set.ptB, rules.noAd);
            if (won != SetScoring.NOBODY) {
                if (won == SetScoring.SIDE_A) {
                    set.scoreA++;
                } else {
                    set.scoreB++;
                }
                set.ptA = 0;
                set.ptB = 0;
            }
        }
        storeSets(match, expectedVersion, actor, periods, set);
    }

    private static MatchPeriod newPeriod(String matchId, int number) {
        MatchPeriod p = new MatchPeriod();
        p.matchId = matchId;
        p.number = number;
        return p;
    }

    /**
     * Validates the complete list of sets, then saves the changed set and the new match
     * totals together. Nothing is written when the list is not consistent.
     */
    private void storeSets(Match match, int expectedVersion, User actor,
                           List<MatchPeriod> periods, MatchPeriod changed) {
        SetRules rules = SetRules.from(match.rules());
        SetScoring.State state = setState(match, periods);
        if (state.error != null) {
            throw new ValidationException(state.error);
        }
        // A 7-6 set needs the tie-break score that produced it.
        int high = Math.max(changed.scoreA, changed.scoreB);
        int low = Math.min(changed.scoreA, changed.scoreB);
        boolean endedByTiebreak = rules.tiebreakIn(changed.number)
                && high == rules.tiebreakAt + 1 && low == rules.tiebreakAt;
        if (endedByTiebreak) {
            int won = TennisGame.tiebreakWinner(changed.tbA, changed.tbB, rules.tiebreakPoints);
            int expected = changed.scoreA > changed.scoreB ? SetScoring.SIDE_A : SetScoring.SIDE_B;
            if (won != expected) {
                throw new ValidationException("Enter the tie-break score that decided this "
                        + rules.unit.toLowerCase() + ", for example 7-5.");
            }
        } else if (!(rules.tiebreakIn(changed.number) && high == rules.tiebreakAt
                && low == rules.tiebreakAt)) {
            // Tie-break points only exist while a tie-break is being played.
            changed.tbA = 0;
            changed.tbB = 0;
        }

        // A decided match becomes Completed; a correction that undecides it goes back to Live.
        String status = state.winner != SetScoring.NOBODY ? Match.STATUS_COMPLETED
                : Match.STATUS_LIVE;

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues row = new ContentValues();
            row.put("score_a", changed.scoreA);
            row.put("score_b", changed.scoreB);
            row.put("tb_a", changed.tbA);
            row.put("tb_b", changed.tbB);
            row.put("pt_a", changed.ptA);
            row.put("pt_b", changed.ptB);
            if (changed.id == null) {
                row.put("id", newId());
                row.put("match_id", match.id);
                row.put("number", changed.number);
                db.insertOrThrow("match_periods", null, row);
            } else {
                db.update("match_periods", row, "id = ?", new String[]{changed.id});
            }

            ContentValues cv = new ContentValues();
            cv.put("score_a", state.setsA);
            cv.put("score_b", state.setsB);
            cv.put("status", status);
            writeMatch(match, expectedVersion, cv, actor, ScoreAudit.ACTION_SCORE_UPDATE,
                    rules.unit + " " + changed.number + ": " + changed.scoreA + " - "
                            + changed.scoreB + "; " + describe(state.setsA, state.setsB, status),
                    "");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ----- Cricket innings -----

    /**
     * Saves the summary of one innings. Ball-by-ball scoring is done in CricHeroes; this
     * records the totals so that results and tables work inside FLAVIUM Arena.
     */
    public void saveInnings(User actorArg, String matchId, int expectedVersion, int number,
                            String side, int runs, int wickets, int overs, int ballsInOver,
                            int extras) {
        User actor = repo.actorIn(actorArg, repo.sportOfMatch(matchId));
        Match match = requireMatch(matchId);
        requireScoring(actor, match);
        requireEngine(match, SportTemplate.ENGINE_CRICKET, "by innings");

        RuleSet rules = match.rules();
        int maxInnings = Math.max(1, rules.getInt("innings_per_side", 1)) * 2;
        int maxOvers = Math.max(1, rules.getInt("overs", 50));
        int maxWickets = Math.max(1, rules.getInt(RuleSet.TEAM_MIN, 11) - 1);
        if (number < 1 || number > maxInnings) {
            throw new ValidationException("This match has " + maxInnings + " innings.");
        }
        if (!"A".equals(side) && !"B".equals(side)) {
            throw new ValidationException("Choose which side batted.");
        }
        if (runs < 0 || extras < 0 || wickets < 0 || overs < 0 || ballsInOver < 0) {
            throw new ValidationException("Innings figures cannot be negative.");
        }
        if (wickets > maxWickets) {
            throw new ValidationException("At most " + maxWickets + " wickets can fall.");
        }
        if (ballsInOver > 5) {
            throw new ValidationException("An over has six balls, so the balls part is 0 to 5.");
        }
        int balls = overs * 6 + ballsInOver;
        if (balls > maxOvers * 6) {
            throw new ValidationException("An innings is limited to " + maxOvers + " overs.");
        }
        if (extras > runs) {
            throw new ValidationException("Extras are part of the total, so they cannot be "
                    + "more than the runs.");
        }

        List<MatchPeriod> periods = getPeriods(matchId);
        if (number > periods.size() + 1) {
            throw new ValidationException("Enter the innings in order.");
        }
        // The two sides bat alternately.
        if (number > 1 && periods.get(number - 2).side.equals(side)) {
            throw new ValidationException("The same side cannot bat in two innings in a row.");
        }
        MatchPeriod innings = number <= periods.size() ? periods.get(number - 1)
                : newPeriod(matchId, number);
        if (number > periods.size()) {
            periods.add(innings);
        }
        innings.side = side;
        innings.scoreA = runs;
        innings.wickets = wickets;
        innings.balls = balls;
        innings.extras = extras;

        int runsA = 0;
        int runsB = 0;
        for (MatchPeriod p : periods) {
            if ("A".equals(p.side)) {
                runsA += p.scoreA;
            } else {
                runsB += p.scoreA;
            }
        }

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues row = new ContentValues();
            row.put("side", side);
            row.put("score_a", runs);
            row.put("wickets", wickets);
            row.put("balls", balls);
            row.put("extras", extras);
            if (innings.id == null) {
                row.put("id", newId());
                row.put("match_id", matchId);
                row.put("number", number);
                db.insertOrThrow("match_periods", null, row);
            } else {
                db.update("match_periods", row, "id = ?", new String[]{innings.id});
            }
            ContentValues cv = new ContentValues();
            cv.put("score_a", runsA);
            cv.put("score_b", runsB);
            writeMatch(match, expectedVersion, cv, actor, ScoreAudit.ACTION_SCORE_UPDATE,
                    "Innings " + number + " (side " + side + "): " + runs + "/" + wickets
                            + " in " + innings.oversText() + " overs; "
                            + describe(runsA, runsB, match.status), "");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ----- Match events -----

    public List<MatchEvent> getEvents(String matchId) {
        List<MatchEvent> list = new ArrayList<>();
        Cursor c = query("SELECT * FROM match_events WHERE match_id = ? "
                + "ORDER BY minute, created_at", matchId);
        try {
            while (c.moveToNext()) {
                MatchEvent e = new MatchEvent();
                e.id = text(c, "id");
                e.matchId = text(c, "match_id");
                e.minute = number(c, "minute");
                e.side = text(c, "side");
                e.type = text(c, "type");
                e.player = text(c, "player");
                e.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
                list.add(e);
            }
        } finally {
            c.close();
        }
        return list;
    }

    public String addEvent(User actorArg, String matchId, int minute, String side, String type,
                           String player) {
        User actor = repo.actorIn(actorArg, repo.sportOfMatch(matchId));
        Match match = requireMatch(matchId);
        requireScoring(actor, match);
        if (!"A".equals(side) && !"B".equals(side)) {
            throw new ValidationException("Choose the side the event belongs to.");
        }
        if (clean(type).isEmpty()) {
            throw new ValidationException("Choose the kind of event.");
        }
        if (minute < 0 || minute > 300) {
            throw new ValidationException("Enter the minute of the match (0 to 300).");
        }
        String id = newId();
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("match_id", matchId);
        cv.put("minute", minute);
        cv.put("side", side);
        cv.put("type", clean(type));
        cv.put("player", clean(player));
        cv.put("created_by", actor.id);
        cv.put("created_at", System.currentTimeMillis());
        helper.getWritableDatabase().insertOrThrow("match_events", null, cv);
        return id;
    }

    public void deleteEvent(User actorArg, String eventId) {
        User actor = repo.actorIn(actorArg, repo.sportOfMatchEvent(eventId));
        String matchId = null;
        Cursor c = query("SELECT match_id FROM match_events WHERE id = ?", eventId);
        try {
            if (c.moveToFirst()) {
                matchId = c.getString(0);
            }
        } finally {
            c.close();
        }
        if (matchId == null) {
            throw new ValidationException("This event no longer exists.");
        }
        requireScoring(actor, requireMatch(matchId));
        helper.getWritableDatabase().delete("match_events", "id = ?", new String[]{eventId});
    }

    // ----- Finalising, reopening and correcting -----

    public void finaliseMatch(User actorArg, String matchId, int expectedVersion) {
        User actor = repo.actorIn(actorArg, repo.sportOfMatch(matchId));
        AccessControl.require(AccessControl.canFinaliseResults(actor,
                repo.getFlag(AppSettings.CORE_CAN_FINALISE)), actor, "finalise results");

        Match match = requireMatch(matchId);
        if (match.isFinal) {
            throw new ValidationException("This result is already final.");
        }
        if (!Match.STATUS_COMPLETED.equals(match.status) || !match.hasScore()) {
            throw new ValidationException("Only a completed match with a score can be "
                    + "finalised.");
        }
        checkCanComplete(match);
        checkHasWinnerIfNeeded(match, match.scoreA, match.scoreB);

        ContentValues cv = new ContentValues();
        cv.put("is_final", 1);
        writeMatch(match, expectedVersion, cv, actor, ScoreAudit.ACTION_FINALISED,
                match.scoreText() + " (FINAL)", "");
    }

    /**
     * Takes a final result back to "completed, not final" so that it can be corrected
     * with the normal scoring controls and finalised again. A reason is required and is
     * kept in the score history, so a final result is never changed silently.
     */
    public void reopenMatch(User actorArg, String matchId, int expectedVersion, String reason) {
        User actor = repo.actorIn(actorArg, repo.sportOfMatch(matchId));
        AccessControl.require(AccessControl.canFinaliseResults(actor,
                repo.getFlag(AppSettings.CORE_CAN_FINALISE)), actor, "reopen final results");

        Match match = requireMatch(matchId);
        if (!match.isFinal) {
            throw new ValidationException("This result is not final, so it does not need "
                    + "reopening.");
        }
        if (clean(reason).isEmpty()) {
            throw new ValidationException("Give a reason for reopening a final result.");
        }
        ContentValues cv = new ContentValues();
        cv.put("is_final", 0);
        writeMatch(match, expectedVersion, cv, actor, ScoreAudit.ACTION_REOPENED,
                describe(match.scoreA, match.scoreB, Match.STATUS_COMPLETED), clean(reason));
    }

    public void correctFinalResult(User actorArg, String matchId, int expectedVersion,
                                   Integer scoreA, Integer scoreB, String note, String reason) {
        User actor = repo.actorIn(actorArg, repo.sportOfMatch(matchId));
        AccessControl.require(AccessControl.canFinaliseResults(actor,
                repo.getFlag(AppSettings.CORE_CAN_FINALISE)), actor, "correct final results");

        Match match = requireMatch(matchId);
        if (!match.isFinal) {
            throw new ValidationException("This result is not final yet. Update the score "
                    + "normally.");
        }
        boolean result = match.usesEngine(SportTemplate.ENGINE_RESULT);
        if (!result && !match.usesEngine(SportTemplate.ENGINE_SCORE)) {
            throw new ValidationException("Reopen this result with a reason, correct it with "
                    + "the scoring controls, and finalise it again.");
        }
        if (clean(reason).isEmpty()) {
            throw new ValidationException("Give a reason for correcting a final result.");
        }
        checkScores(scoreA, scoreB, true);
        if (result && !isResultScore(scoreA, scoreB)) {
            throw new ValidationException("Choose a result: a win for one side, or a draw.");
        }
        // A corrected score makes any recorded shoot-out meaningless unless still level.
        boolean keepPenalties = scoreA.equals(scoreB) && match.penA != null;
        if (!keepPenalties) {
            match.penA = null;
            match.penB = null;
        }
        checkHasWinnerIfNeeded(match, scoreA, scoreB);

        ContentValues cv = new ContentValues();
        putScore(cv, scoreA, scoreB);
        cv.put("score_note", clean(note));
        if (!keepPenalties) {
            cv.putNull("pen_a");
            cv.putNull("pen_b");
        }
        writeMatch(match, expectedVersion, cv, actor, ScoreAudit.ACTION_CORRECTION,
                scoreA + " - " + scoreB + " (FINAL)", clean(reason));
    }

    /**
     * A knockout match, and any category whose rules say a match may not end in a draw,
     * needs a winner. A level score is accepted only with a decisive penalty shoot-out.
     */
    private static void checkHasWinnerIfNeeded(Match match, Integer scoreA, Integer scoreB) {
        boolean drawForbidden = Category.FORMAT_KNOCKOUT.equals(match.categoryFormat)
                || RuleSet.NO.equals(match.rules().get("allow_draw", ""));
        if (!drawForbidden || !scoreA.equals(scoreB)) {
            return;
        }
        if (match.penA != null && match.penB != null && !match.penA.equals(match.penB)) {
            return;
        }
        throw new ValidationException("This match needs a winner, so the final score cannot "
                + "be a draw. Record the deciding shoot-out or correct the score.");
    }

    /**
     * Saves a change to a match and records it in the score history, as one transaction.
     * The update only succeeds when the row still has the version the screen loaded.
     * If another organiser saved first, nothing is written and a ConflictException is thrown.
     */
    private void writeMatch(Match before, int expectedVersion, ContentValues cv, User actor,
                            String action, String newScore, String reason) {
        long now = System.currentTimeMillis();
        cv.put("version", expectedVersion + 1);
        cv.put("updated_at", now);
        cv.put("updated_by", actor.id);

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            int rows = db.update("matches", cv, "id = ? AND version = ?",
                    new String[]{before.id, String.valueOf(expectedVersion)});
            if (rows == 0) {
                throw new ConflictException("Someone else updated this match a moment ago. "
                        + "Your change was not saved. Check the latest score and try again.");
            }
            ContentValues audit = new ContentValues();
            audit.put("id", newId());
            audit.put("match_id", before.id);
            audit.put("changed_by", actor.id);
            audit.put("changed_at", now);
            audit.put("action", action);
            audit.put("old_score", describe(before.scoreA, before.scoreB, before.statusLabel()));
            audit.put("new_score", newScore);
            audit.put("reason", reason);
            db.insertOrThrow("score_audit", null, audit);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void checkScores(Integer scoreA, Integer scoreB, boolean required) {
        if ((scoreA == null) != (scoreB == null)) {
            throw new ValidationException("Enter the score of both sides.");
        }
        if (scoreA == null) {
            if (required) {
                throw new ValidationException("Enter the score before marking the match "
                        + "as completed.");
            }
            return;
        }
        if (scoreA < 0 || scoreB < 0) {
            throw new ValidationException("Scores cannot be negative.");
        }
    }

    private static void putScore(ContentValues cv, Integer scoreA, Integer scoreB) {
        if (scoreA == null || scoreB == null) {
            cv.putNull("score_a");
            cv.putNull("score_b");
        } else {
            cv.put("score_a", scoreA);
            cv.put("score_b", scoreB);
        }
    }

    private static String describe(Integer scoreA, Integer scoreB, String status) {
        String score = scoreA == null || scoreB == null ? "no score" : scoreA + " - " + scoreB;
        return score + " (" + status + ")";
    }

    private Match requireMatch(String id) {
        Match match = getMatch(id);
        if (match == null) {
            throw new ValidationException("This match no longer exists.");
        }
        return match;
    }

    public List<ScoreAudit> getScoreAudit(User actorArg, String matchId) {
        User actor = repo.actorIn(actorArg, repo.sportOfMatch(matchId));
        AccessControl.require(AccessControl.canViewScoreHistory(actor), actor,
                "view the score history");

        List<ScoreAudit> list = new ArrayList<>();
        Cursor c = query("SELECT a.*, u.name AS changed_by_name FROM score_audit a "
                + "LEFT JOIN users u ON u.id = a.changed_by "
                + "WHERE a.match_id = ? ORDER BY a.changed_at DESC, a.rowid DESC", matchId);
        try {
            while (c.moveToNext()) {
                ScoreAudit a = new ScoreAudit();
                a.id = text(c, "id");
                a.matchId = text(c, "match_id");
                a.changedByName = text(c, "changed_by_name");
                a.changedAt = c.getLong(c.getColumnIndexOrThrow("changed_at"));
                a.action = text(c, "action");
                a.oldScore = text(c, "old_score");
                a.newScore = text(c, "new_score");
                a.reason = text(c, "reason");
                list.add(a);
            }
        } finally {
            c.close();
        }
        return list;
    }
}
