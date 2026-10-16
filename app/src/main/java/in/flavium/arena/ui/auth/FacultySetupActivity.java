package in.flavium.arena.ui.auth;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import in.flavium.arena.R;
import in.flavium.arena.ui.common.BaseActivity;
import in.flavium.arena.auth.Session;
import in.flavium.arena.data.ValidationException;
import in.flavium.arena.model.User;

/**
 * One-time set-up of the first Faculty (Super Admin) account.
 *
 * The repository only creates the account when no Faculty account exists yet and the
 * email typed here equals the one in res/values/config.xml. Nothing on this screen can
 * be used to become Faculty in any other situation.
 *
 * Limitation of the local build: the app cannot send email, so it cannot prove that the
 * person typing owns the address. The online version does that with Firebase.
 */
public class FacultySetupActivity extends BaseActivity {

    private EditText etName, etEmail, etPassword, etPasswordAgain;
    private String authorisedEmail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_faculty_setup, "First Faculty account", true);

        etName = findViewById(R.id.etFacultyName);
        etEmail = findViewById(R.id.etFacultyEmail);
        etPassword = findViewById(R.id.etFacultyPassword);
        etPasswordAgain = findViewById(R.id.etFacultyPasswordAgain);
        authorisedEmail = getString(R.string.faculty_email).trim();

        TextView tvStatus = findViewById(R.id.tvSetupStatus);
        if (authorisedEmail.isEmpty()) {
            tvStatus.setText("This version of the app has no Faculty email, so the first "
                    + "Faculty account cannot be created on it. Ask the organisers for the "
                    + "version prepared for the festival.");
            findViewById(R.id.btnCreateFaculty).setEnabled(false);
        } else {
            tvStatus.setText("Only the festival's Faculty email can be used here. Type it "
                    + "below to continue.");
        }

        findViewById(R.id.btnCreateFaculty).setOnClickListener(v -> create());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshScreen();
    }

    /** Shows the current data. Runs when the screen opens and when shared data changes. */
    @Override
    protected void refreshScreen() {
        if (repo.hasFaculty()) {
            toast("A Faculty account already exists.");
            finish();
        }
    }

    private void create() {
        boolean ok = attempt(() -> {
            String password = etPassword.getText().toString();
            if (!password.equals(etPasswordAgain.getText().toString())) {
                throw new ValidationException("The two passwords are not the same.");
            }
            User faculty = repo.createFirstFaculty(textOf(etName), textOf(etEmail), password,
                    authorisedEmail);
            Session.login(this, faculty);
        });
        if (ok) {
            toast("Faculty account created");
            openHome();
        }
    }
}
