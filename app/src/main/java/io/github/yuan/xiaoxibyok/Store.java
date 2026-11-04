package io.github.yuan.xiaoxibyok;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;
import org.json.*;

final class Store {
  private static SQLiteDatabase db;

  static synchronized SQLiteDatabase db() {
    if (db == null) {
      db = Host.app().openOrCreateDatabase("xiaoxi_enhance.db", 0, null);
      db.execSQL(
          "CREATE TABLE IF NOT EXISTS documents(kind TEXT,id TEXT,body TEXT,PRIMARY KEY(kind,id))");
      db.execSQL(
          "CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY,session TEXT,kind TEXT,time"
              + " INTEGER,body TEXT)");
      db.execSQL("CREATE INDEX IF NOT EXISTS event_session_time ON events(session,time)");
    }
    return db;
  }

  static synchronized JSONObject get(String kind, String id) {
    try (Cursor c =
        db().rawQuery(
                "SELECT body FROM documents WHERE kind=? AND id=?", new String[] {kind, id})) {
      return c.moveToFirst() ? Json.obj(c.getString(0)) : new JSONObject();
    }
  }

  static synchronized void save(String kind, String id, JSONObject body) {
    ContentValues v = new ContentValues();
    v.put("kind", kind);
    v.put("id", id);
    v.put("body", body.toString());
    db().insertWithOnConflict("documents", null, v, SQLiteDatabase.CONFLICT_REPLACE);
  }

  static synchronized void delete(String kind, String id) {
    db().delete("documents", "kind=? AND id=?", new String[] {kind, id});
  }

  static synchronized JSONArray list(String kind) {
    JSONArray a = new JSONArray();
    try (Cursor c =
        db().rawQuery(
                "SELECT id,body FROM documents WHERE kind=? ORDER BY rowid", new String[] {kind})) {
      while (c.moveToNext()) a.put(Json.put(Json.obj(c.getString(1)), "id", c.getString(0)));
    }
    return a;
  }

  static void event(String session, String kind, JSONObject body) {
    event(UUID.randomUUID().toString(), session, kind, System.currentTimeMillis(), body);
  }

  static synchronized void event(
      String id, String session, String kind, long time, JSONObject body) {
    ContentValues v = new ContentValues();
    v.put("id", id);
    v.put("session", session);
    v.put("kind", kind);
    v.put("time", time);
    v.put("body", body.toString());
    db().insertWithOnConflict("events", null, v, SQLiteDatabase.CONFLICT_REPLACE);
  }

  static synchronized JSONArray events(String session, int limit, int offset, String filter) {
    JSONArray a = new JSONArray();
    String where = "session=?";
    List<String> args = new ArrayList<>();
    args.add(session);
    if (!filter.isEmpty()) {
      where += " AND (kind LIKE ? OR body LIKE ?)";
      args.add("%" + filter + "%");
      args.add("%" + filter + "%");
    }
    try (Cursor c =
        db().rawQuery(
                "SELECT id,kind,time,body FROM events WHERE "
                    + where
                    + " ORDER BY time DESC LIMIT "
                    + limit
                    + " OFFSET "
                    + offset,
                args.toArray(new String[0]))) {
      while (c.moveToNext())
        a.put(
            Json.o(
                "id",
                c.getString(0),
                "kind",
                c.getString(1),
                "time",
                c.getLong(2),
                "data",
                Json.obj(c.getString(3))));
    }
    return a;
  }

  static synchronized JSONArray requests(String requestId) {
    JSONArray result = new JSONArray();
    if (requestId.isEmpty()) return result;
    try (Cursor c =
        db().rawQuery(
                "SELECT body FROM events WHERE kind='llm.request' AND body LIKE ? ORDER BY time"
                    + " DESC",
                new String[] {"%" + JSONObject.quote(requestId) + "%"})) {
      while (c.moveToNext()) {
        JSONObject data = Json.obj(c.getString(0));
        if (requestId.equals(data.optString("requestId"))) result.put(data);
      }
    }
    return result;
  }

  static JSONObject settings(String session) {
    return get("session", session);
  }

  static void setting(String session, String key, Object value) {
    save("session", session, Json.put(settings(session), key, value));
  }
}
