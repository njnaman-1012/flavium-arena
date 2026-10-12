package com.example.flaviumarena.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.example.flaviumarena.scoring.EventSummary;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.AppSettings;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.EventEntry;
import com.example.flaviumarena.model.EventRound;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.model.ScoreAudit;
import com.example.flaviumarena.model.RuleSet;
import com.example.flaviumarena.model.SportTemplate;
import com.example.flaviumarena.model.User;
import com.example.flaviumarena.scoring.EventRanking;
import com.example.flaviumarena.scoring.Measure;
import com.example.flaviumarena.util.DateUtil;
import java.util.ArrayList;
import java.util.List;
import static com.example.flaviumarena.data.Sql.*;

/**
 * Heats and finals of races, relays, swimming and throwing events: scheduling, entrants,
 * times and attempts, positions, qualifiers and the result history.
 * SqliteArenaRepository hands these calls on to this class.
 */
final class EventStore extends Store {

    EventStore(SqliteArenaRepository repo, DbHelper helper) {
        super(repo, helper);
    }

    // =====================================================================
    // Heats and finals: races, relays, swimming, discus, shot put
    // =====================================================================

    private static final String ROUND_SELECT =
            "SELECT r.*, c.name AS category_name, c.engine AS category_engine, "
                    + "c.rules AS category_rules, s.name AS sport_name, s.id AS sport_id, "
                    + "ua.name AS assigned_name, ub.name AS updated_by_name, "
                    + "(SELECT COUNT(*) FROM event_entries e WHERE e.round_id = r.id) "
                    + "AS entry_count "
                    + "FROM event_rounds r "
                    + "JOIN categories c ON c.id = r.category_id "
                    + "JOIN sports s ON s.id = c.sport_id "
                    + "LEFT JOIN users ua ON ua.id = r.assigned_to "
                    + "LEFT JOIN users ub ON ub.id = r.updated_by ";

    public List<EventRound> getRounds(String categoryId) {
        List<EventRound> list = new ArrayList<>();
        // Heats first, in number order, then the final. Without a category: every
        // heat and final of the festival, by date.
        Cursor c = categoryId == null
                ? query(ROUND_SELECT + "ORDER BY r.event_date, r.event_time, c.name, "
                + "r.stage = 'FINAL', r.heat_number")
                : query(ROUND_SELECT + "WHERE r.category_id = ? "
                + "ORDER BY r.stage = 'FINAL', r.heat_number", categoryId);
        try {
            while (c.moveToNext()) {
                list.add(readRound(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    public EventRound getRound(String id) {
        if (id == null) {
            return null;
        }
        Cursor c = query(ROUND_SELECT + "WHERE r.id = ?", id);
        try {
            return c.moveToFirst() ? readRound(c) : null;
        } finally {
            c.close();
        }
    }

    private static EventRound readRound(Cursor c) {
        EventRound r = new EventRound();
        r.id = text(c, "id");
        r.categoryId = text(c, "category_id");
        r.stage = text(c, "stage");
        r.heatNumber = number(c, "heat_number");
        r.date = text(c, "event_date");
        r.time = text(c, "event_time");
        r.venue = text(c, "venue");
        r.status = text(c, "status");
        r.isFinal = number(c, "is_final") == 1;
        r.assignedTo = textOrNull(c, "assigned_to");
        r.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        r.updatedBy = textOrNull(c, "updated_by");
        r.version = number(c, "version");
        r.categoryName = text(c, "category_name");
        r.categoryEngine = text(c, "category_engine");
        r.categoryRules = text(c, "category_rules");
        r.sportName = text(c, "sport_name");
        r.sportId = text(c, "sport_id");
        r.assignedName = text(c, "assigned_name");
        r.updatedByName = text(c, "updated_by_name");
        r.entryCount = number(c, "entry_count");
        return r;
    }

    private EventRound requireRound(String id) {
        EventRound round = getRound(id);
        if (round == null) {
            throw new ValidationException("This heat or final no longer exists.");
        }
        return round;
    }

    public String saveRound(User actorArg, EventRound round) {
        User actor = repo.actorIn(actorArg,
                round.id == null ? repo.sportOfCategory(round.categoryId) : repo.sportOfRound(round.id));
        AccessControl.require(AccessControl.canManageFixtures(actor), actor,
                "create or edit heats and finals");

        // An existing event stays in its category, whatever the screen sent.
        EventRound storedRound = round.id == null ? null : getRound(round.id);
        if (storedRound != null) {
            round.categoryId = storedRound.categoryId;
        }
        Category category = repo.getCategory(round.categoryId);
        if (category == null) {
            throw new ValidationException("The category no longer exists.");
        }
        boolean timed = SportTemplate.ENGINE_TIMED.equals(category.engine);
        if (!timed && !SportTemplate.ENGINE_MEASURED.equals(category.engine)) {
            throw new ValidationException("Heats and finals are for races and throwing "
                    + "events. This category uses fixtures between two sides.");
        }
        boolean heat = EventRound.STAGE_HEAT.equals(round.stage);
        if (!heat && !EventRound.STAGE_FINAL.equals(round.stage)) {
            throw new ValidationException("Choose Heat or Final.");
        }
        if (heat && !(timed && category.ruleSet().isYes("heats"))) {
            throw new ValidationException("This category has no heats. Create its "
                    + "competition as the Final.");
        }
        int heatNumber = heat ? round.heatNumber : 0;
        if (heat && heatNumber < 1) {
            throw new ValidationException("Give the heat a number, starting from 1.");
        }
        String date = clean(round.date);
        String time = clean(round.time);
        if (!DateUtil.isValidDate(date)) {
            throw new ValidationException("Choose the date.");
        }
        if (!time.isEmpty() && !DateUtil.isValidTime(time)) {
            throw new ValidationException("The time must look like 16:30.");
        }
        if (round.assignedTo != null) {
            User assignee = repo.getUser(round.assignedTo);
            if (assignee == null || !AccessControl.isAsooCore(assignee)) {
                throw new ValidationException("An event can only be assigned to an Asoo Core.");
            }
        }

        SQLiteDatabase db = helper.getWritableDatabase();
        boolean isNew = round.id == null;
        String id = isNew ? newId() : round.id;
        if (count(db, "event_rounds", "category_id = ? AND stage = ? AND heat_number = ? "
                + "AND id <> ?", category.id, round.stage, String.valueOf(heatNumber), id) > 0) {
            throw new ValidationException(heat ? "Heat " + heatNumber + " already exists."
                    : "This category already has a final.");
        }

        ContentValues cv = new ContentValues();
        cv.put("stage", round.stage);
        cv.put("heat_number", heatNumber);
        cv.put("event_date", date);
        cv.put("event_time", time);
        cv.put("venue", clean(round.venue));
        if (round.assignedTo == null) {
            cv.putNull("assigned_to");
        } else {
            cv.put("assigned_to", round.assignedTo);
        }
        cv.put("updated_at", System.currentTimeMillis());
        cv.put("updated_by", actor.id);
        if (isNew) {
            cv.put("id", id);
            cv.put("category_id", category.id);
            cv.put("status", Match.STATUS_UPCOMING);
            cv.put("is_final", 0);
            cv.put("version", 1);
            db.insertOrThrow("event_rounds", null, cv);
            return id;
        }
        EventRound stored = requireRound(id);
        if (stored.isFinal) {
            throw new ValidationException("These results are official. Reopen them before "
                    + "changing the event.");
        }
        cv.put("version", round.version + 1);
        if (db.update("event_rounds", cv, "id = ? AND version = ?",
                new String[]{id, String.valueOf(round.version)}) == 0) {
            throw new ConflictException("Someone else changed this event while you were "
                    + "editing it. Open it again and retry.");
        }
        return id;
    }

    public void deleteRound(User actorArg, String id) {
        User actor = repo.actorIn(actorArg, repo.sportOfRound(id));
        AccessControl.require(AccessControl.canManageFixtures(actor), actor,
                "delete heats and finals");
        // Entrants, attempts and history of the event are removed with it.
        helper.getWritableDatabase().delete("event_rounds", "id = ?", new String[]{id});
    }

    // ----- Entrants -----

    public List<EventEntry> getEntries(String roundId) {
        List<EventEntry> list = new ArrayList<>();
        // Placed entrants first in order of position, then the rest by lane.
        Cursor c = query("SELECT e.*, r.name AS reg_name, r.year, r.logo_path, r.members, "
                + "r.branch_id AS reg_branch_id, "
                + "b.name AS branch_name, b.code AS branch_code "
                + "FROM event_entries e "
                + "JOIN registrations r ON r.id = e.registration_id "
                + "LEFT JOIN branches b ON b.id = r.branch_id "
                + "WHERE e.round_id = ? "
                + "ORDER BY e.position IS NULL, e.position, e.lane", roundId);
        try {
            while (c.moveToNext()) {
                EventEntry e = new EventEntry();
                e.id = text(c, "id");
                e.roundId = text(c, "round_id");
                e.registrationId = text(c, "registration_id");
                e.lane = number(c, "lane");
                e.timeMs = numberOrNull(c, "time_ms");
                e.resultStatus = text(c, "result_status");
                e.position = numberOrNull(c, "position");
                e.qualified = number(c, "qualified") == 1;
                e.bestCm = numberOrNull(c, "best_cm");
                e.name = text(c, "reg_name");
                e.year = number(c, "year");
                e.logoPath = text(c, "logo_path");
                e.team = !text(c, "members").isEmpty();
                e.branchName = text(c, "branch_name");
                e.branchCode = text(c, "branch_code");
                e.branchId = textOrNull(c, "reg_branch_id");
                list.add(e);
            }
        } finally {
            c.close();
        }
        for (EventEntry e : list) {
            Cursor a = query("SELECT * FROM event_attempts WHERE entry_id = ? ORDER BY number",
                    e.id);
            try {
                while (a.moveToNext()) {
                    EventEntry.Attempt attempt = new EventEntry.Attempt();
                    attempt.number = number(a, "number");
                    attempt.distanceCm = numberOrNull(a, "distance_cm");
                    attempt.foul = number(a, "foul") == 1;
                    e.attempts.add(attempt);
                }
            } finally {
                a.close();
            }
        }
        return list;
    }

    private static EventEntry findEntry(List<EventEntry> entries, String entryId) {
        for (EventEntry e : entries) {
            if (e.id.equals(entryId)) {
                return e;
            }
        }
        throw new ValidationException("This entrant is no longer in the event.");
    }

    /** The heat or final an entrant belongs to. */
    private EventRound roundOfEntry(String entryId) {
        String roundId = null;
        Cursor c = query("SELECT round_id FROM event_entries WHERE id = ?", entryId);
        try {
            if (c.moveToFirst()) {
                roundId = c.getString(0);
            }
        } finally {
            c.close();
        }
        if (roundId == null) {
            throw new ValidationException("This entrant is no longer in the event.");
        }
        return requireRound(roundId);
    }

    public String addEntry(User actorArg, String roundId, String registrationId, int lane) {
        User actor = repo.actorIn(actorArg, repo.sportOfRound(roundId));
        AccessControl.require(AccessControl.canManageFixtures(actor), actor,
                "add entrants to an event");
        EventRound round = requireRound(roundId);
        if (round.isFinal) {
            throw new ValidationException("These results are official. Reopen them first.");
        }
        Registration entrant = repo.rawRegistration(registrationId);
        if (entrant == null || !round.categoryId.equals(entrant.categoryId)) {
            throw new ValidationException("This entrant is not registered in this category.");
        }
        if (!entrant.isApproved()) {
            throw new ValidationException(entrant.name + " is not approved yet.");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        if (count(db, "event_entries", "round_id = ? AND registration_id = ?", roundId,
                registrationId) > 0) {
            throw new ValidationException(entrant.name + " is already in this event.");
        }

        List<EventEntry> entries = getEntries(roundId);
        if (round.isMeasured()) {
            // Throwing order: next free number unless one was given.
            if (lane <= 0) {
                lane = entries.size() + 1;
            }
        } else {
            int lanes = Math.max(1, round.rules().getInt("lanes", 8));
            if (entries.size() >= lanes) {
                throw new ValidationException("This race is full: there are " + lanes
                        + " lanes.");
            }
            if (lane < 1 || lane > lanes) {
                throw new ValidationException("Choose a lane from 1 to " + lanes + ".");
            }
        }
        for (EventEntry e : entries) {
            if (e.lane == lane) {
                throw new ValidationException((round.isMeasured() ? "Position " : "Lane ")
                        + lane + " is already taken by " + e.name + ".");
            }
        }

        String id = newId();
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("round_id", roundId);
        cv.put("registration_id", registrationId);
        cv.put("lane", lane);
        db.insertOrThrow("event_entries", null, cv);
        return id;
    }

    public void removeEntry(User actorArg, String entryId) {
        User actor = repo.actorIn(actorArg, repo.sportOfEntry(entryId));
        AccessControl.require(AccessControl.canManageFixtures(actor), actor,
                "remove entrants from an event");
        EventRound round = roundOfEntry(entryId);
        if (round.isFinal) {
            throw new ValidationException("These results are official. Reopen them first.");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("event_entries", "id = ?", new String[]{entryId});
            storeRanking(db, round);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ----- Status and results -----

    /** Common checks before a result is written, the same as for a match. */
    private void requireRoundScoring(User actor, EventRound round) {
        AccessControl.require(AccessControl.canScore(actor, round.assignedTo, round.isFinal),
                actor, "enter results for this event");
        if (round.isFinal) {
            throw new ValidationException("These results are official. They have to be "
                    + "reopened with a reason before they can change.");
        }
        if (!Match.STATUS_LIVE.equals(round.status)
                && !Match.STATUS_COMPLETED.equals(round.status)) {
            throw new ValidationException("Results can only be entered while the event is "
                    + "Live. Its status is " + round.status + ": start or resume it first.");
        }
    }

    public void setRoundStatus(User actorArg, String roundId, int expectedVersion,
                               String status) {
        User actor = repo.actorIn(actorArg, repo.sportOfRound(roundId));
        EventRound round = requireRound(roundId);
        AccessControl.require(AccessControl.canScore(actor, round.assignedTo, round.isFinal),
                actor, "change the status of this event");
        if (round.isFinal) {
            throw new ValidationException("These results are official, so the status cannot "
                    + "change.");
        }
        if (!Match.isValidStatus(status)) {
            throw new ValidationException("Choose a valid status.");
        }
        if (Match.STATUS_COMPLETED.equals(status)) {
            // Every entrant needs a result or a reason for not having one.
            int missing = 0;
            List<EventEntry> entries = getEntries(roundId);
            for (EventEntry e : entries) {
                boolean hasResult = round.isMeasured() ? !e.attempts.isEmpty() : e.timeMs != null;
                if (!hasResult && e.hasNormalStatus()) {
                    missing++;
                }
            }
            if (entries.isEmpty()) {
                throw new ValidationException("This event has no entrants.");
            }
            if (missing > 0) {
                throw new ValidationException(missing + " entrant(s) have no result yet. "
                        + "Enter a result, or mark them as did not start, did not finish or "
                        + "disqualified.");
            }
        }
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        writeRound(round, expectedVersion, cv, actor, ScoreAudit.ACTION_STATUS,
                "Status: " + status, "");
    }

    public void saveTimedResult(User actorArg, String entryId, int expectedVersion,
                                Integer timeMs, String resultStatus) {
        User actor = repo.actorIn(actorArg, repo.sportOfEntry(entryId));
        EventRound round = roundOfEntry(entryId);
        requireRoundScoring(actor, round);
        if (round.isMeasured()) {
            throw new ValidationException("This event is measured, not timed.");
        }
        String status = checkResultStatus(resultStatus);
        if (timeMs != null && timeMs <= 0) {
            throw new ValidationException("Enter the time, for example 12.34 or 1:02.35.");
        }
        // Someone who did not start or finish has no time.
        if (EventEntry.STATUS_DNS.equals(status) || EventEntry.STATUS_DNF.equals(status)) {
            timeMs = null;
        }
        EventEntry entry = findEntry(getEntries(round.id), entryId);

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            if (timeMs == null) {
                cv.putNull("time_ms");
            } else {
                cv.put("time_ms", timeMs);
            }
            cv.put("result_status", status);
            db.update("event_entries", cv, "id = ?", new String[]{entryId});
            storeRanking(db, round);
            writeRound(round, expectedVersion, new ContentValues(), actor,
                    ScoreAudit.ACTION_SCORE_UPDATE,
                    "Lane " + entry.lane + " " + entry.name + ": "
                            + (timeMs == null ? "no time" : Measure.formatTime(timeMs))
                            + (status.isEmpty() ? "" : " " + status), "");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void saveAttempt(User actorArg, String entryId, int expectedVersion, int number,
                            Integer distanceCm, boolean foul) {
        User actor = repo.actorIn(actorArg, repo.sportOfEntry(entryId));
        EventRound round = roundOfEntry(entryId);
        requireRoundScoring(actor, round);
        if (!round.isMeasured()) {
            throw new ValidationException("This event is timed, not measured.");
        }
        RuleSet rules = round.rules();
        int attempts = EventSummary.attempts(rules);
        int finalistCount = EventSummary.finalists(rules);
        int extra = EventSummary.extraAttempts(rules);
        boolean secondBest = rules.get("tie_break", "").startsWith("Second-best");

        List<EventEntry> entries = getEntries(round.id);
        EventEntry entry = findEntry(entries, entryId);
        if (number < 1 || number > attempts + extra) {
            throw new ValidationException("This event allows attempts 1 to "
                    + (attempts + extra) + ".");
        }
        // Attempts are taken in order; an existing one may be corrected.
        int taken = entry.attempts.size();
        if (number > taken + 1) {
            throw new ValidationException("Enter attempt " + (taken + 1) + " first.");
        }
        if (number > attempts
                && !EventRanking.finalists(entries, attempts, finalistCount, secondBest)
                .contains(entry)) {
            throw new ValidationException("Only the best " + finalistCount + " after "
                    + attempts + " attempts take the extra attempts.");
        }
        if (foul) {
            distanceCm = null;
        } else if (distanceCm == null || distanceCm <= 0) {
            throw new ValidationException("Enter the measured distance, for example 12.45, "
                    + "or mark the attempt as a foul.");
        }

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            if (distanceCm == null) {
                cv.putNull("distance_cm");
            } else {
                cv.put("distance_cm", distanceCm);
            }
            cv.put("foul", foul ? 1 : 0);
            if (db.update("event_attempts", cv, "entry_id = ? AND number = ?",
                    new String[]{entryId, String.valueOf(number)}) == 0) {
                cv.put("id", newId());
                cv.put("entry_id", entryId);
                cv.put("number", number);
                db.insertOrThrow("event_attempts", null, cv);
            }
            storeRanking(db, round);
            writeRound(round, expectedVersion, new ContentValues(), actor,
                    ScoreAudit.ACTION_SCORE_UPDATE,
                    entry.name + ", attempt " + number + ": "
                            + (foul ? "foul" : Measure.formatDistance(distanceCm) + " m"), "");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void setEntryStatus(User actorArg, String entryId, int expectedVersion,
                               String resultStatus) {
        User actor = repo.actorIn(actorArg, repo.sportOfEntry(entryId));
        EventRound round = roundOfEntry(entryId);
        requireRoundScoring(actor, round);
        String status = checkResultStatus(resultStatus);
        EventEntry entry = findEntry(getEntries(round.id), entryId);

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("result_status", status);
            db.update("event_entries", cv, "id = ?", new String[]{entryId});
            storeRanking(db, round);
            writeRound(round, expectedVersion, new ContentValues(), actor,
                    ScoreAudit.ACTION_SCORE_UPDATE,
                    entry.name + ": " + (status.isEmpty() ? "result counts" : status), "");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static String checkResultStatus(String status) {
        String value = status == null ? "" : status.trim();
        for (String known : EventEntry.STATUSES) {
            if (known.equals(value)) {
                return value;
            }
        }
        throw new ValidationException("Choose a valid result status.");
    }

    /**
     * Works out positions (and qualifiers, for a heat) from the stored results and saves
     * them on every entrant. Called inside the transaction of each result change, so the
     * positions always agree with the results.
     */
    private void storeRanking(SQLiteDatabase db, EventRound round) {
        List<EventEntry> entries = getEntries(round.id);
        RuleSet rules = round.rules();
        if (round.isMeasured()) {
            EventRanking.rankMeasured(entries,
                    rules.get("tie_break", "").startsWith("Second-best"));
        } else {
            boolean heatsUsed = round.isHeat() && rules.isYes("heats");
            EventRanking.rankTimed(entries,
                    heatsUsed ? Math.max(0, rules.getInt("qualify_per_heat", 0)) : 0);
        }
        for (EventEntry e : entries) {
            ContentValues cv = new ContentValues();
            if (e.position == null) {
                cv.putNull("position");
            } else {
                cv.put("position", e.position);
            }
            cv.put("qualified", e.qualified ? 1 : 0);
            if (e.bestCm == null) {
                cv.putNull("best_cm");
            } else {
                cv.put("best_cm", e.bestCm);
            }
            db.update("event_entries", cv, "id = ?", new String[]{e.id});
        }
    }

    /** Saves a change to a heat or final and records it in its history, like writeMatch. */
    private void writeRound(EventRound before, int expectedVersion, ContentValues cv, User actor,
                            String action, String detail, String reason) {
        long now = System.currentTimeMillis();
        cv.put("version", expectedVersion + 1);
        cv.put("updated_at", now);
        cv.put("updated_by", actor.id);

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            if (db.update("event_rounds", cv, "id = ? AND version = ?",
                    new String[]{before.id, String.valueOf(expectedVersion)}) == 0) {
                throw new ConflictException("Someone else updated this event a moment ago. "
                        + "Your change was not saved. Check the latest results and try again.");
            }
            ContentValues audit = new ContentValues();
            audit.put("id", newId());
            audit.put("round_id", before.id);
            audit.put("changed_by", actor.id);
            audit.put("changed_at", now);
            audit.put("action", action);
            audit.put("detail", detail);
            audit.put("reason", reason);
            db.insertOrThrow("event_audit", null, audit);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void finaliseRound(User actorArg, String roundId, int expectedVersion) {
        User actor = repo.actorIn(actorArg, repo.sportOfRound(roundId));
        AccessControl.require(AccessControl.canFinaliseResults(actor,
                repo.getFlag(AppSettings.CORE_CAN_FINALISE)), actor, "make results official");
        EventRound round = requireRound(roundId);
        if (round.isFinal) {
            throw new ValidationException("These results are already official.");
        }
        if (!Match.STATUS_COMPLETED.equals(round.status)) {
            throw new ValidationException("Only a completed event can be made official.");
        }
        ContentValues cv = new ContentValues();
        cv.put("is_final", 1);
        writeRound(round, expectedVersion, cv, actor, ScoreAudit.ACTION_FINALISED,
                "Results made official", "");
    }

    public void reopenRound(User actorArg, String roundId, int expectedVersion, String reason) {
        User actor = repo.actorIn(actorArg, repo.sportOfRound(roundId));
        AccessControl.require(AccessControl.canFinaliseResults(actor,
                repo.getFlag(AppSettings.CORE_CAN_FINALISE)), actor, "reopen official results");
        EventRound round = requireRound(roundId);
        if (!round.isFinal) {
            throw new ValidationException("These results are not official, so they do not "
                    + "need reopening.");
        }
        if (clean(reason).isEmpty()) {
            throw new ValidationException("Give a reason for reopening official results.");
        }
        ContentValues cv = new ContentValues();
        cv.put("is_final", 0);
        writeRound(round, expectedVersion, cv, actor, ScoreAudit.ACTION_REOPENED,
                "Results reopened", clean(reason));
    }

    public List<ScoreAudit> getRoundAudit(User actorArg, String roundId) {
        User actor = repo.actorIn(actorArg, repo.sportOfRound(roundId));
        AccessControl.require(AccessControl.canViewScoreHistory(actor), actor,
                "view the result history");
        List<ScoreAudit> list = new ArrayList<>();
        Cursor c = query("SELECT a.*, u.name AS changed_by_name FROM event_audit a "
                + "LEFT JOIN users u ON u.id = a.changed_by "
                + "WHERE a.round_id = ? ORDER BY a.changed_at DESC, a.rowid DESC", roundId);
        try {
            while (c.moveToNext()) {
                ScoreAudit a = new ScoreAudit();
                a.id = text(c, "id");
                a.matchId = text(c, "round_id");
                a.changedByName = text(c, "changed_by_name");
                a.changedAt = c.getLong(c.getColumnIndexOrThrow("changed_at"));
                a.action = text(c, "action");
                a.oldScore = "";
                a.detailOnly = true;
                a.newScore = text(c, "detail");
                a.reason = text(c, "reason");
                list.add(a);
            }
        } finally {
            c.close();
        }
        return list;
    }

    /**
     * Builds the final of a race from its heats: everyone who qualified by position,
     * plus the fastest of the rest as far as the rules allow. All heats must be official.
     */
    public String createFinalFromHeats(User actorArg, String categoryId) {
        User actor = repo.actorIn(actorArg, repo.sportOfCategory(categoryId));
        AccessControl.require(AccessControl.canManageFixtures(actor), actor,
                "create the final");
        Category category = repo.getCategory(categoryId);
        if (category == null || !SportTemplate.ENGINE_TIMED.equals(category.engine)
                || !category.ruleSet().isYes("heats")) {
            throw new ValidationException("Only a race that is run in heats has a final "
                    + "made from them.");
        }
        List<EventRound> heats = new ArrayList<>();
        for (EventRound r : getRounds(categoryId)) {
            if (!r.isHeat()) {
                throw new ValidationException("This category already has a final.");
            }
            if (!r.isFinal) {
                throw new ValidationException(r.title() + " is not official yet. Every heat "
                        + "has to be made official before the final is drawn.");
            }
            heats.add(r);
        }
        if (heats.isEmpty()) {
            throw new ValidationException("There are no heats yet.");
        }

        List<EventEntry> all = new ArrayList<>();
        for (EventRound heat : heats) {
            all.addAll(getEntries(heat.id));
        }
        List<EventEntry> finalists = new ArrayList<>();
        for (EventEntry e : all) {
            if (e.qualified) {
                finalists.add(e);
            }
        }
        RuleSet rules = category.ruleSet();
        finalists.addAll(EventRanking.fastestNonQualifiers(all,
                Math.max(0, rules.getInt("fastest_losers", 0))));
        int lanes = Math.max(1, rules.getInt("lanes", 8));
        if (finalists.isEmpty()) {
            throw new ValidationException("Nobody qualified from the heats.");
        }
        if (finalists.size() > lanes) {
            throw new ValidationException(finalists.size() + " entrants qualified but there "
                    + "are only " + lanes + " lanes. Change the qualifying rules.");
        }
        // Lanes in order of heat time, fastest in lane 1.
        java.util.Collections.sort(finalists, new java.util.Comparator<EventEntry>() {
                    public int compare(EventEntry x, EventEntry y) {
                return x.timeMs - y.timeMs;
            }
        });

        EventRound finalRound = new EventRound();
        finalRound.categoryId = categoryId;
        finalRound.stage = EventRound.STAGE_FINAL;
        finalRound.date = DateUtil.today().compareTo(heats.get(0).date) > 0
                ? DateUtil.today() : heats.get(0).date;
        finalRound.time = "";
        finalRound.venue = heats.get(0).venue;
        finalRound.assignedTo = heats.get(0).assignedTo;

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            String id = saveRound(actor, finalRound);
            int lane = 1;
            for (EventEntry e : finalists) {
                ContentValues cv = new ContentValues();
                cv.put("id", newId());
                cv.put("round_id", id);
                cv.put("registration_id", e.registrationId);
                cv.put("lane", lane++);
                db.insertOrThrow("event_entries", null, cv);
            }
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }
}
