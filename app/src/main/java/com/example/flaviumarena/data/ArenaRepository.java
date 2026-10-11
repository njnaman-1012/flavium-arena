package com.example.flaviumarena.data;

import com.example.flaviumarena.model.AdminAudit;
import com.example.flaviumarena.model.Branch;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.DashboardStats;
import com.example.flaviumarena.model.EventEntry;
import com.example.flaviumarena.model.EventRound;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.MedalRow;
import com.example.flaviumarena.model.MedalWin;
import com.example.flaviumarena.model.MatchEvent;
import com.example.flaviumarena.model.MatchPeriod;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.model.ScoreAudit;
import com.example.flaviumarena.model.Sport;
import com.example.flaviumarena.model.SportTemplate;
import com.example.flaviumarena.model.StandingRow;
import com.example.flaviumarena.model.User;

import java.util.List;

/**
 * Every data operation the screens need.
 *
 * The screens only know this interface, never SQLite. The local version is
 * SqliteArenaRepository; an online version can implement the same interface later.
 *
 * "actor" is the logged-in user (null for a guest). Every method that changes data
 * checks the actor's role and throws PermissionDeniedException when it is not allowed.
 * Wrong input throws ValidationException and a stale update throws ConflictException.
 */
public interface ArenaRepository {

    // ----- Users -----
    /** expectedRole is the role chosen on the login screen; it must match the stored role. */
    User login(String email, String password, String expectedRole);

    User getUser(String id);

    /** The logged-in organiser changes their own password; the current one must be given. */
    void changeOwnPassword(User actor, String currentPassword, String newPassword);

    boolean hasFaculty();

    /** Works only while there is no Faculty account, and only for the configured email. */
    User createFirstFaculty(String name, String email, String password, String authorisedEmail);

    List<User> getUsers(User actor);

    List<User> getAsooCores(User actor);

    /** branchId is required for a Branch Captain and ignored for every other role. */
    String createUser(User actor, String name, String email, String password, String role,
                      String branchId);

    void updateUser(User actor, String userId, String name, String email, String role,
                    String branchId);

    void resetPassword(User actor, String userId, String newPassword);

    void setUserActive(User actor, String userId, boolean active);

    void deleteUser(User actor, String userId);

    // ----- Branches, settings, administrative log -----
    List<Branch> getBranches();

    Branch getBranch(String id);

    /** Faculty only. Renaming a branch also renames its teams. */
    String saveBranch(User actor, Branch branch);

    void setBranchActive(User actor, String id, boolean active);

    /** A Faculty-controlled switch, see AppSettings. */
    boolean getFlag(String key);

    void setFlag(User actor, String key, boolean value);

    List<AdminAudit> getAdminAudit(User actor);

    // ----- Sports -----
    List<Sport> getSports();

    Sport getSport(String id);

    // ----- Sport catalogue -----
    List<SportTemplate> getTemplates();

    SportTemplate getTemplate(String key);

    /**
     * Adds a sport from the catalogue. For the "custom" template a name is required;
     * for every other template the name comes from the template.
     */
    String addSportFromTemplate(User actor, String templateKey, String name,
                                String description);

    String saveSport(User actor, Sport sport);

    void deleteSport(User actor, String id);

    // ----- Categories -----
    List<Category> getCategories(String sportId);

    Category getCategory(String id);

    String saveCategory(User actor, Category category);

    void deleteCategory(User actor, String id);

    // ----- Registrations -----
    List<Registration> getRegistrations(User actor, String categoryId);

    /** Registrations of one branch in every category, as far as the actor may see them. */
    List<Registration> getRegistrationsOfBranch(User actor, String branchId);

    Registration getRegistration(User actor, String id);

    /** The only change a captain may still make to an approved team. */
    void setRegistrationLogo(User actor, String id, String logoPath);

    String saveRegistration(User actor, Registration registration);

    void setRegistrationStatus(User actor, String id, String status);

    void deleteRegistration(User actor, String id);

    // ----- Fixtures and scores -----

    /** All matches of one category, or of every category when categoryId is null. */
    List<Match> getMatches(String categoryId);

    List<Match> getMatchesOfBranch(String branchId);

    Match getMatch(String id);

    String saveFixture(User actor, Match match);

    void deleteFixture(User actor, String id);

    void updateScore(User actor, String matchId, int expectedVersion,
                     Integer scoreA, Integer scoreB, String note, String status);

    /** The same, also saving the period in play and a penalty shoot-out score. */
    void updateScore(User actor, String matchId, int expectedVersion,
                     Integer scoreA, Integer scoreB, String note, String status,
                     int currentPeriod, Integer penA, Integer penB);

    /** Start, pause, resume, postpone or complete a match without changing its score. */
    void setMatchStatus(User actor, String matchId, int expectedVersion, String status);

    // ----- Sport-specific scoring -----

    /** The sets (or games, frames, pulls) or the innings of a match, in order. */
    List<MatchPeriod> getPeriods(String matchId);

    /** Sets engine: the new total of one set. The match is recalculated from all sets. */
    void saveSetScore(User actor, String matchId, int expectedVersion, int setNumber,
                      int scoreA, int scoreB, int tieBreakA, int tieBreakB);

    /** Lawn tennis: one point to side "A" or "B". */
    void scoreTennisPoint(User actor, String matchId, int expectedVersion, String side);

    /** Cricket: the totals of one innings. */
    void saveInnings(User actor, String matchId, int expectedVersion, int number, String side,
                     int runs, int wickets, int overs, int ballsInOver, int extras);

    List<MatchEvent> getEvents(String matchId);

    String addEvent(User actor, String matchId, int minute, String side, String type,
                    String player);

    void deleteEvent(User actor, String eventId);

    void finaliseMatch(User actor, String matchId, int expectedVersion);

    /** Makes a final result editable again. A reason is required and recorded. */
    void reopenMatch(User actor, String matchId, int expectedVersion, String reason);

    void correctFinalResult(User actor, String matchId, int expectedVersion,
                            Integer scoreA, Integer scoreB, String note, String reason);

    List<ScoreAudit> getScoreAudit(User actor, String matchId);

    // ----- Heats and finals: races, relays, swimming, discus, shot put -----

    List<EventRound> getRounds(String categoryId);

    EventRound getRound(String id);

    String saveRound(User actor, EventRound round);

    void deleteRound(User actor, String id);

    /** Entrants with their results, placed ones first. Public information. */
    List<EventEntry> getEntries(String roundId);

    /** lane is the lane of a race, or the throwing order (0 = next free) of a field event. */
    String addEntry(User actor, String roundId, String registrationId, int lane);

    void removeEntry(User actor, String entryId);

    void setRoundStatus(User actor, String roundId, int expectedVersion, String status);

    /** A race result: time in thousandths of a second (null to clear) and a status. */
    void saveTimedResult(User actor, String entryId, int expectedVersion, Integer timeMs,
                         String resultStatus);

    /** One throw: distance in centimetres, or a foul. */
    void saveAttempt(User actor, String entryId, int expectedVersion, int number,
                     Integer distanceCm, boolean foul);

    /** Disqualified, did not start, did not finish, or back to a normal result. */
    void setEntryStatus(User actor, String entryId, int expectedVersion, String resultStatus);

    void finaliseRound(User actor, String roundId, int expectedVersion);

    void reopenRound(User actor, String roundId, int expectedVersion, String reason);

    List<ScoreAudit> getRoundAudit(User actor, String roundId);

    /** Draws the final of a race from the official results of its heats. */
    String createFinalFromHeats(User actor, String categoryId);

    // ----- Standings and statistics -----
    List<StandingRow> getStandings(String categoryId);

    /**
     * Medals decided so far, from official results only: in one category, or in the
     * whole festival when categoryId is null. Public information.
     */
    List<MedalWin> getMedals(String categoryId);

    /** Medals of every active branch, ordered by gold, then silver, then bronze. */
    List<MedalRow> getMedalTable();

    DashboardStats getStats(User actor);
}
