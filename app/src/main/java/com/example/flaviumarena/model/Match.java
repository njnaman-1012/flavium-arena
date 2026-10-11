package com.example.flaviumarena.model;

/** One fixture between two registrations, together with its score. */
public class Match {

    public static final String STATUS_UPCOMING = "UPCOMING";
    public static final String STATUS_LIVE = "LIVE";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_POSTPONED = "POSTPONED";
    public static final String[] STATUSES =
            {STATUS_UPCOMING, STATUS_LIVE, STATUS_PAUSED, STATUS_COMPLETED, STATUS_POSTPONED};
    public static final String[] STATUS_LABELS =
            {"Upcoming", "Live", "Paused", "Completed", "Postponed"};

    // Stored in the matches table
    public String id;
    public String categoryId;
    public String teamAId;
    public String teamBId;
    public String date;      // yyyy-MM-dd
    public String time;      // HH:mm, may be empty
    public String venue;
    public String round;
    public String status;
    public Integer scoreA;   // null until a score is entered
    public Integer scoreB;
    public String scoreNote;
    public boolean isFinal;  // true once a Core has finalised the result
    public String assignedTo; // id of the Asoo Core who may score this match, or null
    public long updatedAt;
    public String updatedBy;
    public int version;      // increases by 1 on every change, used to detect conflicts

    // Filled in by the repository for display only
    public String teamAName;
    public String teamBName;
    public String categoryName;
    public String sportName;
    public String sportId;
    public String categoryFormat;
    public String assignedName;
    public String updatedByName;

    // Added with sport-specific scoring
    /** Address of the match on CricHeroes (cricket only), or empty. */
    public String externalUrl = "";
    /** Half or quarter in play, 0 when not used. */
    public int currentPeriod;
    /** Penalty shoot-out score, null when there was none. */
    public Integer penA;
    public Integer penB;
    /** How this match is scored: the engine and rules of its category. */
    public String categoryEngine;
    public String categoryRules;

    public RuleSet rules() {
        return RuleSet.parse(categoryRules);
    }

    public boolean usesEngine(String engine) {
        return engine.equals(categoryEngine);
    }

    /** Play is in progress or interrupted: the match has started and is not over. */
    public boolean isUnderway() {
        return STATUS_LIVE.equals(status) || STATUS_PAUSED.equals(status);
    }

    /**
     * 1 when side A won, 2 when side B won, 0 for a draw or no result yet.
     * A level score that was settled by penalties has a winner.
     */
    public int winnerSide() {
        if (!hasScore()) {
            return 0;
        }
        if (!scoreA.equals(scoreB)) {
            return scoreA > scoreB ? 1 : 2;
        }
        if (penA != null && penB != null && !penA.equals(penB)) {
            return penA > penB ? 1 : 2;
        }
        return 0;
    }
    /** TEAM or INDIVIDUAL, from the category. */
    public String categoryType;
    // Whom each side represents
    public String branchAName;
    public String branchACode;
    public int yearA;
    public String logoA;
    public String branchBName;
    public String branchBCode;
    public int yearB;
    public String logoB;

    /**
     * Names for lists. A team name already contains its branch. An individual is shown
     * with the short code of the branch they represent, for example "Ananya Rao (CE)".
     */
    public String sideAText() {
        return sideText(teamAName, branchACode);
    }

    public String sideBText() {
        return sideText(teamBName, branchBCode);
    }

    /** A short name for buttons: "CE Y3" for a team, the person's name for an individual. */
    public String sideAShort() {
        return shortName(teamAName, branchACode, yearA);
    }

    public String sideBShort() {
        return shortName(teamBName, branchBCode, yearB);
    }

    private String shortName(String name, String branchCode, int year) {
        boolean individual = Category.TYPE_INDIVIDUAL.equals(categoryType);
        if (individual || branchCode == null || branchCode.isEmpty()) {
            return name;
        }
        return year > 0 ? branchCode + " Y" + year : branchCode;
    }

    private String sideText(String name, String branchCode) {
        boolean individual = Category.TYPE_INDIVIDUAL.equals(categoryType);
        if (individual && branchCode != null && !branchCode.isEmpty()) {
            return name + " (" + branchCode + ")";
        }
        return name;
    }

    public boolean hasScore() {
        return scoreA != null && scoreB != null;
    }

    public String scoreText() {
        return hasScore() ? scoreA + " - " + scoreB : "- : -";
    }

    public String statusLabel() {
        if (isFinal) {
            return "FINAL";
        }
        return status;
    }

    public static boolean isValidStatus(String status) {
        for (String s : STATUSES) {
            if (s.equals(status)) {
                return true;
            }
        }
        return false;
    }
}
