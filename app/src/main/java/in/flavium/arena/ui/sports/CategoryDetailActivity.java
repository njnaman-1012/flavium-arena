package in.flavium.arena.ui.sports;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import in.flavium.arena.R;
import in.flavium.arena.ui.common.BaseActivity;
import in.flavium.arena.ui.events.RoundsActivity;
import in.flavium.arena.ui.matches.FixturesActivity;
import in.flavium.arena.ui.registrations.RegistrationsActivity;
import in.flavium.arena.ui.standings.StandingsActivity;
import in.flavium.arena.auth.AccessControl;
import in.flavium.arena.model.Category;
import in.flavium.arena.model.RuleField;
import in.flavium.arena.model.RuleSet;
import in.flavium.arena.model.Sport;
import in.flavium.arena.model.SportTemplate;

/** Rules of one category, with links to its teams, fixtures and standings. */
public class CategoryDetailActivity extends BaseActivity {

    private String categoryId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_category_detail, "Category", true);
        categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);

        setUpLink(R.id.btnTeams, R.drawable.ill_medal, "Teams and participants",
                "See who has registered", RegistrationsActivity.class);
        findViewById(R.id.btnEditCategory).setOnClickListener(v ->
                open(CategoryEditActivity.class));
        findViewById(R.id.btnDeleteCategory).setOnClickListener(v ->
                confirm("Delete this category?", () -> {
                    if (attempt(() -> repo.deleteCategory(currentUser, categoryId))) {
                        toast("Category deleted");
                        finish();
                    }
                }));
    }

    /** Fills one of the three picture cards and makes it open a screen. */
    private void setUpLink(int cardId, int picture, String title, String text, Class<?> screen) {
        View card = findViewById(cardId);
        ((ImageView) card.findViewById(R.id.ivLink)).setImageResource(picture);
        ((TextView) card.findViewById(R.id.tvLinkTitle)).setText(title);
        ((TextView) card.findViewById(R.id.tvLinkText)).setText(text);
        card.setOnClickListener(v -> open(screen));
    }

    /**
     * The rules of the category in words. Each line is a question of the sport's
     * template with this category's answer; questions that do not apply are left out.
     */
    private String describe(Category c) {
        StringBuilder text = new StringBuilder();
        text.append("Registration deadline: ").append(c.deadline.isEmpty() ? "none" : c.deadline);

        Sport sport = repo.getSport(c.sportId);
        SportTemplate template = sport == null ? null : repo.getTemplate(sport.templateKey);
        RuleSet rules = c.ruleSet();
        if (template == null || rules.isEmpty()) {
            // A category from before templates: show the values it has always had.
            text.append("\nType: ").append(c.typeLabel()).append(" (").append(c.sizeText())
                    .append(")\nFormat: ").append(c.formatLabel())
                    .append("\nPoints: win ").append(c.ptsWin).append(", draw ")
                    .append(c.ptsDraw).append(", loss ").append(c.ptsLoss);
            return text.toString();
        }
        for (RuleField field : template.fields) {
            if (!field.isShownFor(rules, template.fields) || RuleSet.ENGINE.equals(field.key)) {
                continue;
            }
            String value = rules.get(field.key, "");
            if (!value.isEmpty()) {
                text.append("\n").append(field.label).append(": ").append(value);
            }
        }
        return text.toString();
    }

    private void open(Class<?> screen) {
        Intent intent = new Intent(this, screen);
        intent.putExtra(EXTRA_CATEGORY_ID, categoryId);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScreen();
    }

    /** Shows the current data. Runs when the screen opens and when shared data changes. */
    @Override
    protected void refreshScreen() {
        Category c = repo.getCategory(categoryId);
        if (c == null) {
            finish();
            return;
        }
        TextView tvName = findViewById(R.id.tvCategoryName);
        TextView tvInfo = findViewById(R.id.tvCategoryInfo);
        tvName.setText(c.name);
        tvInfo.setText(describe(c));

        // Races and throws have heats and finals with many entrants instead of fixtures
        // between two sides, and no league table.
        boolean manyEntrants = SportTemplate.ENGINE_TIMED.equals(c.engine)
                || SportTemplate.ENGINE_MEASURED.equals(c.engine);
        if (manyEntrants) {
            boolean heats = SportTemplate.ENGINE_TIMED.equals(c.engine)
                    && c.ruleSet().isYes("heats");
            setUpLink(R.id.btnFixtures, R.drawable.ill_trophy,
                    heats ? "Heats and final" : "Final and results",
                    "Schedule, entrants, results and positions", RoundsActivity.class);
        } else {
            setUpLink(R.id.btnFixtures, R.drawable.ill_trophy, "Fixtures and scores",
                    "Schedule, live scores and results", FixturesActivity.class);
        }
        // What "standings" are depends on how the category is played.
        if (manyEntrants) {
            setUpLink(R.id.btnStandings, R.drawable.ill_board, "Placings and medals",
                    "Result of the final", StandingsActivity.class);
        } else if (c.isKnockout()) {
            setUpLink(R.id.btnStandings, R.drawable.ill_board, "Knockout rounds and medals",
                    "Who went through, round by round", StandingsActivity.class);
        } else {
            setUpLink(R.id.btnStandings, R.drawable.ill_board, "Points table and medals",
                    "Standings from finalised results", StandingsActivity.class);
        }

        boolean canManage = AccessControl.canManageCategories(userIn(c.sportId));
        show(findViewById(R.id.btnEditCategory), canManage);
        show(findViewById(R.id.btnDeleteCategory), canManage);
    }

    // ----- Downloads -----

    /** The records of this category as files. */
    @Override
    protected String[] downloadNames() {
        return new String[]{"Registrations (CSV)", "Fixtures and scores (CSV)", "Standings and medals (CSV)"};
    }

    @Override
    protected Download downloadAt(int position) {
        if (position == 0) {
            return exports -> exports.categoryRegistrations(categoryId);
        }
        if (position == 1) {
            return exports -> exports.fixtures(categoryId);
        }
        return exports -> exports.categoryStandings(categoryId);
    }
}
