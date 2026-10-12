package com.example.flaviumarena.sync;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps a copy of the local database file before a device joins the shared festival,
 * because joining replaces the festival data on that device.
 */
public final class LocalBackup {

    private LocalBackup() {
    }

    /**
     * Copies the database to the app's own "backups" folder and returns the copy. The
     * copy is a normal SQLite file. It is made inside a transaction, so nothing can be
     * written to the database while the bytes are read.
     *
     * @throws SyncException when the copy could not be made; the caller must not go on
     */
    public static File copy(Context context, SQLiteOpenHelper helper) {
        String name = helper.getDatabaseName();
        if (name == null) {
            throw new SyncException("A temporary database has no file to copy.");
        }
        File folder = new File(context.getFilesDir(), "backups");
        if (!folder.exists() && !folder.mkdirs()) {
            throw new SyncException("The backup folder could not be created.");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File target = new File(folder, name.replace(".db", "") + "-" + stamp + ".db");

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try (InputStream in = new FileInputStream(context.getDatabasePath(name));
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (IOException e) {
            throw new SyncException("The local data could not be backed up: " + e.getMessage());
        } finally {
            db.endTransaction();
        }
        if (target.length() == 0) {
            throw new SyncException("The backup is empty; the local data was not replaced.");
        }
        return target;
    }
}
