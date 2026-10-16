package in.flavium.arena.auth;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * The permission self-test needs the development sample data, which a release build
 * does not contain. This empty version only keeps the release build compiling; the real
 * class is in app/src/debug.
 */
public final class PermissionSelfTest {

    public static class Result {
        public final String name;
        public final boolean passed;
        public final String detail;

        Result(String name, boolean passed, String detail) {
            this.name = name;
            this.passed = passed;
            this.detail = detail;
        }
    }

    private PermissionSelfTest() {
    }

    public static List<Result> run(Context context) {
        return new ArrayList<>();
    }
}
