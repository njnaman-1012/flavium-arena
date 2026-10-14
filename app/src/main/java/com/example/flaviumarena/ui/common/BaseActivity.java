package com.example.flaviumarena.ui.common;

import android.app.DatePickerDialog;
import android.content.ActivityNotFoundException;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.database.sqlite.SQLiteException;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.flaviumarena.cloud.Cloud;
import com.example.flaviumarena.R;
import com.example.flaviumarena.ui.main.MainActivity;
import com.example.flaviumarena.auth.AccessControl;
import com.example.flaviumarena.auth.Session;
import com.example.flaviumarena.data.ArenaException;
import com.example.flaviumarena.data.ArenaRepository;
import com.example.flaviumarena.data.RepositoryProvider;
import com.example.flaviumarena.export.ExportFile;
import com.example.flaviumarena.export.ExportService;
import com.example.flaviumarena.model.User;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.Locale;

/**
 * Parent of every screen. It provides the repository, the logged-in user and a few
 * small helpers, so the individual activities stay short.
 */
public abstract class BaseActivity extends AppCompatActivity implements Cloud.Observer {

    private static final String TAG = "FlaviumArena";
    /** One background thread for the whole app: work is done in the order it was asked. */
    private static final Executor BACKGROUND = Executors.newSingleThreadExecutor();

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

    // ----- Downloads -----
    private static final int MENU_DOWNLOAD = 1;
    /** The file that is waiting for the person to choose where to save it. */
    private ExportFile pendingExport;
    private ActivityResultLauncher<String> saveCsv;
    private ActivityResultLauncher<String> savePdf;

    /** Makes one download. The export service only hands out what this user may see. */
    protected interface Download {
        ExportFile build(ExportService exports);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        // Android's own "save as" screen: the person chooses the folder, so the app
        // needs no storage permission.
        saveCsv = registerForActivityResult(
                new ActivityResultContracts.CreateDocument(ExportFile.TYPE_CSV), this::saveExport);
        savePdf = registerForActivityResult(
                new ActivityResultContracts.CreateDocument(ExportFile.TYPE_PDF), this::saveExport);
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

    /**
     * The logged-in user as they count inside one sport. A Coach manages only the
     * sports assigned to them, so screens ask "may this user do it in this sport?".
     * This only decides which buttons are shown; the repository decides again.
     */
    protected User userIn(String sportId) {
        return AccessControl.inSport(currentUser, sportId);
    }

    /**
     * Shows the current data. A screen puts its loading here; it runs when the screen
     * opens (from onResume) and again when shared data changes while it is on screen.
     */
    protected void refreshScreen() {
    }

    @Override
    protected void onStart() {
        super.onStart();
        Cloud cloud = Cloud.get(this);
        cloud.addObserver(this);
        cloud.onForeground();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Cloud cloud = Cloud.get(this);
        cloud.removeObserver(this);
        cloud.onBackground();
    }

    // ----- A record being edited on this screen -----

    private String watchedTable;
    private String watchedKey;
    private long watchedRevision;
    private boolean toldChangedElsewhere;

    /**
     * An edit screen calls this for the record it has opened. If another device changes
     * that record while the screen is open, the person is told, and what they typed is
     * left as it is: an edit screen reads its record once and is not reloaded.
     */
    protected void watchRecord(String table, String key) {
        watchedTable = table;
        watchedKey = key;
        watchedRevision = key == null ? 0 : Cloud.get(this).lastReceived(table, key);
        toldChangedElsewhere = false;
    }

    /** True when another device changed the watched record after this screen opened it. */
    protected boolean watchedRecordChanged() {
        return watchedTable != null && watchedKey != null
                && Cloud.get(this).lastReceived(watchedTable, watchedKey) != watchedRevision;
    }

    /**
     * Saves, but first asks when another device changed the record meanwhile, so a
     * newer version is never replaced without the person deciding it.
     */
    protected void saveCarefully(Runnable save) {
        if (!watchedRecordChanged()) {
            save.run();
            return;
        }
        showDialog("This record was changed on another device after you opened it. "
                        + "Save your version anyway? It replaces the other change.",
                R.drawable.ic_info, true, "Save mine", "Cancel", save);
    }

    /**
     * Shared data, or the connection, changed while this screen is open: show the
     * latest, and anything the person should be told (a conflict, a refusal).
     */
    @Override
    public void onCloudChanged() {
        if (databaseFailed || isFinishing() || isDestroyed()) {
            return;
        }
        Cloud cloud = Cloud.get(this);
        if (cloud.takeSessionTakenOver() && currentUser != null) {
            Session.logout(this, repo, currentUser);
            cloud.signOut();
            showMessage("You have been logged out because this account signed in on "
                    + "another device.");
        }
        currentUser = Session.currentUser(this, repo);
        refreshScreen();
        List<String> notices = new ArrayList<>(cloud.takeNotices());
        if (!toldChangedElsewhere && watchedRecordChanged()) {
            toldChangedElsewhere = true;
            notices.add("This record was just changed on another device. What you typed "
                    + "here is kept. Go back without saving to see the new version.");
        }
        if (!notices.isEmpty()) {
            showMessage(TextUtils.join("\n\n", notices));
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
    public void openHome() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // ----- Downloads -----

    /**
     * Screens that show records override these two methods: the names of the downloads
     * they offer, and how each one is made. A screen with at least one download gets a
     * download button in its title bar.
     */
    protected String[] downloadNames() {
        return new String[0];
    }

    protected Download downloadAt(int position) {
        return null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (!databaseFailed && downloadNames().length > 0) {
            menu.add(Menu.NONE, MENU_DOWNLOAD, Menu.NONE, "Download")
                    .setIcon(R.drawable.ic_download)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() != MENU_DOWNLOAD) {
            return super.onOptionsItemSelected(item);
        }
        String[] names = downloadNames();
        if (names.length == 1) {
            download(downloadAt(0));
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Download")
                    .setItems(names, (dialog, which) -> download(downloadAt(which)))
                    .setNegativeButton("Cancel", null)
                    .show();
        }
        return true;
    }

    /**
     * Builds a file and asks where to save it. A report reads many records, so it is
     * built in the background while the screen shows that it is working.
     */
    protected void download(Download download) {
        if (download == null) {
            return;
        }
        ExportService exports = new ExportService(repo, currentUser);
        runBusy("Preparing the file...", () -> download.build(exports), file -> {
            pendingExport = file;
            try {
                (file.isPdf() ? savePdf : saveCsv).launch(file.fileName);
            } catch (ActivityNotFoundException e) {
                pendingExport = null;
                showMessage("This device has no app for choosing where to save a file.");
            }
        });
    }

    // ----- Work that takes a moment -----

    /** Something that is worked out away from the screen and may be refused. */
    protected interface Work<T> {
        T run();
    }

    /** What to do with the result, back on the screen. */
    protected interface OnDone<T> {
        void done(T result);
    }

    /**
     * Runs work in the background and shows a "please wait" message meanwhile, so the
     * screen never freezes. When it finishes, onDone runs on the main thread. A refusal
     * or a database error is shown as a message, exactly as attempt() does, and the
     * result is dropped if the screen was closed in the meantime.
     *
     * This is the one way a screen should start slow work, which is what every call
     * becomes once the data is online.
     */
    protected <T> void runBusy(String message, Work<T> work, OnDone<T> onDone) {
        AlertDialog busy = new AlertDialog.Builder(this)
                .setMessage(message)
                .setCancelable(false)
                .create();
        busy.show();
        BACKGROUND.execute(() -> {
            T result = null;
            String problem = null;
            try {
                result = work.run();
            } catch (ArenaException e) {
                problem = e.getMessage();
            } catch (SQLiteException e) {
                Log.e(TAG, "Database error", e);
                problem = "The database could not complete this, and nothing was changed. "
                        + "Close the app and try again.\n\nDetails: " + e.getMessage();
            } catch (RuntimeException e) {
                Log.e(TAG, "Unexpected error", e);
                problem = "Something went wrong and this could not be completed.";
            }
            T finished = result;
            String failure = problem;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                busy.dismiss();
                if (failure != null) {
                    showMessage(failure);
                } else {
                    onDone.done(finished);
                }
            });
        });
    }

    /** Runs when the person has chosen a place (or cancelled). */
    private void saveExport(Uri destination) {
        ExportFile file = pendingExport;
        pendingExport = null;
        if (destination == null || file == null) {
            toast("Download cancelled");
            return;
        }
        try (OutputStream out = getContentResolver().openOutputStream(destination)) {
            if (out == null) {
                throw new IOException("the chosen place cannot be written to");
            }
            out.write(file.content);
            toast("Saved " + file.fileName);
        } catch (IOException | SecurityException e) {
            showMessage("The file could not be saved: " + e.getMessage());
        }
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
            Cloud.get(this).requestSync();
            return true;
        } catch (ArenaException e) {
            // Refused for a reason the person can act on: wrong input, no permission,
            // or someone else changed the record first.
            showMessage(e.getMessage());
            return false;
        } catch (SQLiteException e) {
            // The database itself failed (storage full, damaged file). A change runs in
            // a transaction, so nothing was half-saved.
            Log.e(TAG, "Database error", e);
            showMessage("The database could not complete this, and nothing was changed. "
                    + "Close the app and try again.\n\nDetails: " + e.getMessage());
            return false;
        }
    }

    /** Lets the logged-in organiser change their own password. */
    public void showChangePasswordDialog() {
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
    public void showMessage(String message) {
        showDialog(message, R.drawable.ic_info, false, "OK", null, null);
    }

    /** Pop-up that asks before something is removed or made permanent. */
    public void confirm(String question, Runnable onYes) {
        showDialog(question, R.drawable.ic_close, true, "Yes", "Cancel", onYes);
    }

    /**
     * Shows the rounded pop-up from dialog_confirm.xml.
     * Pass null as negativeText to hide the second button.
     */
    public void showDialog(String message, int icon, boolean danger, String positiveText,
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

    /** Puts text into a TextView of this screen. */
    protected void setText(int viewId, String text) {
        ((TextView) findViewById(viewId)).setText(text);
    }

    protected void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    public String textOf(EditText field) {
        return field.getText().toString().trim();
    }

    /** Number typed in a field, or the fallback when the field is empty or not a number. */
    public int numberOf(EditText field, int fallback) {
        try {
            return Integer.parseInt(textOf(field));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    protected void show(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void setSpinnerItems(Spinner spinner, String[] items) {
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
