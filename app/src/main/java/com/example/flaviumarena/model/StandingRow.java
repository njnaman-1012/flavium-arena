package com.example.flaviumarena.model;

/** One line of a points table. */
public class StandingRow {
    public String registrationId;
    public String name;
    public int played;
    public int won;
    public int drawn;
    public int lost;
    public int scored;
    public int conceded;
    public int points;

    public int difference() {
        return scored - conceded;
    }
}
