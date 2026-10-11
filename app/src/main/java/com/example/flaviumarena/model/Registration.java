package com.example.flaviumarena.model;

/**
 * One entry in a category: a team, or a single participant, representing a branch.
 *
 * The identity that matters is the branch and the academic year.
 *   Team category:        name is built from them, for example
 *                         "Computer Engineering — Year 3" or "... — Year 3 (Team A)".
 *   Individual category:  name is the participant's own name, and the branch and year
 *                         say whom they represent.
 */
public class Registration {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String[] STATUSES = {STATUS_PENDING, STATUS_APPROVED, STATUS_REJECTED};

    /** Academic years a registration can belong to. */
    public static final int MIN_YEAR = 1;
    public static final int MAX_YEAR = 4;
    public static final String[] YEAR_LABELS = {"Year 1", "Year 2", "Year 3", "Year 4"};

    public String id;
    public String categoryId;
    /** Display name. Built by the repository for teams; typed for individuals. */
    public String name;
    public String branchId;
    /** 1 to 4. 0 means "not set" (registrations made before branches were introduced). */
    public int year;
    /** Optional, tells apart two teams of the same branch and year, for example "Team A". */
    public String teamLabel;
    /** File path of the team logo inside the app's private storage, or empty. */
    public String logoPath;
    public String contactName;
    public String contactPhone;
    /** Member names, one per line. Empty for individual categories. */
    public String members;
    public String status;
    public long createdAt;

    // Filled in by the repository for display only
    public String branchName;
    public String branchCode;
    public String categoryName;
    public String sportName;

    public boolean isApproved() {
        return STATUS_APPROVED.equals(status);
    }

    public int memberCount() {
        if (members == null || members.trim().isEmpty()) {
            return 0;
        }
        return members.trim().split("\n").length;
    }

    /** "Computer Engineering — Year 3", or just the branch when the year is not set. */
    public String branchLine() {
        return branchLine(branchName, year);
    }

    public static String branchLine(String branchName, int year) {
        if (branchName == null || branchName.isEmpty()) {
            return "";
        }
        return year >= MIN_YEAR && year <= MAX_YEAR ? branchName + " — Year " + year : branchName;
    }

    /** The name of a team: branch, year and the optional label. */
    public static String teamName(String branchName, int year, String teamLabel) {
        String label = teamLabel == null ? "" : teamLabel.trim();
        return branchLine(branchName, year) + (label.isEmpty() ? "" : " (" + label + ")");
    }

    public static boolean isValidStatus(String status) {
        return STATUS_PENDING.equals(status) || STATUS_APPROVED.equals(status)
                || STATUS_REJECTED.equals(status);
    }
}
