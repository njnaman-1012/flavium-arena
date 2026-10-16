package in.flavium.arena.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import in.flavium.arena.auth.AccessControl;
import in.flavium.arena.model.Branch;
import in.flavium.arena.model.Category;
import in.flavium.arena.model.Registration;
import in.flavium.arena.model.RuleSet;
import in.flavium.arena.model.User;
import in.flavium.arena.util.DateUtil;
import java.util.ArrayList;
import java.util.List;

import static in.flavium.arena.data.Sql.*;

/**
 * Registrations of teams and participants: who may see what, the limits of a category,
 * saving, approving and deleting. SqliteArenaRepository hands these calls on.
 */
final class RegistrationStore extends Store {

    RegistrationStore(SqliteArenaRepository repo, DbHelper helper) {
        super(repo, helper);
    }

    /** A registration with the names of its branch, category and sport. */
    static final String REG_SELECT =
            "SELECT r.*, b.name AS branch_name, b.code AS branch_code, "
                    + "c.name AS category_name, s.name AS sport_name, s.id AS sport_id "
                    + "FROM registrations r "
                    + "LEFT JOIN branches b ON b.id = r.branch_id "
                    + "JOIN categories c ON c.id = r.category_id "
                    + "JOIN sports s ON s.id = c.sport_id ";

    public List<Registration> getRegistrations(User actorArg, String categoryId) {
        return visibleRegistrations(repo.trusted(actorArg), "r.category_id = ?", categoryId,
                "r.name");
    }

    public List<Registration> getRegistrationsOfBranch(User actorArg, String branchId) {
        return visibleRegistrations(repo.trusted(actorArg), "r.branch_id = ?", branchId,
                "s.name, c.name, r.name");
    }

    private List<Registration> visibleRegistrations(User actor, String where, String argument,
                                                   String order) {
        List<Registration> list = new ArrayList<>();
        Cursor c = query(REG_SELECT + "WHERE " + where + " ORDER BY " + order, argument);
        try {
            while (c.moveToNext()) {
                Registration r = forViewer(actor, readRegistration(c));
                if (r != null) {
                    list.add(r);
                }
            }
        } finally {
            c.close();
        }
        return list;
    }

    /**
     * Decides what one person may see of a registration. This is done here, in the data
     * layer, so no screen can show more by mistake.
     *   Faculty, Core, Asoo Core: everything.
     *   A Coach: everything in the sports assigned to them; the public view elsewhere.
     *   A captain: everything about their own branch; the public view of other branches.
     *   The public: approved registrations only, without the phone number.
     */
    private static Registration forViewer(User actor, Registration r) {
        if (AccessControl.canViewPrivateRegistrationData(
                AccessControl.inSport(actor, r.sportId), r.branchId)) {
            return r;
        }
        if (!r.isApproved()) {
            return null;
        }
        r.contactPhone = "";
        return r;
    }

    public Registration getRegistration(User actorArg, String id) {
        Registration r = rawRegistration(id);
        return r == null ? null : forViewer(repo.trusted(actorArg), r);
    }

    /** The registration exactly as stored. Only for permission checks inside this class. */
    Registration rawRegistration(String id) {
        if (id == null) {
            return null;
        }
        Cursor c = query(REG_SELECT + "WHERE r.id = ?", id);
        try {
            return c.moveToFirst() ? readRegistration(c) : null;
        } finally {
            c.close();
        }
    }

    public String saveRegistration(User actorArg, Registration registration) {
        User actor = repo.actorIn(actorArg,
                registration.id == null ? repo.sportOfCategory(registration.categoryId) : repo.sportOfRegistration(registration.id));
        boolean isNew = registration.id == null;
        Registration existing = isNew ? null : rawRegistration(registration.id);
        if (!isNew && existing == null) {
            throw new ValidationException("This registration no longer exists.");
        }

        // Who may do this: Faculty and Core for any branch; a captain only for their own
        // branch, and only while the registration has not been approved.
        if (!isNew) {
            AccessControl.require(AccessControl.canEditRegistration(actor, existing), actor,
                    "edit this registration");
        }
        AccessControl.require(AccessControl.canRegisterForBranch(actor, registration.branchId),
                actor, "register for this branch");

        Category category = repo.getCategory(isNew ? registration.categoryId : existing.categoryId);
        if (category == null) {
            throw new ValidationException("The category of this registration no longer exists.");
        }
        Branch branch = repo.getBranch(registration.branchId);
        if (branch == null) {
            throw new ValidationException("Choose the branch.");
        }
        boolean branchChanged = isNew || !branch.id.equals(existing.branchId);
        if (branchChanged && !branch.active) {
            throw new ValidationException("This branch is deactivated. Choose an active branch.");
        }
        if (registration.year < Registration.MIN_YEAR || registration.year > Registration.MAX_YEAR) {
            throw new ValidationException("Choose the academic year (1 to 4).");
        }

        // The name: built from branch and year for a team, typed for an individual.
        String label = "";
        String name;
        if (category.isTeam()) {
            label = clean(registration.teamLabel);
            name = Registration.teamName(branch.name, registration.year, label);
        } else {
            name = clean(registration.name);
            if (name.isEmpty()) {
                throw new ValidationException("Participant name is required.");
            }
        }

        // Tidy the member list: one name per line, no blanks, no repeats.
        String members = "";
        if (category.isTeam()) {
            List<String> names = new ArrayList<>();
            for (String part : clean(registration.members).split("[\\n,]")) {
                String member = part.trim();
                if (member.isEmpty()) {
                    continue;
                }
                for (String already : names) {
                    if (already.equalsIgnoreCase(member)) {
                        throw new ValidationException("\"" + member
                                + "\" is listed more than once in this team.");
                    }
                }
                names.add(member);
            }
            if (names.size() < category.minSize || names.size() > category.maxSize) {
                throw new ValidationException("This category needs " + category.sizeText()
                        + " per team. You listed " + names.size() + ".");
            }
            StringBuilder sb = new StringBuilder();
            for (String member : names) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(member);
            }
            members = sb.toString();
        }

        SQLiteDatabase db = helper.getWritableDatabase();
        String id = isNew ? newId() : registration.id;
        if (count(db, "registrations", "category_id = ? AND name = ? AND id <> ?",
                category.id, name, id) > 0) {
            throw new ValidationException(category.isTeam()
                    ? "This branch already has a team for that year in this category. Give "
                    + "the second team a label such as \"Team B\"."
                    : "\"" + name + "\" is already registered in this category.");
        }
        if (isNew && !category.deadline.isEmpty()
                && DateUtil.today().compareTo(category.deadline) > 0) {
            throw new ValidationException("The registration deadline (" + category.deadline
                    + ") has passed. Extend the deadline in the category settings first.");
        }

        // Limits of the category. Rejected registrations never count. They are checked
        // for a new entry and when an entry moves to another branch or year, so an
        // existing entry can always be corrected.
        RuleSet limits = category.ruleSet();
        boolean placeChanged = branchChanged || existing.year != registration.year;
        if (category.isTeam() && placeChanged) {
            int allowed = Math.max(1, limits.getInt(RuleSet.TEAMS_PER_BRANCH, 1));
            int entered = count(db, "registrations", "category_id = ? AND branch_id = ? "
                            + "AND year = ? AND status <> 'REJECTED' AND id <> ?", category.id,
                    branch.id, String.valueOf(registration.year), id);
            if (entered >= allowed) {
                throw new ValidationException(allowed == 1
                        ? branch.name + " already has a team for Year " + registration.year
                        + " in this category, and the category allows one team per branch "
                        + "and year."
                        : branch.name + " already has " + entered + " teams for Year "
                        + registration.year + " in this category, which is the most the "
                        + "category allows.");
            }
            if (entered > 0 && label.isEmpty()) {
                throw new ValidationException("This is an additional team of " + branch.name
                        + ". Give it a label such as \"Team B\".");
            }
        }
        int capacity = Math.max(0, limits.getInt(RuleSet.MAX_ENTRIES, 0));
        if (isNew && capacity > 0 && count(db, "registrations",
                "category_id = ? AND status <> 'REJECTED'", category.id) >= capacity) {
            throw new ValidationException("This category is full: it accepts " + capacity
                    + " entries.");
        }

        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("branch_id", branch.id);
        cv.put("year", registration.year);
        cv.put("team_label", label);
        cv.put("logo_path", clean(registration.logoPath));
        cv.put("contact_name", clean(registration.contactName));
        cv.put("contact_phone", clean(registration.contactPhone));
        cv.put("members", members);
        if (isNew) {
            cv.put("id", id);
            cv.put("category_id", category.id);
            cv.put("status", Registration.STATUS_PENDING);
            cv.put("created_at", System.currentTimeMillis());
            try {
                db.insertOrThrow("registrations", null, cv);
            } catch (android.database.sqlite.SQLiteConstraintException e) {
                throw new ValidationException("\"" + name + "\" is already registered in "
                        + "this category.");
            }
        } else {
            // A captain who edits a rejected registration is submitting it again.
            if (AccessControl.isCaptain(actor)
                    && Registration.STATUS_REJECTED.equals(existing.status)) {
                cv.put("status", Registration.STATUS_PENDING);
            }
            db.update("registrations", cv, "id = ?", new String[]{id});
        }
        return id;
    }

    public void setRegistrationLogo(User actorArg, String id, String logoPath) {
        User actor = repo.actorIn(actorArg, repo.sportOfRegistration(id));
        Registration existing = rawRegistration(id);
        if (existing == null) {
            throw new ValidationException("This registration no longer exists.");
        }
        AccessControl.require(AccessControl.canSetLogo(actor, existing), actor,
                "change the logo of this team");
        ContentValues cv = new ContentValues();
        cv.put("logo_path", clean(logoPath));
        helper.getWritableDatabase().update("registrations", cv, "id = ?", new String[]{id});
    }

    public void setRegistrationStatus(User actorArg, String id, String status) {
        User actor = repo.actorIn(actorArg, repo.sportOfRegistration(id));
        AccessControl.require(AccessControl.canManageRegistrations(actor), actor,
                "approve or reject registrations");

        if (!Registration.isValidStatus(status)) {
            throw new ValidationException("Unknown registration status.");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        if (!Registration.STATUS_APPROVED.equals(status) && isUsedInMatches(db, id)) {
            throw new ValidationException("This registration already has fixtures, so it must "
                    + "stay approved. Delete its fixtures first.");
        }
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        if (db.update("registrations", cv, "id = ?", new String[]{id}) == 0) {
            throw new ValidationException("This registration no longer exists.");
        }
    }

    public void deleteRegistration(User actorArg, String id) {
        User actor = repo.actorIn(actorArg, repo.sportOfRegistration(id));
        Registration existing = rawRegistration(id);
        if (existing == null) {
            throw new ValidationException("This registration no longer exists.");
        }
        AccessControl.require(AccessControl.canEditRegistration(actor, existing), actor,
                "delete this registration");

        SQLiteDatabase db = helper.getWritableDatabase();
        if (isUsedInMatches(db, id)) {
            throw new ValidationException("This registration has fixtures. "
                    + "Delete its fixtures first.");
        }
        db.delete("registrations", "id = ?", new String[]{id});
    }

    private boolean isUsedInMatches(SQLiteDatabase db, String registrationId) {
        return count(db, "matches", "team_a_id = ? OR team_b_id = ?",
                registrationId, registrationId) > 0
                || count(db, "event_entries", "registration_id = ?", registrationId) > 0;
    }
}
