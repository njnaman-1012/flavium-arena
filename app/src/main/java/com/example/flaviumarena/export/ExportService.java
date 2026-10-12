package com.example.flaviumarena.export;

import com.example.flaviumarena.scoring.EventSummary;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.data.ArenaException;
import com.example.flaviumarena.data.ArenaRepository;
import com.example.flaviumarena.data.ValidationException;
import com.example.flaviumarena.model.Branch;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.EventEntry;
import com.example.flaviumarena.model.EventRound;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.MedalRow;
import com.example.flaviumarena.model.MedalWin;
import com.example.flaviumarena.model.Registration;
import com.example.flaviumarena.model.Role;
import com.example.flaviumarena.model.Sport;
import com.example.flaviumarena.model.SportTemplate;
import com.example.flaviumarena.model.StandingRow;
import com.example.flaviumarena.model.User;
import com.example.flaviumarena.scoring.Medals;
import com.example.flaviumarena.util.DateUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Every download of the app is made here, so the rules are in one place.
 *
 * How access is kept safe: this class has no way to the database of its own. It asks the
 * repository for the data on behalf of the person who is exporting, and the repository
 * gives each person only what they may see. A Branch CR who asks for another branch
 * gets what the public gets: approved entries, no phone numbers, no pending or rejected
 * registrations. Passwords and login sessions are not available through the repository
 * at all, so they can never appear in a file.
 *
 * The same class works unchanged when the repository behind it is an online one.
 */
public final class ExportService {

    private final ArenaRepository repo;
    /** The person exporting, or null for a member of the public. */
    private final User actor;

    public ExportService(ArenaRepository repo, User actor) {
        this.repo = repo;
        this.actor = actor;
    }

    // =====================================================================
    // One branch
    // =====================================================================

    /** Registrations of a branch: one row per team or participant. */
    public ExportFile branchRegistrations(String branchId) {
        Branch branch = requireBranch(branchId);
        List<Registration> list = repo.getRegistrationsOfBranch(actor, branchId);
        Csv csv = new Csv();
        registrationHeader(csv, list);
        for (Registration r : list) {
            registrationRow(csv, r, list);
        }
        return ExportFile.csv(Csv.fileName(branch.name, "Registrations"), csv);
    }

    /** Fixtures, scores and event results that involve a branch. */
    public ExportFile branchFixtures(String branchId) {
        Branch branch = requireBranch(branchId);
        Csv csv = new Csv();
        csv.row("Sport", "Category", "Round", "Date", "Time", "Venue", "Entry of this branch",
                "Opponent", "Status", "Result", "Score, time or distance", "Position");
        for (Match m : repo.getMatchesOfBranch(branchId)) {
            boolean sideA = branch.name.equals(m.branchAName);
            String result = "";
            // Two entries of the same branch met: there is no result for the branch.
            boolean bothSides = branch.name.equals(m.branchAName)
                    && branch.name.equals(m.branchBName);
            if (m.isFinal && m.hasScore() && !bothSides) {
                int winner = m.winnerSide();
                result = winner == 0 ? "Draw" : (winner == 1) == sideA ? "Won" : "Lost";
            }
            csv.row(m.sportName, m.categoryName, m.round, m.date, m.time, m.venue,
                    sideA ? m.sideAText() : m.sideBText(), sideA ? m.sideBText() : m.sideAText(),
                    m.statusLabel(), result, m.hasScore() ? scoreFor(m, sideA) : "", "");
        }
        // Races and throws: the entrants of this branch in every heat and final.
        for (EventRound round : repo.getRounds(null)) {
            for (EventEntry e : repo.getEntries(round.id)) {
                if (branchId.equals(e.branchId)) {
                    csv.row(round.sportName, round.categoryName, round.title(), round.date,
                            round.time, round.venue, e.name, "", round.statusLabel(),
                            e.qualified ? "Qualified" : "", EventSummary.result(round.isMeasured(), e),
                            e.position == null ? "" : e.position);
                }
            }
        }
        return ExportFile.csv(Csv.fileName(branch.name, "Fixtures and Results"), csv);
    }

    /** The score with this branch's side first, so "2 - 1" always reads as "ours - theirs". */
    private static String scoreFor(Match m, boolean sideA) {
        return sideA ? m.scoreA + " - " + m.scoreB : m.scoreB + " - " + m.scoreA;
    }

    /** Medals of a branch, with the counts in the last rows. */
    public ExportFile branchMedals(String branchId) {
        Branch branch = requireBranch(branchId);
        Csv csv = new Csv();
        csv.row("Branch", "Code", "Sport", "Category", "Won by", "Year", "Medal");
        int[] counts = new int[3];
        for (MedalWin win : repo.getMedals(null)) {
            if (branchId.equals(win.branchId)) {
                csv.row(branch.name, branch.code, win.sportName, win.categoryName, win.name,
                        yearText(win.year), Medals.label(win.medal));
                counts[Medals.order(win.medal)]++;
            }
        }
        csv.row();
        csv.row("Gold", counts[0]);
        csv.row("Silver", counts[1]);
        csv.row("Bronze", counts[2]);
        csv.row("Total", counts[0] + counts[1] + counts[2]);
        return ExportFile.csv(Csv.fileName(branch.name, "Medals"), csv);
    }

    /** A readable summary of one branch as a PDF. */
    public ExportFile branchReport(String branchId) {
        Branch branch = requireBranch(branchId);
        PdfReport pdf = new PdfReport(branch.name
                + (branch.code.isEmpty() ? "" : " (" + branch.code + ")"),
                "FLAVIUM Arena branch report, " + DateUtil.today() + viewNote());

        List<Registration> registrations = repo.getRegistrationsOfBranch(actor, branchId);
        pdf.heading("Teams and participants");
        if (registrations.isEmpty()) {
            pdf.note("No registrations to show.");
        }
        for (Registration r : registrations) {
            StringBuilder line = new StringBuilder(r.sportName).append(", ")
                    .append(r.categoryName).append(": ").append(r.name);
            if (r.memberCount() == 0) {
                line.append(" (").append(yearText(r.year)).append(")");
            }
            if (isPrivateView(branchId)) {
                line.append("  [").append(r.status).append("]");
            }
            pdf.line(line.toString());
            if (r.memberCount() > 0) {
                pdf.note("    " + r.members.replace("\n", ", "));
            }
        }

        pdf.heading("Fixtures and results");
        List<Match> matches = repo.getMatchesOfBranch(branchId);
        boolean any = false;
        for (Match m : matches) {
            pdf.line(m.date + " " + m.time + "  " + m.sportName + ", " + m.categoryName + ", "
                    + m.round + ": " + m.sideAText() + " vs " + m.sideBText() + "  "
                    + (m.hasScore() ? m.scoreText() + "  " : "") + m.statusLabel());
            any = true;
        }
        for (EventRound round : repo.getRounds(null)) {
            for (EventEntry e : repo.getEntries(round.id)) {
                if (branchId.equals(e.branchId)) {
                    String result = EventSummary.result(round.isMeasured(), e);
                    pdf.line(round.date + " " + round.time + "  " + round.sportName + ", "
                            + round.categoryName + ", " + round.title() + ": " + e.name
                            + (e.position == null ? "" : "  position " + e.position)
                            + (result.isEmpty() ? "" : "  " + result) + "  "
                            + round.statusLabel());
                    any = true;
                }
            }
        }
        if (!any) {
            pdf.note("No fixtures involve this branch yet.");
        }

        pdf.heading("Medals");
        int[] counts = new int[3];
        for (MedalWin win : repo.getMedals(null)) {
            if (branchId.equals(win.branchId)) {
                pdf.line(Medals.label(win.medal) + ": " + win.name + ", " + win.sportName + ", "
                        + win.categoryName);
                counts[Medals.order(win.medal)]++;
            }
        }
        pdf.line("Gold " + counts[0] + ", Silver " + counts[1] + ", Bronze " + counts[2]
                + ", Total " + (counts[0] + counts[1] + counts[2]));
        return pdf(Csv.fileName(branch.name, "Report"), pdf);
    }

    // =====================================================================
    // The whole festival
    // =====================================================================

    /** Every branch with its Branch CR (for those who may see accounts) and its entries. */
    public ExportFile branchList() {
        // Account names are only available to those who manage accounts.
        List<User> users = new ArrayList<>();
        boolean accounts = true;
        try {
            users = repo.getUsers(actor);
        } catch (ArenaException e) {
            accounts = false;
        }
        Csv csv = new Csv();
        if (accounts) {
            csv.row("Branch", "Code", "Status", "Branch CR", "Registrations", "Gold", "Silver",
                    "Bronze", "Total medals");
        } else {
            csv.row("Branch", "Code", "Status", "Registrations", "Gold", "Silver", "Bronze",
                    "Total medals");
        }
        List<MedalRow> medals = repo.getMedalTable();
        for (Branch b : repo.getBranches()) {
            if (!b.active && !AccessControl.canManageBranches(actor)) {
                continue;
            }
            MedalRow row = new MedalRow();
            for (MedalRow m : medals) {
                if (m.branchId.equals(b.id)) {
                    row = m;
                }
            }
            String cr = "";
            for (User u : users) {
                if (u.active && Role.CAPTAIN.equals(u.role) && b.id.equals(u.branchId)) {
                    cr = u.name;
                }
            }
            int entries = repo.getRegistrationsOfBranch(actor, b.id).size();
            String status = b.active ? "Active" : "Deactivated";
            if (accounts) {
                csv.row(b.name, b.code, status, cr, entries, row.gold, row.silver, row.bronze,
                        row.total());
            } else {
                csv.row(b.name, b.code, status, entries, row.gold, row.silver, row.bronze,
                        row.total());
            }
        }
        return ExportFile.csv("Branches", csv);
    }

    /** The medal table: gold, silver, bronze and total. There are no medal points. */
    public ExportFile medalTable() {
        Csv csv = new Csv();
        csv.row("Rank", "Branch", "Code", "Gold", "Silver", "Bronze", "Total");
        for (MedalRow m : repo.getMedalTable()) {
            csv.row(m.rank, m.branchName, m.branchCode, m.gold, m.silver, m.bronze, m.total());
        }
        csv.row();
        csv.row("Medal", "Won by", "Branch", "Year", "Sport", "Category");
        for (MedalWin win : repo.getMedals(null)) {
            csv.row(Medals.label(win.medal), win.name, win.branchName, yearText(win.year),
                    win.sportName, win.categoryName);
        }
        return ExportFile.csv("Medal_Table", csv);
    }

    /** Every registration the exporting person may see, across all categories. */
    public ExportFile allRegistrations() {
        List<Registration> all = new ArrayList<>();
        for (Sport sport : repo.getSports()) {
            for (Category c : repo.getCategories(sport.id)) {
                all.addAll(repo.getRegistrations(actor, c.id));
            }
        }
        Csv csv = new Csv();
        registrationHeader(csv, all);
        for (Registration r : all) {
            registrationRow(csv, r, all);
        }
        return ExportFile.csv("All_Registrations", csv);
    }

    /** Fixtures with scores: of one category, or of the whole festival when null. */
    public ExportFile fixtures(String categoryId) {
        Csv csv = new Csv();
        csv.row("Sport", "Category", "Round", "Date", "Time", "Venue", "Side A", "Branch A",
                "Side B", "Branch B", "Status", "Score A", "Score B", "Winner");
        String name = "All";
        for (Match m : repo.getMatches(categoryId)) {
            int winner = m.isFinal && m.hasScore() ? m.winnerSide() : 0;
            csv.row(m.sportName, m.categoryName, m.round, m.date, m.time, m.venue,
                    m.teamAName, m.branchAName, m.teamBName, m.branchBName, m.statusLabel(),
                    m.scoreA, m.scoreB,
                    winner == 0 ? "" : winner == 1 ? m.teamAName : m.teamBName);
            name = m.sportName + " " + m.categoryName;
        }
        return ExportFile.csv(Csv.fileName(categoryId == null ? "All" : name, "Fixtures"), csv);
    }

    /** A readable festival report as a PDF: the medal table and every medal. */
    public ExportFile festivalReport() {
        PdfReport pdf = new PdfReport("FLAVIUM Arena",
                "Festival report, " + DateUtil.today() + ". Official results only.");
        pdf.heading("Medal table");
        for (MedalRow m : repo.getMedalTable()) {
            pdf.line(m.rank + ".  " + m.branchName + "   Gold " + m.gold + ", Silver " + m.silver
                    + ", Bronze " + m.bronze + ", Total " + m.total());
        }
        pdf.heading("Medals won");
        List<MedalWin> wins = repo.getMedals(null);
        if (wins.isEmpty()) {
            pdf.note("No medals have been decided yet.");
        }
        for (MedalWin win : wins) {
            pdf.line(win.sportName + ", " + win.categoryName + ": " + Medals.label(win.medal)
                    + "  " + win.name + (win.team || win.branchName == null
                    || win.branchName.isEmpty() ? "" : " (" + win.branchName + ")"));
        }
        pdf.heading("Final results");
        boolean any = false;
        for (Match m : repo.getMatches(null)) {
            if (m.isFinal) {
                pdf.line(m.sportName + ", " + m.categoryName + ", " + m.round + ": "
                        + m.sideAText() + " " + m.scoreText() + " " + m.sideBText());
                any = true;
            }
        }
        if (!any) {
            pdf.note("No fixture has a final result yet.");
        }
        return pdf("Festival_Report", pdf);
    }

    // =====================================================================
    // One category
    // =====================================================================

    /** Registrations of one category. */
    public ExportFile categoryRegistrations(String categoryId) {
        Category category = requireCategory(categoryId);
        List<Registration> list = repo.getRegistrations(actor, categoryId);
        Csv csv = new Csv();
        registrationHeader(csv, list);
        for (Registration r : list) {
            registrationRow(csv, r, list);
        }
        return ExportFile.csv(Csv.fileName(sportName(category), category.name,
                "Registrations"), csv);
    }

    /**
     * Standings of one category in the form that suits it: the league table, the
     * knockout fixtures round by round, or the placings of a race or throwing final.
     * The medals of the category follow.
     */
    public ExportFile categoryStandings(String categoryId) {
        Category category = requireCategory(categoryId);
        Csv csv = new Csv();
        boolean manyEntrants = SportTemplate.ENGINE_TIMED.equals(category.engine)
                || SportTemplate.ENGINE_MEASURED.equals(category.engine);
        if (manyEntrants) {
            csv.row("Round", "Position", "Entrant", "Branch", "Year", "Lane or order",
                    "Time or distance", "Status", "Qualified");
            for (EventRound round : repo.getRounds(categoryId)) {
                for (EventEntry e : repo.getEntries(round.id)) {
                    csv.row(round.title() + (round.isFinal ? "" : " (provisional)"),
                            e.position, e.name, e.branchName, yearText(e.year), e.lane,
                            EventSummary.result(round.isMeasured(), e), e.resultStatus, e.qualified ? "Yes" : "");
                }
            }
        } else if (category.isKnockout()) {
            csv.row("Round", "Date", "Side A", "Side B", "Status", "Score A", "Score B",
                    "Went through");
            for (Match m : repo.getMatches(categoryId)) {
                int winner = m.isFinal && m.hasScore() ? m.winnerSide() : 0;
                csv.row(m.round, m.date, m.sideAText(), m.sideBText(), m.statusLabel(),
                        m.scoreA, m.scoreB,
                        winner == 0 ? "" : winner == 1 ? m.teamAName : m.teamBName);
            }
        } else {
            // Win, draw and loss points belong to the league table of a category.
            csv.row("Position", "Team or participant", "Branch and year", "Played", "Won",
                    "Drawn", "Lost", "For", "Against", "Difference", "League points");
            int place = 1;
            for (StandingRow s : repo.getStandings(categoryId)) {
                csv.row(place++, s.name, s.detail, s.played, s.won, s.drawn, s.lost, s.scored,
                        s.conceded, s.difference(), s.points);
            }
        }
        csv.row();
        csv.row("Medal", "Won by", "Branch");
        for (MedalWin win : repo.getMedals(categoryId)) {
            csv.row(Medals.label(win.medal), win.name, win.branchName);
        }
        return ExportFile.csv(Csv.fileName(sportName(category), category.name, "Standings"),
                csv);
    }

    /** Entrants and results of one heat or final. */
    public ExportFile eventResults(String roundId) {
        EventRound round = repo.getRound(roundId);
        if (round == null) {
            throw new ValidationException("This heat or final no longer exists.");
        }
        Csv csv = new Csv();
        csv.row("Position", "Entrant", "Branch", "Year", round.isMeasured() ? "Order" : "Lane",
                round.isMeasured() ? "Best distance (m)" : "Time", "Attempts", "Status",
                "Qualified");
        for (EventEntry e : repo.getEntries(roundId)) {
            StringBuilder attempts = new StringBuilder();
            for (EventEntry.Attempt a : e.attempts) {
                attempts.append(attempts.length() > 0 ? "; " : "")
                        .append(EventSummary.attempt(a));
            }
            csv.row(e.position, e.name, e.branchName, yearText(e.year), e.lane,
                    EventSummary.result(round.isMeasured(), e), attempts, e.resultStatus, e.qualified ? "Yes" : "");
        }
        return ExportFile.csv(Csv.fileName(round.sportName, round.categoryName, round.title(),
                round.isFinal ? "Results" : "Provisional Results"), csv);
    }

    // =====================================================================
    // Shared pieces
    // =====================================================================

    /** Phone numbers and statuses appear only when the repository handed them out. */
    private static boolean hasPhones(List<Registration> list) {
        for (Registration r : list) {
            if (!r.contactPhone.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUnapproved(List<Registration> list) {
        for (Registration r : list) {
            if (!r.isApproved()) {
                return true;
            }
        }
        return false;
    }

    private void registrationHeader(Csv csv, List<Registration> list) {
        List<Object> cells = new ArrayList<>();
        java.util.Collections.addAll(cells, "Branch", "Code", "Academic year", "Team label",
                "Sport", "Category", "Team or participant", "Team captain or contact",
                "Participants", "Status");
        if (hasPhones(list)) {
            cells.add("Contact phone");
        }
        csv.row(cells.toArray());
    }

    private void registrationRow(Csv csv, Registration r, List<Registration> list) {
        List<Object> cells = new ArrayList<>();
        java.util.Collections.addAll(cells, r.branchName, r.branchCode, yearText(r.year),
                r.teamLabel, r.sportName, r.categoryName, r.name, r.contactName,
                r.memberCount() == 0 ? r.name : r.members.replace("\n", "; "), r.status);
        if (hasPhones(list)) {
            cells.add(r.contactPhone);
        }
        csv.row(cells.toArray());
    }

    private static String yearText(int year) {
        return year >= Registration.MIN_YEAR && year <= Registration.MAX_YEAR
                ? "Year " + year : "";
    }

    private boolean isPrivateView(String branchId) {
        return AccessControl.canViewPrivateRegistrationData(actor, branchId);
    }

    private String viewNote() {
        return actor == null ? ". Public information only." : "";
    }

    private Branch requireBranch(String branchId) {
        Branch branch = repo.getBranch(branchId);
        if (branch == null) {
            throw new ValidationException("This branch no longer exists.");
        }
        return branch;
    }

    private Category requireCategory(String categoryId) {
        Category category = repo.getCategory(categoryId);
        if (category == null) {
            throw new ValidationException("This category no longer exists.");
        }
        return category;
    }

    private String sportName(Category category) {
        Sport sport = repo.getSport(category.sportId);
        return sport == null ? "" : sport.name;
    }

    private static ExportFile pdf(String name, PdfReport report) {
        try {
            return new ExportFile(name + ".pdf", ExportFile.TYPE_PDF, report.finish());
        } catch (IOException e) {
            throw new ValidationException("The report could not be created: " + e.getMessage());
        }
    }
}
