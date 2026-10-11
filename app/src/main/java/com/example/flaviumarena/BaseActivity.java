package com.example.flaviumarena;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.database.sqlite.SQLiteException;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.flaviumarena.auth.Session;
import com.example.flaviumarena.data.ArenaException;
import com.example.flaviumarena.data.ArenaRepository;
import com.example.flaviumarena.data.RepositoryProvider;
import com.example.flaviumarena.model.User;

import java.util.Calendar;
import java.util.Locale;

/**
 * Parent of every screen. It provides the repository, the logged-in user and a few
 * small helpers, so the individual activities stay short.
 */
public abstract class BaseActivity extends AppCompatActivity {

    // Keys used to pass ids from one screen to the next.
    public static final String EXTRA_SPORT_ID = "sport_id";
    public static final String EXTRA_CATEGORY_ID = "category_id";
    public static final String EXTRA_REGISTRATION_ID = "registration_id";
    public static final String EXTRA_MATCH_ID = "match_id";
    public static final String EXTRA_ROUND_ID = "round_id";
    public static final String EXTRA_BRANCH_ID = "branch_id";
    public static final String EXTRA_ONLY_MINE = "only_mine";

    protected ArenaRepository repo;
    /** The logged-in user, or null for a member of the public (read-only). */
    protected User currentUser;
    /** True when the database could not be opened; the screen then only shows an error. */
    protected boolean databaseFailed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        repo = RepositoryProvider.get(this);
        try {
            currentUser = Session.currentUser(this, repo);
        } catch (SQLiteException e) {
            // The database could not be opened, for example because an upgrade failed.
            // An upgrade runs in a transaction, so the old data is still there untouched.
            databaseFailed = true;
            showDatabaseError(e);
        }
    }

    /** Replaces the screen with a plain message. Subclasses must stop when databaseFailed. */
    private void showDatabaseError(SQLiteException e) {
        TextView message = new TextView(this);
        int padding = (int) (32 * getResources().getDisplayMetrics().density);
        message.setPadding(padding, padding * 2, padding, padding);
        message.setTextSize(15);
        message.setText("FLAVIUM Arena could not open its database.\n\n"
                + "Your existing data has not been changed. Close the app and install a "
                + "corrected version.\n\nDetails: " + e.getMessage());
        setContentView(message);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (databaseFailed) {
            return;
        }
        // Read the user again: they may have logged in or out on another screen,
        // or their account may have been deactivated.
        currentUser = Session.currentUser(this, repo);
        // Say so when the session was ended from somewhere else.
        String ended = Session.takeEndMessage();
        if (ended != null) {
            showMessage(ended);
        }
    }

    /** Sets the layout, the title bar and the padding that keeps content clear of system bars. */
    protected void setupScreen(int layoutId, String title, boolean showBackArrow) {
        setContentView(layoutId);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(showBackArrow);
        }
        applyInsets();
    }

    /** Changes the text in the title bar after the screen has been set up. */
    protected void setScreenTitle(String title) {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(title);
        }
    }

    /** Pads the root view so content is not hidden behind the status bar, nav bar or keyboard. */
    protected void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    /** Opens the home screen and clears the screens behind it (used after login and logout). */
    protected void openHome() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    /** The back arrow in the title bar closes the screen. */
    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /**
     * Runs a repository call and shows its error message if it is refused.
     * Returns true when the call succeeded.
     */
    protected boolean attempt(Runnable action) {
        try {
            action.run();
            return true;
        } catch (ArenaException e) {
            showMessage(e.getMessage());
            return false;
        }
    }

    /** Lets the logged-in organiser change their own password. */
    protected void showChangePasswordDialog() {
        View form = getLayoutInflater().inflate(R.layout.dialog_password, null);
        EditText etCurrent = form.findViewById(R.id.etCurrentPassword);
        EditText etNew = form.findViewById(R.id.etNewPassword);
        new AlertDialog.Builder(this)
                .setTitle("Change my password")
                .setView(form)
                .setPositiveButton("Save", (dialog, which) -> {
                    if (attempt(() -> repo.changeOwnPassword(currentUser,
                            etCurrent.getText().toString(), etNew.getText().toString()))) {
                        toast("Password changed");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Pop-up with one OK button. */
    protected void showMessage(String message) {
        showDialog(message, R.drawable.ic_info, false, "OK", null, null);
    }

    /** Pop-up that asks before something is removed or made permanent. */
    protected void confirm(String question, Runnable onYes) {
        showDialog(question, R.drawable.ic_close, true, "Yes", "Cancel", onYes);
    }

    /**
     * Shows the rounded pop-up from dialog_confirm.xml.
     * Pass null as negativeText to hide the second button.
     */
    protected void showDialog(String message, int icon, boolean danger, String positiveText,
                              String negativeText, Runnable onPositive) {
        View content = getLayoutInflater().inflate(R.layout.dialog_confirm, null);
        ImageView ivIcon = content.findViewById(R.id.dialogIcon);
        TextView tvMessage = content.findViewById(R.id.tvDialogMessage);
        Button btnPositive = content.findViewById(R.id.btnDialogPositive);
        Button btnNegative = content.findViewById(R.id.btnDialogNegative);

        ivIcon.setImageResource(icon);
        ivIcon.setBackgroundResource(danger
                ? R.drawable.bg_circle_danger : R.drawable.bg_circle_primary);
        tvMessage.setText(message);
        btnPositive.setText(positiveText);
        show(btnNegative, negativeText != null);
        btnNegative.setText(negativeText);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        if (dialog.getWindow() != null) {
            // Let the rounded white card of the layout be the only background.
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        btnPositive.setOnClickListener(v -> {
            dialog.dismiss();
            if (onPositive != null) {
                onPositive.run();
            }
        });
        btnNegative.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    protected void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    protected String textOf(EditText field) {
        return field.getText().toString().trim();
    }

    /** Number typed in a field, or the fallback when the field is empty or not a number. */
    protected int numberOf(EditText field, int fallback) {
        try {
            return Integer.parseInt(textOf(field));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    protected void show(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    protected void setSpinnerItems(Spinner spinner, String[] items) {
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    protected void onSpinnerChanged(Spinner spinner, Runnable action) {
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                action.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** Tapping the field opens a calendar; the chosen date is written as yyyy-MM-dd. */
    protected void attachDatePicker(EditText field) {
        field.setFocusable(false);
        field.setOnClickListener(v -> {
            Calendar now = Calendar.getInstance();
            new DatePickerDialog(this, (picker, year, month, day) ->
                    field.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)),
                    now.get(Calendar.YEAR), now.get(Calendar.MONTH),
                    now.get(Calendar.DAY_OF_MONTH)).show();
        });
        // Long-press clears the date.
        field.setOnLongClickListener(v -> {
            field.setText("");
            return true;
        });
    }

    /** Tapping the field opens a clock; the chosen time is written as HH:mm. */
    protected void attachTimePicker(EditText field) {
        field.setFocusable(false);
        field.setOnClickListener(v -> new TimePickerDialog(this, (picker, hour, minute) ->
                field.setText(String.format(Locale.US, "%02d:%02d", hour, minute)),
                16, 0, true).show());
        field.setOnLongClickListener(v -> {
            field.setText("");
            return true;
        });
    }
}
