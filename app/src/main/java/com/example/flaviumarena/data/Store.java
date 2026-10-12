package com.example.flaviumarena.data;

import android.database.Cursor;
import static com.example.flaviumarena.data.Sql.*;

/**
 * What the feature stores (MatchStore, EventStore) have in common: the database, and the
 * repository they belong to. A store asks the repository for anything outside its own
 * feature, for example who the acting user really is.
 */
abstract class Store {

    final SqliteArenaRepository repo;
    final DbHelper helper;

    Store(SqliteArenaRepository repo, DbHelper helper) {
        this.repo = repo;
        this.helper = helper;
    }

    Cursor query(String sql, String... args) {
        return helper.getReadableDatabase().rawQuery(sql, args);
    }
}
