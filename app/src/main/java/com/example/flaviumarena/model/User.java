package com.example.flaviumarena.model;

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
}
