package com.example.flaviumarena.model;

import java.util.ArrayList;
import java.util.List;

/** An organiser account. The password is never kept in this object. */
public class User {
    public String id;
    public String name;
    public String email;
    public String role;
    /** A deactivated account cannot log in and has no permissions. */
    public boolean active = true;
    /** Only for a Branch Captain: the branch this account manages. Otherwise null. */
    public String branchId;
    /** Filled in by the repository for display only. */
    public String branchName;
    /**
     * The login session this object belongs to. It is set by the repository when
     * someone logs in. Without a live session the repository treats the person as a
     * member of the public, whatever the other fields say.
     */
    public String sessionId;

    /** Only for a Coach: the sports Faculty assigned to them. Filled in by the repository. */
    public List<String> coachSportIds = new ArrayList<>();
    /** The names of those sports, for display. */
    public String coachSportNames = "";
    /**
     * True on the copy of a Coach that AccessControl.inSport makes for one of their own
     * sports. It is never stored and never read from a screen's object: the repository
     * works it out again for every call.
     */
    public boolean sportAdmin;
}
