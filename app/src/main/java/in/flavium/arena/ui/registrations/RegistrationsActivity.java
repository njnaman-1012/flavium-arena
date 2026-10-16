package in.flavium.arena.ui.registrations;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;

import in.flavium.arena.R;
import in.flavium.arena.ui.common.BaseActivity;
import in.flavium.arena.ui.common.RowAdapter;
import in.flavium.arena.ui.common.SimpleTextWatcher;
import in.flavium.arena.ui.common.Ui;
import in.flavium.arena.auth.AccessControl;
import in.flavium.arena.model.Category;
import in.flavium.arena.model.Registration;

import java.util.ArrayList;
import java.util.List;

/**
 * Teams or participants of one category, each shown under the branch it represents.
 * The repository already returns only what the viewer may see: the public gets approved
 * entries, a captain additionally gets everything of their own branch, staff get all.
 */
public class RegistrationsActivity extends BaseActivity {

    private static final String[] FILTERS = {"All statuses", "Pending", "Approved", "Rejected"};

    private RowAdapter adapter;
    private EditText etSearch;
    private Spinner spFilter;
    private String categoryId;
    private Category category;
    private List<Registration> registrations = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
        category = repo.getCategory(categoryId);
        setupScreen(R.layout.activity_list,
                category != null && !category.isTeam() ? "Participants" : "Teams", true);
        if (category == null) {
            finish();
            return;
        }

        etSearch = findViewById(R.id.etSearch);
        etSearch.setVisibility(View.VISIBLE);
        etSearch.setHint("Search by branch or name");
        etSearch.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onChanged(String text) {
                showRows();
            }
        });

        spFilter = findViewById(R.id.spFilter);
        setSpinnerItems(spFilter, FILTERS);
        onSpinnerChanged(spFilter, this::showRows);

        adapter = new RowAdapter(this);
        ListView listView = findViewById(R.id.listView);
        listView.setEmptyView(findViewById(R.id.tvEmpty));
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) ->
                openRegistration(adapter.getItem(position).id));

        Button btnAdd = findViewById(R.id.btnAdd);
        btnAdd.setText(category.isTeam() ? "Register a team" : "Register a participant");
        btnAdd.setOnClickListener(v -> {
            Intent intent = new Intent(this, RegistrationEditActivity.class);
            intent.putExtra(EXTRA_CATEGORY_ID, categoryId);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScreen();
    }

    /** Shows the current data. Runs when the screen opens and when shared data changes. */
    @Override
    protected void refreshScreen() {
        if (adapter == null) {
            return;
        }
        // Statuses matter to staff and to a captain (for their own branch).
        // A Coach counts as an organiser inside the sports assigned to them.
        boolean manages = AccessControl.canManageRegistrations(userIn(category.sportId));
        boolean seesStatuses = AccessControl.isStaff(currentUser) || manages
                || AccessControl.isCaptain(currentUser);
        show(spFilter, seesStatuses);
        show(findViewById(R.id.btnAdd), manages || AccessControl.isCaptain(currentUser));
        registrations = repo.getRegistrations(currentUser, categoryId);
        showRows();
    }

    private void showRows() {
        String query = textOf(etSearch).toLowerCase();
        int filter = spFilter.getSelectedItemPosition();

        List<RowAdapter.Row> rows = new ArrayList<>();
        for (Registration r : registrations) {
            if (!(r.name + " " + r.branchName).toLowerCase().contains(query)) {
                continue;
            }
            if (spFilter.getVisibility() == View.VISIBLE && filter > 0
                    && !Registration.STATUSES[filter - 1].equals(r.status)) {
                continue;
            }
            boolean sensitive = AccessControl.canViewPrivateRegistrationData(
                    userIn(r.sportId), r.branchId);
            // A team's title already is its branch and year; an individual shows them below.
            String subtitle = category.isTeam()
                    ? r.memberCount() + " members"
                    : r.branchLine();
            RowAdapter.Row row = new RowAdapter.Row(r.id, r.name, subtitle,
                    sensitive ? r.status : "");
            row.imagePath = r.logoPath;
            row.circleText = r.branchCode.isEmpty() ? null : r.branchCode;
            rows.add(row);
        }
        adapter.setRows(rows);
    }

    /** Whoever may change something opens the form; everyone else gets a summary. */
    private void openRegistration(String id) {
        Registration r = repo.getRegistration(currentUser, id);
        if (r == null) {
            return;
        }
        if (AccessControl.canEditRegistration(userIn(r.sportId), r)
                || AccessControl.canSetLogo(userIn(r.sportId), r)) {
            Intent intent = new Intent(this, RegistrationEditActivity.class);
            intent.putExtra(EXTRA_CATEGORY_ID, categoryId);
            intent.putExtra(EXTRA_REGISTRATION_ID, id);
            startActivity(intent);
            return;
        }
        showMessage(Ui.registrationSummary(r, category.isTeam(),
                AccessControl.canViewPrivateRegistrationData(userIn(r.sportId), r.branchId)));
    }

    // ----- Downloads -----

    /** The registrations this person may see, as a file. */
    @Override
    protected String[] downloadNames() {
        return new String[]{"Registrations of this category (CSV)"};
    }

    @Override
    protected Download downloadAt(int position) {
        return exports -> exports.categoryRegistrations(categoryId);
    }
}
