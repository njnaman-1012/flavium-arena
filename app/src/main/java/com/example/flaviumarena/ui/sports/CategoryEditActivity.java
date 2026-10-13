package com.example.flaviumarena.ui.sports;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.common.BaseActivity;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.data.CategoryRules;
import com.example.flaviumarena.model.Category;
import com.example.flaviumarena.model.RuleSet;
import com.example.flaviumarena.model.Sport;
import com.example.flaviumarena.model.SportTemplate;

import java.util.Map;

/**
 * Adds or edits a category. The questions on this screen are not fixed: they come from
 * the template of the category's sport, so Volleyball asks about sets, Swimming about
 * distance and lanes, Cricket about overs. The answers are saved with the category.
 */
public class CategoryEditActivity extends BaseActivity {

    private EditText etName, etDeadline;
    private Spinner spPreset;
    private LinearLayout rulesContainer;

    private Category category;
    private SportTemplate template;
    private RuleForm form;
    /** Name filled in from a preset, so a later preset may replace it but not a typed name. */
    private String presetName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
        setupScreen(R.layout.activity_category_edit,
                categoryId == null ? "Add category" : "Edit category", true);

        etName = findViewById(R.id.etCategoryName);
        etDeadline = findViewById(R.id.etDeadline);
        spPreset = findViewById(R.id.spPreset);
        rulesContainer = findViewById(R.id.rulesContainer);
        attachDatePicker(etDeadline);

        if (categoryId == null) {
            category = new Category();
            category.sportId = getIntent().getStringExtra(EXTRA_SPORT_ID);
            category.deadline = "";
            category.rules = "";
        } else {
            category = repo.getCategory(categoryId);
        }
        Sport sport = category == null ? null : repo.getSport(category.sportId);
        if (sport == null) {
            finish();
            return;
        }
        template = repo.getTemplate(sport.templateKey);
        if (template == null) {
            template = repo.getTemplate(SportTemplate.CUSTOM);
        }

        TextView tvIntro = findViewById(R.id.tvCategoryIntro);
        tvIntro.setText(sport.name + ". " + template.source);

        RuleSet values;
        if (category.id == null) {
            values = template.defaults();
            setUpPresets();
        } else {
            show(findViewById(R.id.tvPresetLabel), false);
            show(spPreset, false);
            etName.setText(category.name);
            etDeadline.setText(category.deadline);
            values = existingValues();
        }
        form = new RuleForm(this, rulesContainer, template.fields, values);

        findViewById(R.id.btnSaveCategory).setOnClickListener(v -> save());
    }

    /**
     * Values of a saved category. Its own rules are laid over the template's defaults,
     * so a question added to the template later still has an answer. A category from
     * before templates existed gets its rules from its old columns.
     */
    private RuleSet existingValues() {
        RuleSet stored = category.ruleSet();
        if (stored.isEmpty()) {
            return CategoryRules.fromColumns(template, category);
        }
        RuleSet values = template.defaults();
        for (Map.Entry<String, String> entry : stored.asMap().entrySet()) {
            values.put(entry.getKey(), entry.getValue());
        }
        return values;
    }

    /** The "Start from" list: the ready-made categories of this sport. */
    private void setUpPresets() {
        if (template.presets.isEmpty()) {
            show(findViewById(R.id.tvPresetLabel), false);
            show(spPreset, false);
            return;
        }
        String[] names = new String[template.presets.size() + 1];
        names[0] = "My own category";
        for (int i = 0; i < template.presets.size(); i++) {
            names[i + 1] = template.presets.get(i).name;
        }
        setSpinnerItems(spPreset, names);
        onSpinnerChanged(spPreset, () -> {
            int index = spPreset.getSelectedItemPosition();
            SportTemplate.Preset preset = index <= 0 ? null : template.presets.get(index - 1);
            // Rebuild the form with the preset's values.
            form = new RuleForm(this, rulesContainer, template.fields,
                    template.valuesFor(preset));
            // Suggest the preset's name unless the organiser has typed their own.
            if (textOf(etName).isEmpty() || textOf(etName).equals(presetName)) {
                presetName = preset == null ? "" : preset.name;
                etName.setText(presetName);
            }
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
        if (!AccessControl.canManageCategories(userIn(category == null ? null : category.sportId))) {
            toast("Only Faculty, Cores and the Coach of this sport can change categories.");
            finish();
        }
    }

    private void save() {
        category.name = textOf(etName);
        category.deadline = textOf(etDeadline);
        category.rules = form.read().toText();

        if (attempt(() -> repo.saveCategory(currentUser, category))) {
            toast("Category saved");
            finish();
        }
    }
}
