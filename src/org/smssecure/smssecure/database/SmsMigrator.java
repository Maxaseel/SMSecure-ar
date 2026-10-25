/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.smssecure.smssecure.database;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.util.Log;

import org.smssecure.smssecure.crypto.MasterCipher;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.RecipientFormattingException;
import org.smssecure.smssecure.recipients.Recipients;
import org.whispersystems.libsignal.InvalidMessageException;

import java.util.ArrayList;
import java.util.StringTokenizer;

public class SmsMigrator {

  private static final String TAG = SmsMigrator.class.getSimpleName();

  private static void addEncryptedStringToStatement(Context context, SQLiteStatement statement,
                                                    Cursor cursor, MasterSecret masterSecret,
                                                    int index, String key)
  {
    int columnIndex = cursor.getColumnIndexOrThrow(key);

    if (cursor.isNull(columnIndex)) {
      statement.bindNull(index);
    } else {
      statement.bindString(index, encrypt(masterSecret, cursor.getString(columnIndex)));
    }
  }

  private static void addStringToStatement(SQLiteStatement statement, Cursor cursor,
                                           int index, String key)
  {
    int columnIndex = cursor.getColumnIndexOrThrow(key);

    if (cursor.isNull(columnIndex)) {
      statement.bindNull(index);
    } else {
      statement.bindString(index, cursor.getString(columnIndex));
    }
  }

  private static void addIntToStatement(SQLiteStatement statement, Cursor cursor,
                                        int index, String key)
  {
    int columnIndex = cursor.getColumnIndexOrThrow(key);

    if (cursor.isNull(columnIndex)) {
      statement.bindNull(index);
    } else {
      statement.bindLong(index, cursor.getLong(columnIndex));
    }
  }

  private static void addTranslatedTypeToStatement(SQLiteStatement statement, Cursor cursor,
                                                   int index, String key)
  {
    int columnIndex = cursor.getColumnIndexOrThrow(key);

    if (cursor.isNull(columnIndex)) {
      statement.bindLong(index, SmsDatabase.Types.BASE_INBOX_TYPE | SmsDatabase.Types.ENCRYPTION_SYMMETRIC_BIT);
    } else {
      long theirType = cursor.getLong(columnIndex);
      statement.bindLong(index, SmsDatabase.Types.translateFromSystemBaseType(theirType) | SmsDatabase.Types.ENCRYPTION_SYMMETRIC_BIT);
    }
  }

  private static boolean isAppropriateTypeForMigration(Cursor cursor, int columnIndex) {
    long systemType = cursor.getLong(columnIndex);
    long ourType    = SmsDatabase.Types.translateFromSystemBaseType(systemType);

    return ourType == MmsSmsColumns.Types.BASE_INBOX_TYPE ||
           ourType == MmsSmsColumns.Types.BASE_SENT_TYPE ||
           ourType == MmsSmsColumns.Types.BASE_SENT_FAILED_TYPE;
  }

  private static void getContentValuesForRow(Context context, MasterSecret masterSecret,
                                             Cursor cursor, long threadId,
                                             SQLiteStatement statement)
  {
    addStringToStatement(statement, cursor, 1, SmsDatabase.ADDRESS);
    addIntToStatement(statement, cursor, 2, SmsDatabase.PERSON);
    addIntToStatement(statement, cursor, 3, SmsDatabase.DATE_RECEIVED);
    addIntToStatement(statement, cursor, 4, SmsDatabase.DATE_SENT);
    addIntToStatement(statement, cursor, 5, SmsDatabase.PROTOCOL);
    addIntToStatement(statement, cursor, 6, SmsDatabase.READ);
    addIntToStatement(statement, cursor, 7, SmsDatabase.STATUS);
    addTranslatedTypeToStatement(statement, cursor, 8, SmsDatabase.TYPE);
    addIntToStatement(statement, cursor, 9, SmsDatabase.REPLY_PATH_PRESENT);
    addStringToStatement(statement, cursor, 10, SmsDatabase.SUBJECT);
    addEncryptedStringToStatement(context, statement, cursor, masterSecret, 11, SmsDatabase.BODY);
    addStringToStatement(statement, cursor, 12, SmsDatabase.SERVICE_CENTER);

    statement.bindLong(13, threadId);
  }

  private static String getTheirCanonicalAddress(Context context, String theirRecipientId) {
    Uri uri       = Uri.parse("content://mms-sms/canonical-address/" + theirRecipientId);
    Cursor cursor = null;

    try {
      cursor = context.getContentResolver().query(uri, null, null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getString(0);
      } else {
        return null;
      }
    } catch (IllegalStateException iae) {
      Log.w("SmsMigrator", iae);
      return null;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  private static Recipients getOurRecipients(Context context, String theirRecipients) {
    StringTokenizer tokenizer = new StringTokenizer(theirRecipients.trim(), " ");
    StringBuilder sb          = new StringBuilder();

    while (tokenizer.hasMoreTokens()) {
      String theirRecipientId = tokenizer.nextToken();
      String address          = getTheirCanonicalAddress(context, theirRecipientId);

      if (address == null)
        continue;

      if (sb.length() != 0)
        sb.append(',');

      sb.append(address);
    }

    if (sb.length() == 0) return null;
    else                  return RecipientFactory.getRecipientsFromString(context, sb.toString(), true);
  }

  private static String encrypt(MasterSecret masterSecret, String body)
  {
    MasterCipher masterCipher = new MasterCipher(masterSecret);
    return masterCipher.encryptBody(body);
  }

  private static long findExistingSms(SQLiteDatabase database, Cursor systemCursor,
                                      long threadId, MasterSecret masterSecret)
  {
    String address = systemCursor.getString(systemCursor.getColumnIndexOrThrow(SmsDatabase.ADDRESS));
    String date    = Long.toString(systemCursor.getLong(systemCursor.getColumnIndexOrThrow(SmsDatabase.DATE_RECEIVED)));
    String body    = systemCursor.getString(systemCursor.getColumnIndexOrThrow(SmsDatabase.BODY));
    String selection = SmsDatabase.THREAD_ID + " = ? AND " + SmsDatabase.DATE_RECEIVED + " = ? AND ";
    String[] selectionArgs;

    if (address == null) {
      selection += SmsDatabase.ADDRESS + " IS NULL";
      selectionArgs = new String[]{Long.toString(threadId), date};
    } else {
      selection += SmsDatabase.ADDRESS + " = ?";
      selectionArgs = new String[]{Long.toString(threadId), date, address};
    }

    Cursor existing = null;
    try {
      existing = database.query(SmsDatabase.TABLE_NAME,
          new String[]{SmsDatabase.ID, SmsDatabase.BODY}, selection, selectionArgs,
          null, null, null);

      while (existing != null && existing.moveToNext()) {
        String storedBody = existing.getString(existing.getColumnIndexOrThrow(SmsDatabase.BODY));
        if (sameBody(masterSecret, storedBody, body)) {
          return existing.getLong(existing.getColumnIndexOrThrow(SmsDatabase.ID));
        }
      }
    } finally {
      if (existing != null) existing.close();
    }

    return -1;
  }

  private static boolean sameBody(MasterSecret masterSecret, String storedBody, String systemBody) {
    if (storedBody == null || systemBody == null) return storedBody == null && systemBody == null;
    if (storedBody.equals(systemBody)) return true;
    if (masterSecret == null) return false;

    try {
      return new MasterCipher(masterSecret).decryptBody(storedBody).equals(systemBody);
    } catch (InvalidMessageException e) {
      return false;
    }
  }

  private static int migrateConversation(Context context, MasterSecret masterSecret,
                                          SmsMigrationProgressListener listener,
                                          ProgressDescription progress,
                                          long theirThreadId, Recipients ourRecipients,
                                          long startDate, long endDate)
  {
    Cursor cursor = null;

    try {
      Uri uri = Uri.parse("content://sms/conversations/" + theirThreadId);
      StringBuilder selection = new StringBuilder();
      ArrayList<String> selectionArgs = new ArrayList<>();

      if (startDate != Long.MIN_VALUE) {
        selection.append(SmsDatabase.DATE_RECEIVED).append(" >= ?");
        selectionArgs.add(Long.toString(startDate));
      }

      if (endDate != Long.MAX_VALUE) {
        if (selection.length() > 0) selection.append(" AND ");
        selection.append(SmsDatabase.DATE_RECEIVED).append(" <= ?");
        selectionArgs.add(Long.toString(endDate));
      }

      try {
        cursor = context.getContentResolver().query(uri, null,
                                                   selection.length() == 0 ? null : selection.toString(),
                                                   selectionArgs.isEmpty() ? null : selectionArgs.toArray(new String[selectionArgs.size()]),
                                                   null);
      } catch (SQLiteException e) {
        /// Work around for weird sony-specific (?) bug: #4309
        Log.w(TAG, e);
        return 0;
      }

      if (cursor == null || cursor.getCount() == 0) return 0;

      int dateColumn = cursor.getColumnIndexOrThrow(SmsDatabase.DATE_RECEIVED);
      boolean hasMessageInDateRange = false;
      while (cursor.moveToNext()) {
        long messageDate = cursor.getLong(dateColumn);
        if ((startDate == Long.MIN_VALUE || messageDate >= startDate) &&
            (endDate == Long.MAX_VALUE || messageDate <= endDate))
        {
          hasMessageInDateRange = true;
          break;
        }
      }

      if (!hasMessageInDateRange) return 0;
      cursor.moveToPosition(-1);

      long ourThreadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(ourRecipients);
      SmsDatabase ourSmsDatabase = DatabaseFactory.getSmsDatabase(context);
      SystemMessageSyncDatabase syncDatabase = DatabaseFactory.getSystemMessageSyncDatabase(context);
      int imported = 0;

      SQLiteDatabase transaction = ourSmsDatabase.beginTransaction();
      SQLiteStatement statement  = ourSmsDatabase.createInsertStatement(transaction);
      int systemIdColumn = cursor.getColumnIndexOrThrow(SmsDatabase.ID);

      try {
        while (cursor != null && cursor.moveToNext()) {
          int typeColumn = cursor.getColumnIndex(SmsDatabase.TYPE);
          long messageDate = cursor.getLong(dateColumn);
          long systemId = cursor.getLong(systemIdColumn);
          boolean inDateRange = (startDate == Long.MIN_VALUE || messageDate >= startDate) &&
                                (endDate == Long.MAX_VALUE || messageDate <= endDate);

          if (inDateRange && (cursor.isNull(typeColumn) || isAppropriateTypeForMigration(cursor, typeColumn))) {
            if (!syncDatabase.hasImported(transaction, SystemMessageSyncDatabase.SMS, systemId)) {
              long existingId = findExistingSms(transaction, cursor, ourThreadId, masterSecret);
              if (existingId >= 0) {
                syncDatabase.markImported(transaction, SystemMessageSyncDatabase.SMS, systemId, existingId);
              } else {
                getContentValuesForRow(context, masterSecret, cursor, ourThreadId, statement);
                long insertedId = statement.executeInsert();
                if (insertedId >= 0) {
                  syncDatabase.markImported(transaction, SystemMessageSyncDatabase.SMS, systemId, insertedId);
                  imported++;
                }
              }
            }
          }

          listener.progressUpdate(new ProgressDescription(progress, cursor.getCount(), cursor.getPosition()));
        }
        transaction.setTransactionSuccessful();
      } finally {
        statement.close();
        transaction.endTransaction();
      }

      DatabaseFactory.getThreadDatabase(context).update(ourThreadId, true);
      DatabaseFactory.getThreadDatabase(context).notifyConversationListeners(ourThreadId);
      return imported;

    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public static int migrateDatabase(Context context,
                                     MasterSecret masterSecret,
                                     SmsMigrationProgressListener listener)
  {
    return migrateDatabase(context, masterSecret, listener, Long.MIN_VALUE, Long.MAX_VALUE);
  }

  public static int migrateDatabase(Context context,
                                     MasterSecret masterSecret,
                                     SmsMigrationProgressListener listener,
                                     long startDate,
                                     long endDate)
  {
    if (startDate > endDate) throw new IllegalArgumentException("Import start date must not be after end date");

    int imported = 0;

//    if (context.getSharedPreferences("SecureSMS", Context.MODE_PRIVATE).getBoolean("migrated", false))
//      return;

    Cursor cursor = null;

    try {
      Uri threadListUri = Uri.parse("content://mms-sms/conversations?simple=true");
      cursor            = context.getContentResolver().query(threadListUri, null, null, null, "date ASC");

      while (cursor != null && cursor.moveToNext()) {
        long   theirThreadId         = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
        String theirRecipients       = cursor.getString(cursor.getColumnIndexOrThrow("recipient_ids"));
        Recipients ourRecipients     = getOurRecipients(context, theirRecipients);
        ProgressDescription progress = new ProgressDescription(cursor.getCount(), cursor.getPosition(), 100, 0);

        if (ourRecipients != null) {
          imported += migrateConversation(context, masterSecret,
                                          listener, progress,
                                          theirThreadId, ourRecipients, startDate, endDate);
        }

        progress.incrementPrimaryComplete();
        listener.progressUpdate(progress);
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }

    context.getSharedPreferences("SecureSMS", Context.MODE_PRIVATE).edit()
      .putBoolean("migrated", true).apply();
    return imported;
  }

  public interface SmsMigrationProgressListener {
    public void progressUpdate(ProgressDescription description);
  }

  public static class ProgressDescription {
    public final int primaryTotal;
    public       int primaryComplete;
    public final int secondaryTotal;
    public final int secondaryComplete;

    public ProgressDescription(int primaryTotal, int primaryComplete,
                               int secondaryTotal, int secondaryComplete)
    {
      this.primaryTotal      = primaryTotal;
      this.primaryComplete   = primaryComplete;
      this.secondaryTotal    = secondaryTotal;
      this.secondaryComplete = secondaryComplete;
    }

    public ProgressDescription(ProgressDescription that, int secondaryTotal, int secondaryComplete) {
      this.primaryComplete   = that.primaryComplete;
      this.primaryTotal      = that.primaryTotal;
      this.secondaryComplete = secondaryComplete;
      this.secondaryTotal    = secondaryTotal;
    }

    public void incrementPrimaryComplete() {
      primaryComplete += 1;
    }
  }

}
