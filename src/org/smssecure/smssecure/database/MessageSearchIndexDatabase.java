package org.smssecure.smssecure.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.support.annotation.Nullable;

public class MessageSearchIndexDatabase extends Database {

  public static final String TABLE_NAME = "message_search_index";

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + " (" +
      "thread_id INTEGER PRIMARY KEY, " +
      "payload BLOB, " +
      "message_count INTEGER NOT NULL DEFAULT 0, " +
      "thread_date INTEGER NOT NULL DEFAULT 0, " +
      "indexed_at INTEGER NOT NULL DEFAULT 0, " +
      "generation INTEGER NOT NULL DEFAULT 0, " +
      "ready INTEGER NOT NULL DEFAULT 0);";

  public MessageSearchIndexDatabase(Context context, SQLiteOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  public long beginBuild(long threadId) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    ContentValues initial = new ContentValues(2);
    initial.put("thread_id", threadId);
    initial.put("ready", 0);
    db.insertWithOnConflict(TABLE_NAME, null, initial, SQLiteDatabase.CONFLICT_IGNORE);
    db.execSQL("UPDATE " + TABLE_NAME + " SET generation = generation + 1, ready = 0 WHERE thread_id = ?",
               new Object[] {threadId});
    return getGeneration(db, threadId);
  }

  public boolean storeIfGenerationMatches(long threadId, long generation, byte[] encryptedPayload,
                                         int messageCount, long threadDate)
  {
    ContentValues values = new ContentValues(5);
    values.put("payload", encryptedPayload);
    values.put("message_count", messageCount);
    values.put("thread_date", threadDate);
    values.put("indexed_at", System.currentTimeMillis());
    values.put("ready", 1);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    return db.update(TABLE_NAME, values, "thread_id = ? AND generation = ?",
                     new String[] {String.valueOf(threadId), String.valueOf(generation)}) == 1;
  }

  public void markDirty(long threadId) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.execSQL("UPDATE " + TABLE_NAME + " SET generation = generation + 1, ready = 0 WHERE thread_id = ?",
               new Object[] {threadId});
  }

  public void deleteIndex(long threadId) {
    databaseHelper.getWritableDatabase().delete(TABLE_NAME, "thread_id = ?", new String[] {String.valueOf(threadId)});
  }

  public int deleteAllIndexes() {
    return databaseHelper.getWritableDatabase().delete(TABLE_NAME, null, null);
  }

  public int getReadyIndexCount() {
    Cursor cursor = null;
    try {
      cursor = databaseHelper.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE_NAME + " WHERE ready = 1", null);
      return cursor.moveToFirst() ? cursor.getInt(0) : 0;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public @Nullable IndexRecord getReadyIndex(long threadId) {
    Cursor cursor = null;
    try {
      cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME,
          new String[] {"payload", "message_count", "thread_date"},
          "thread_id = ? AND ready = 1", new String[] {String.valueOf(threadId)}, null, null, null);

      if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
        return new IndexRecord(cursor.getBlob(0), cursor.getInt(1), cursor.getLong(2));
      }
      return null;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public long getThreadGeneration(long threadId) {
    return getGeneration(databaseHelper.getReadableDatabase(), threadId);
  }

  private long getGeneration(SQLiteDatabase db, long threadId) {
    Cursor cursor = null;
    try {
      cursor = db.query(TABLE_NAME, new String[] {"generation"}, "thread_id = ?",
                        new String[] {String.valueOf(threadId)}, null, null, null);
      return cursor != null && cursor.moveToFirst() ? cursor.getLong(0) : -1;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public static class IndexRecord {
    public final byte[] encryptedPayload;
    public final int messageCount;
    public final long threadDate;

    private IndexRecord(byte[] encryptedPayload, int messageCount, long threadDate) {
      this.encryptedPayload = encryptedPayload;
      this.messageCount = messageCount;
      this.threadDate = threadDate;
    }
  }
}
