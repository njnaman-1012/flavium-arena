package com.example.flaviumarena.ui.common;

import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.example.flaviumarena.R;

/**
 * A small pop-up form built from dialog_form.xml: up to two lists, five number boxes,
 * one text box and a tick box. A screen switches on the parts it needs and gets the
 * answers back in one object. Used for correcting a set, entering an innings, a race
 * time or a throw, recording a match event and adding an entrant.
 */
public class FormDialog {

    /** What the person entered. Parts that were not shown keep their starting values. */
    public static class Values {
        public int choice;
        public int secondChoice;
        /** One value per number box; an empty box counts as 0. */
        public final int[] numbers = new int[5];
        public String text = "";
        public boolean ticked;
    }

    public interface OnSave {
        void onSave(Values values);
    }

    private final BaseActivity activity;
    private final String title;
    private final View form;
    private final EditText[] boxes;
    private int numberCount;

    public FormDialog(BaseActivity activity, String title) {
        this.activity = activity;
        this.title = title;
        form = activity.getLayoutInflater().inflate(R.layout.dialog_form, null);
        boxes = new EditText[]{form.findViewById(R.id.etNumber1),
                form.findViewById(R.id.etNumber2), form.findViewById(R.id.etNumber3),
                form.findViewById(R.id.etNumber4), form.findViewById(R.id.etNumber5)};
        // Everything starts hidden; each method below shows its own part.
        int[] parts = {R.id.tvDialogChoiceLabel, R.id.spDialogChoice,
                R.id.tvDialogChoice2Label, R.id.spDialogChoice2, R.id.tvNumber1Label,
                R.id.etNumber1, R.id.tvNumber2Label, R.id.etNumber2, R.id.tvNumber3Label,
                R.id.etNumber3, R.id.tvNumber4Label, R.id.etNumber4, R.id.tvNumber5Label,
                R.id.etNumber5, R.id.tvDialogTextLabel, R.id.etDialogText, R.id.cbDialogTick};
        for (int id : parts) {
            form.findViewById(id).setVisibility(View.GONE);
        }
    }

    public FormDialog choice(String label, String[] options, int selected) {
        return list(R.id.tvDialogChoiceLabel, R.id.spDialogChoice, label, options, selected);
    }

    public FormDialog secondChoice(String label, String[] options) {
        return list(R.id.tvDialogChoice2Label, R.id.spDialogChoice2, label, options, 0);
    }

    private FormDialog list(int labelId, int spinnerId, String label, String[] options,
                            int selected) {
        Spinner spinner = form.findViewById(spinnerId);
        showLabel(labelId, label);
        spinner.setVisibility(View.VISIBLE);
        activity.setSpinnerItems(spinner, options);
        spinner.setSelection(selected);
        return this;
    }

    /** One number box per label. Pass null as values to start with empty boxes. */
    public FormDialog numbers(String[] labels, int[] values) {
        int[] labelIds = {R.id.tvNumber1Label, R.id.tvNumber2Label, R.id.tvNumber3Label,
                R.id.tvNumber4Label, R.id.tvNumber5Label};
        numberCount = labels.length;
        for (int i = 0; i < labels.length; i++) {
            showLabel(labelIds[i], labels[i]);
            boxes[i].setVisibility(View.VISIBLE);
            boxes[i].setText(values == null ? "" : String.valueOf(values[i]));
        }
        return this;
    }

    /** A text box for names. */
    public FormDialog text(String label) {
        return text(label, "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
    }

    /** A text box with a starting value and a keyboard that suits what is typed. */
    public FormDialog text(String label, String value, int inputType) {
        EditText box = form.findViewById(R.id.etDialogText);
        showLabel(R.id.tvDialogTextLabel, label);
        box.setVisibility(View.VISIBLE);
        box.setInputType(inputType);
        box.setText(value);
        return this;
    }

    public FormDialog tickBox(String label) {
        CheckBox tick = form.findViewById(R.id.cbDialogTick);
        tick.setText(label);
        tick.setVisibility(View.VISIBLE);
        return this;
    }

    private void showLabel(int labelId, String label) {
        TextView view = form.findViewById(labelId);
        view.setText(label);
        view.setVisibility(View.VISIBLE);
    }

    public void show(OnSave onSave) {
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(form)
                .setPositiveButton("Save", (dialog, which) -> onSave.onSave(read()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private Values read() {
        Values values = new Values();
        Spinner first = form.findViewById(R.id.spDialogChoice);
        Spinner second = form.findViewById(R.id.spDialogChoice2);
        values.choice = Math.max(0, first.getSelectedItemPosition());
        values.secondChoice = Math.max(0, second.getSelectedItemPosition());
        for (int i = 0; i < numberCount; i++) {
            values.numbers[i] = activity.numberOf(boxes[i], 0);
        }
        values.text = activity.textOf((EditText) form.findViewById(R.id.etDialogText));
        values.ticked = ((CheckBox) form.findViewById(R.id.cbDialogTick)).isChecked();
        return values;
    }
}
