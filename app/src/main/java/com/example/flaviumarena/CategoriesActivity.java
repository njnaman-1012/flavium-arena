package com.example.flaviumarena;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ListView;

import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.Sport;

import java.util.ArrayList;
import java.util.List;

/** Categories of one sport. A Core can add a category from here. */
public class CategoriesActivity extends BaseActivity {

    private RowAdapter adapter;
    private String sportId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sportId = getIntent().getStringExtra(EXTRA_SPORT_ID);
        Sport sport = repo.getSport(sportId);
        setupScreen(R.layout.activity_list, sport == null ? "Categories" : sport.name, true);
        if (sport == null) {
            finish();
            return;
        }

        adapter = new RowAdapter(this);
        ListView listView = findViewById(R.id.listView);
        listView.setEmptyView(findViewById(R.id.tvEmpty));
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Intent intent = new Intent(this, CategoryDetailActivity.class);
            intent.putExtra(EXTRA_CATEGORY_ID, adapter.getItem(position).id);
            startActivity(intent);
        });

        Button btnAdd = findViewById(R.id.btnAdd);
        btnAdd.setText("Add category");
        btnAdd.setOnClickListener(v -> {
            Intent intent = new Intent(this, CategoryEditActivity.class);
            intent.putExtra(EXTRA_SPORT_ID, sportId);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter == null) {
            return;
        }
        show(findViewById(R.id.btnAdd), AccessControl.canManageCategories(currentUser));

        List<RowAdapter.Row> rows = new ArrayList<>();
        for (Category c : repo.getCategories(sportId)) {
            String subtitle = c.typeLabel() + ", " + c.sizeText()
                    + (c.deadline.isEmpty() ? "" : "\nRegister by " + c.deadline);
            rows.add(new RowAdapter.Row(c.id, c.name, subtitle, c.formatLabel()));
        }
        adapter.setRows(rows);
    }
}
