/**
 * Copyright (C) 2026 SMSecure-ar contributors
 *
 * Licensed under the GNU General Public License, version 3 or later.
 */
package org.smssecure.smssecure.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class SystemMessageSyncDatabase extends Database {

  public static final String TABLE_NAME = "system_message_sync";
  public static final String TRANSPORT = "transport";
  public static final String SYSTEM_ID = "system_id";
  public static final String APP_ID = "app_id";

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + " (" +
      TRANSPORT + " TEXT NOT NULL, " +
      SYSTEM_ID + " INTEGER NOT NULL, " +
      APP_ID + " INTEGER NOT NULL, " +
      "PRIMARY KEY (" + TRANSPORT + ", " + SYSTEM_ID + "));";

  public static final String SMS = "sms";
  public static final String MMS = "mms";

  public SystemMessageSyncDatabase(Context context, SQLiteOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  public boolean hasImported(String transport, long systemId) {
    return hasImported(databaseHelper.getReadableDatabase(), transport, systemId);
  }

  public boolean hasImported(SQLiteDatabase database, String transport, long systemId) {
    Cursor cursor = null;
    try {
      cursor = database.query(TABLE_NAME, new String[]{APP_ID},
          TRANSPORT + " = ? AND " + SYSTEM_ID + " = ?",
          new String[]{transport, Long.toString(systemId)}, null, null, null);
      return cursor != null && cursor.moveToFirst();
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public void markImported(SQLiteDatabase database, String transport,
                           long systemId, long appId) {
    ContentValues values = new ContentValues(3);
    values.put(TRANSPORT, transport);
    values.put(SYSTEM_ID, systemId);
    values.put(APP_ID, appId);
    database.insertWithOnConflict(TABLE_NAME, null, values,
                                  SQLiteDatabase.CONFLICT_IGNORE);
  }

  public void markImported(String transport, long systemId, long appId) {
    markImported(databaseHelper.getWritableDatabase(), transport, systemId, appId);
  }
}
