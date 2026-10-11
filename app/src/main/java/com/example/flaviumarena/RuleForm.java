package com.example.flaviumarena;

import android.content.Context;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatEditText;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.appcompat.widget.SwitchCompat;

import com.example.flaviumarena.model.RuleField;
import com.example.flaviumarena.model.RuleSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a form from a list of RuleField objects instead of a fixed layout.
 *
 * For every field it adds a label and the right control (number box, text box, list or
 * switch) to a container. Fields that depend on another answer ("show the tie-break
 * settings only when tie-breaks are on") are shown and hidden as the answers change.
 * This is why Volleyball, Swimming and Chess can each have their own category form
 * without a separate screen for each sport.
 */
public class RuleForm {

    private final Context context;
    private final List<RuleField> fields;
    /** Starting values; also holds the fixed values that have no control. */
    private final RuleSet values;
    private final Map<String, View> controls = new LinkedHashMap<>();
    /** Every view that belongs to a field (label, control, hint), for showing and hiding. */
    private final Map<String, List<View>> fieldViews = new LinkedHashMap<>();
    private boolean building = true;

    public RuleForm(Context context, LinearLayout container, List<RuleField> fields,
                    RuleSet values) {
        this.context = context;
        this.fields = fields;
        this.values = values;

        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(context);
        for (RuleField field : fields) {
            if (field.isFixed()) {
                continue;
            }
            List<View> views = new ArrayList<>();

            TextView label = (TextView) inflater.inflate(R.layout.item_rule_label, container,
                    false);
            label.setText(field.label);
            View control = createControl(field, values.get(field.key, field.defaultValue));
            views.add(label);
            views.add(control);

            // A switch carries its own text, so it does not need a separate label.
            if (!RuleField.TYPE_TOGGLE.equals(field.type)) {
                container.addView(label);
            }
            container.addView(control, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            if (!field.hint.isEmpty()) {
                TextView hint = (TextView) inflater.inflate(R.layout.item_rule_hint, container,
                        false);
                hint.setText(field.hint);
                container.addView(hint);
                views.add(hint);
            }
            controls.put(field.key, control);
            fieldViews.put(field.key, views);
        }
        building = false;
        updateVisibility();
    }

    private View createControl(RuleField field, String value) {
        if (RuleField.TYPE_CHOICE.equals(field.type)) {
            // The AppCompat versions pick up the app's rounded input style.
            Spinner spinner = new AppCompatSpinner(context);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                    android.R.layout.simple_spinner_item, field.options);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
            spinner.setSelection(Math.max(0, field.options.indexOf(value)));
            spinner.setMinimumHeight((int) (50 * context.getResources().getDisplayMetrics().density));
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    updateVisibility();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            return spinner;
        }
        if (RuleField.TYPE_TOGGLE.equals(field.type)) {
            SwitchCompat toggle = new SwitchCompat(context);
            toggle.setText(field.label);
            toggle.setTextSize(14);
            toggle.setChecked(RuleSet.YES.equals(value));
            int top = (int) (14 * context.getResources().getDisplayMetrics().density);
            toggle.setPadding(0, top, 0, 0);
            toggle.setOnCheckedChangeListener((button, checked) -> updateVisibility());
            return toggle;
        }
        EditText box = new AppCompatEditText(context);
        box.setText(value);
        box.setInputType(RuleField.TYPE_NUMBER.equals(field.type)
                ? InputType.TYPE_CLASS_NUMBER
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return box;
    }

    private String valueOf(RuleField field) {
        View control = controls.get(field.key);
        if (control instanceof Spinner) {
            Object selected = ((Spinner) control).getSelectedItem();
            return selected == null ? field.defaultValue : selected.toString();
        }
        if (control instanceof SwitchCompat) {
            return ((SwitchCompat) control).isChecked() ? RuleSet.YES : RuleSet.NO;
        }
        return ((EditText) control).getText().toString().trim();
    }

    /** The answers as they stand now, including the fixed values of the template. */
    public RuleSet read() {
        RuleSet result = new RuleSet();
        for (RuleField field : fields) {
            result.put(field.key, field.isFixed()
                    ? values.get(field.key, field.defaultValue) : valueOf(field));
        }
        return result;
    }

    /** Shows each field only while the answer it depends on has the right value. */
    private void updateVisibility() {
        if (building) {
            return;
        }
        RuleSet now = read();
        for (RuleField field : fields) {
            List<View> views = fieldViews.get(field.key);
            if (views == null) {
                continue;
            }
            int visibility = field.isShownFor(now, fields) ? View.VISIBLE : View.GONE;
            for (View view : views) {
                view.setVisibility(visibility);
            }
        }
    }

    /** Locks or unlocks every control (used when the rules of a category are locked). */
    public void setEnabled(boolean enabled) {
        for (View control : controls.values()) {
            control.setEnabled(enabled);
        }
    }
}
