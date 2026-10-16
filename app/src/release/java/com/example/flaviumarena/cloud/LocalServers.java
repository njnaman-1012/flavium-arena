package com.example.flaviumarena.cloud;

import android.content.Context;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

/**
 * Release builds: always the real Firebase project. (The debug twin can point a
 * development build at the Firebase Local Emulator Suite.)
 */
final class LocalServers {

    private LocalServers() {
    }

    static void use(Context context, FirebaseFirestore firestore, FirebaseAuth auth) {
        // Nothing: the real project from google-services.json.
    }
}
