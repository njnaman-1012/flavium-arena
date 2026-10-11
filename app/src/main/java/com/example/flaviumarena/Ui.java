package com.example.flaviumarena;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.flaviumarena.model.EventRound;
import com.example.flaviumarena.model.Match;
import com.example.flaviumarena.model.Registration;

import java.util.Calendar;

/** Small display helpers shared by the screens. */
public final class Ui {

    private Ui() {
    }

    /** Picture that suits a sport, chosen from its name. */
    public static int sportPicture(String sportName) {
        String name = sportName == null ? "" : sportName.toLowerCase();
        // More specific names are tested first ("table tennis" before "tennis").
        if (name.contains("foot") || name.contains("soccer") || name.contains("futsal")) {
            return R.drawable.ill_football;
        }
        if (name.contains("cricket")) {
            return R.drawable.ill_cricket;
        }
        if (name.contains("badminton")) {
            return R.drawable.ill_badminton;
        }
        if (name.contains("table tennis") || name.contains("ping")) {
            return R.drawable.ill_table_tennis;
        }
        if (name.contains("tennis")) {
            return R.drawable.ill_lawn_tennis;
        }
        if (name.contains("squash")) {
            return R.drawable.ill_squash;
        }
        if (name.contains("swim")) {
            return R.drawable.ill_swimming;
        }
        if (name.contains("pool") || name.contains("snooker") || name.contains("billiard")) {
            return R.drawable.ill_pool_8_ball;
        }
        if (name.contains("carrom")) {
            return R.drawable.ill_carrom;
        }
        if (name.contains("chess")) {
            return R.drawable.ill_chess;
        }
        if (name.contains("athletic") || name.contains("track") || name.contains("race")) {
            return R.drawable.ill_athletics;
        }
        if (name.contains("volley") || name.contains("throwball")) {
            return R.drawable.ill_volleyball;
        }
        if (name.contains("tug")) {
            return R.drawable.ill_tug_of_war;
        }
        if (name.contains("basket") || name.contains("ball")) {
            return R.drawable.ill_basketball;
        }
        return R.drawable.ill_medal;
    }

    /** First letter of a name, used inside the round avatar. */
    public static String initial(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "?";
        }
        return name.trim().substring(0, 1).toUpperCase();
    }

    public static String greeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 12) {
            return "Good morning";
        }
        if (hour < 17) {
            return "Good afternoon";
        }
        return "Good evening";
    }

    /** List card for a match: sport picture, teams, details and status with score. */
    public static RowAdapter.Row matchRow(Match m) {
        String subtitle = m.sportName + ", " + m.categoryName + ", " + m.round + "\n"
                + m.date + " " + m.time + (m.venue.isEmpty() ? "" : ", " + m.venue);
        String badge = m.statusLabel() + (m.hasScore() ? "\n" + m.scoreText() : "");
        return new RowAdapter.Row(m.id, m.sideAText() + " vs " + m.sideBText(), subtitle, badge,
                sportPicture(m.sportName));
    }

    /**
     * List card for a heat or final. In a list of the whole festival the card also says
     * which category it belongs to.
     */
    public static RowAdapter.Row roundRow(EventRound r, boolean withCategory) {
        String subtitle = (withCategory ? r.sportName + "\n" : "")
                + r.date + (r.time.isEmpty() ? "" : " " + r.time)
                + (r.venue.isEmpty() ? "" : ", " + r.venue) + "\n"
                + r.entryCount + (r.entryCount == 1 ? " entrant" : " entrants");
        return new RowAdapter.Row(r.id, withCategory ? r.categoryName + ": " + r.title()
                : r.title(), subtitle, r.statusLabel(), sportPicture(r.sportName));
    }

    /**
     * Read-only text about a registration. The phone number is only present when the
     * repository allowed it, and the status only when "showStatus" is true.
     */
    public static String registrationSummary(Registration r, boolean team, boolean showStatus) {
        StringBuilder text = new StringBuilder(r.name);
        if (!team) {
            text.append("\n").append(r.branchLine());
        }
        if (showStatus) {
            text.append("\nStatus: ").append(r.status);
        }
        if (team && !r.contactName.isEmpty()) {
            text.append("\nCaptain: ").append(r.contactName);
        }
        if (!r.contactPhone.isEmpty()) {
            text.append("\nPhone: ").append(r.contactPhone);
        }
        if (team) {
            text.append("\n\nMembers\n").append(r.members);
        }
        return text.toString();
    }

    /**
     * Adds a tile to a vertical container, two tiles per row.
     * "index" is the position of the tile (0, 1, 2, ...).
     */
    public static View addTile(LinearLayout container, int index, int picture, String title,
                               String subtitle) {
        LayoutInflater inflater = LayoutInflater.from(container.getContext());
        LinearLayout row;
        if (index % 2 == 0) {
            row = new LinearLayout(container.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            container.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            row = (LinearLayout) container.getChildAt(container.getChildCount() - 1);
        }
        View tile = inflater.inflate(R.layout.item_tile, row, false);
        ((ImageView) tile.findViewById(R.id.ivTile)).setImageResource(picture);
        ((TextView) tile.findViewById(R.id.tvTileTitle)).setText(title);
        ((TextView) tile.findViewById(R.id.tvTileSub)).setText(subtitle);
        row.addView(tile);
        return tile;
    }

    /** Keeps a single tile from stretching across the whole row. */
    public static void padLastRow(LinearLayout container, int tileCount) {
        if (tileCount % 2 == 1) {
            LinearLayout row = (LinearLayout) container.getChildAt(container.getChildCount() - 1);
            View spacer = new View(container.getContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, 0, 1);
            int margin = (int) (6 * container.getResources().getDisplayMetrics().density);
            params.setMargins(margin, margin, margin, margin);
            row.addView(spacer, params);
        }
    }

    /** Fills a row of small dots and marks one as selected (used under the sliding pages). */
    public static void showDots(LinearLayout container, int count, int selected) {
        // Dots already there: only change which one is dark.
        if (container.getChildCount() == count) {
            for (int i = 0; i < count; i++) {
                container.getChildAt(i).setBackgroundResource(
                        i == selected ? R.drawable.dot_on : R.drawable.dot_off);
            }
            return;
        }
        container.removeAllViews();
        float density = container.getResources().getDisplayMetrics().density;
        int size = (int) (7 * density);
        int gap = (int) (4 * density);
        for (int i = 0; i < count; i++) {
            View dot = new View(container.getContext());
            dot.setBackgroundResource(i == selected ? R.drawable.dot_on : R.drawable.dot_off);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMargins(gap, 0, gap, 0);
            container.addView(dot, params);
        }
    }
}
