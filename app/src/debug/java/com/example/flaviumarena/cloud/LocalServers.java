package com.example.flaviumarena.cloud;

import android.content.Context;

import com.example.flaviumarena.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

/**
 * DEBUG BUILDS ONLY. Points Firebase at the Firebase Local Emulator Suite on the
 * development computer when firebase_emulator_host is set in
 * app/src/debug/res/values/config.xml, so testing never touches the real project.
 *
 * Release builds use the empty twin in app/src/release: they always talk to the real
 * Firebase project and contain no code that could point them anywhere else.
 */
final class LocalServers {

    private LocalServers() {
    }

    static void use(Context context, FirebaseFirestore firestore, FirebaseAuth auth) {
        String host = context.getString(R.string.firebase_emulator_host).trim();
        if (host.isEmpty()) {
            return;
        }
        if (firestore != null) {
            firestore.useEmulator(host, 8080);
        }
        if (auth != null) {
            auth.useEmulator(host, 9099);
        }
    }
}
