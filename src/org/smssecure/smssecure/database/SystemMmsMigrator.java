package org.smssecure.smssecure.database;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;

import com.google.android.mms.pdu_alt.PduHeaders;

import org.smssecure.smssecure.attachments.Attachment;
import org.smssecure.smssecure.attachments.UriAttachment;
import org.smssecure.smssecure.crypto.MasterSecret;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/** Imports completed MMS messages from Android's telephony provider into the encrypted database. */
public final class SystemMmsMigrator {

  private static final String TAG = SystemMmsMigrator.class.getSimpleName();
  private static final Uri MMS_URI = Uri.parse("content://mms");
  private static final Uri PART_URI = Uri.parse("content://mms/part");
  private static final Uri ADDRESS_URI = Uri.parse("content://mms");

  private static final int SYSTEM_BOX_INBOX = 1;
  private static final int SYSTEM_BOX_SENT = 2;

  private SystemMmsMigrator() {}

  public interface ProgressListener {
    void onProgress(int complete, int total);
  }

  public static int synchronize(Context context, MasterSecret masterSecret, ProgressListener listener) {
    ContentResolver resolver = context.getContentResolver();
    SystemMessageSyncDatabase syncDatabase = DatabaseFactory.getSystemMessageSyncDatabase(context);
    Cursor messages = null;
    int imported = 0;

    try {
      messages = resolver.query(MMS_URI, null, null, null, "date ASC");
      if (messages == null) return 0;

      int idColumn = column(messages, "_id");
      int total = messages.getCount();
      int complete = 0;

      while (messages.moveToNext()) {
        long systemId = messages.getLong(idColumn);
        try {
          if (!syncDatabase.hasImported(SystemMessageSyncDatabase.MMS, systemId)) {
            int messageType = getInt(messages, "m_type", -1);
            int messageBox = getInt(messages, "msg_box", -1);

            // A notification-ind only contains a download pointer, not the actual MMS content.
            if (messageType == PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF ||
                messageType == PduHeaders.MESSAGE_TYPE_SEND_REQ) {
              boolean incoming = messageBox == SYSTEM_BOX_INBOX;
              if (incoming || messageBox == SYSTEM_BOX_SENT || messageBox == 3 || messageBox == 4 || messageBox == 5 || messageBox == 6) {
                MmsAddresses addresses = readAddresses(resolver, systemId);
                if (!incoming && addresses.getTo().isEmpty() && addresses.getCc().isEmpty() && addresses.getBcc().isEmpty()) {
                  throw new IllegalStateException("MMS has no recipient addresses");
                }
                if (incoming && addresses.getFrom() == null) {
                  throw new IllegalStateException("Incoming MMS has no sender address");
                }

                MessageParts parts = readParts(context, resolver, systemId);
                long date = toMillis(getLong(messages, "date", 0));
                long sentDate = toMillis(getLong(messages, "date_sent", 0));
                if (sentDate <= 0) sentDate = date;
                if (date <= 0) date = sentDate;
                boolean read = getInt(messages, "read", 1) != 0;
                int subscriptionId = getInt(messages, "sub_id", -1);
                String subject = getString(messages, "sub");

                Pair<Long, Long> result = DatabaseFactory.getMmsDatabase(context)
                    .insertImportedSystemMessage(masterSecret, addresses, parts.body, subject,
                        parts.attachments, sentDate, date, incoming, read, subscriptionId, messageBox);

                syncDatabase.markImported(SystemMessageSyncDatabase.MMS, systemId, result.first);
                imported++;
              }
            }
          }
        } catch (Exception e) {
          // Keep the item eligible for a later sync if provider data or an attachment was unavailable.
          Log.w(TAG, "Unable to synchronize system MMS id " + systemId, e);
        }

        complete++;
        if (listener != null) listener.onProgress(complete, total);
      }
    } finally {
      if (messages != null) messages.close();
    }

    return imported;
  }

  private static MmsAddresses readAddresses(ContentResolver resolver, long messageId) {
    Uri uri = Uri.withAppendedPath(ADDRESS_URI, messageId + "/addr");
    Cursor cursor = null;
    String from = null;
    List<String> to = new LinkedList<>();
    List<String> cc = new LinkedList<>();
    List<String> bcc = new LinkedList<>();

    try {
      cursor = resolver.query(uri, null, null, null, null);
      if (cursor == null) return new MmsAddresses(null, to, cc, bcc);

      int addressColumn = column(cursor, "address");
      int typeColumn = column(cursor, "type");
      if (addressColumn < 0 || typeColumn < 0) return new MmsAddresses(null, to, cc, bcc);

      while (cursor.moveToNext()) {
        String address = cursor.getString(addressColumn);
        if (address == null || address.trim().isEmpty() || "insert-address-token".equals(address)) continue;

        switch (cursor.getInt(typeColumn)) {
          case 137: from = address; break; // PduHeaders.FROM
          case 151: to.add(address); break; // PduHeaders.TO
          case 130: cc.add(address); break; // PduHeaders.CC
          case 129: bcc.add(address); break; // PduHeaders.BCC
          default: break;
        }
      }
    } finally {
      if (cursor != null) cursor.close();
    }

    return new MmsAddresses(from, to, cc, bcc);
  }

  private static MessageParts readParts(Context context, ContentResolver resolver, long messageId) throws Exception {
    Cursor cursor = null;
    StringBuilder body = new StringBuilder();
    List<Attachment> attachments = new ArrayList<>();

    try {
      cursor = resolver.query(PART_URI, null, "mid = ?", new String[]{Long.toString(messageId)}, "seq ASC");
      if (cursor == null) return new MessageParts(null, attachments);

      int idColumn = column(cursor, "_id");
      int typeColumn = column(cursor, "ct");
      int textColumn = column(cursor, "text");
      int sizeColumn = column(cursor, "_size");
      if (sizeColumn < 0) sizeColumn = column(cursor, "size");

      while (cursor.moveToNext()) {
        String contentType = typeColumn >= 0 ? cursor.getString(typeColumn) : null;
        if (contentType == null || contentType.trim().isEmpty()) contentType = "application/octet-stream";
        if (contentType.toLowerCase(java.util.Locale.US).contains("application/smil")) continue;

        long partId = cursor.getLong(idColumn);
        Uri partUri = Uri.withAppendedPath(PART_URI, Long.toString(partId));
        if ("text/plain".equalsIgnoreCase(contentType)) {
          String partText = textColumn >= 0 && !cursor.isNull(textColumn) ? cursor.getString(textColumn) : readText(resolver, partUri);
          if (partText != null) {
            if (body.length() > 0) body.append('\n');
            body.append(partText);
          }
        } else {
          long size = sizeColumn >= 0 && !cursor.isNull(sizeColumn) ? cursor.getLong(sizeColumn) : 0;
          attachments.add(new UriAttachment(partUri, contentType,
              AttachmentDatabase.TRANSFER_PROGRESS_DONE, size));
        }
      }
    } finally {
      if (cursor != null) cursor.close();
    }

    return new MessageParts(body.length() == 0 ? null : body.toString(), attachments);
  }

  private static String readText(ContentResolver resolver, Uri uri) throws Exception {
    InputStream input = null;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try {
      input = resolver.openInputStream(uri);
      if (input == null) return null;
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
      return new String(output.toByteArray(), Charset.forName("UTF-8"));
    } finally {
      if (input != null) input.close();
      output.close();
    }
  }

  private static int column(Cursor cursor, String name) {
    return cursor.getColumnIndex(name);
  }

  private static String getString(Cursor cursor, String name) {
    int index = column(cursor, name);
    return index >= 0 && !cursor.isNull(index) ? cursor.getString(index) : null;
  }

  private static int getInt(Cursor cursor, String name, int defaultValue) {
    int index = column(cursor, name);
    return index >= 0 && !cursor.isNull(index) ? cursor.getInt(index) : defaultValue;
  }

  private static long getLong(Cursor cursor, String name, long defaultValue) {
    int index = column(cursor, name);
    return index >= 0 && !cursor.isNull(index) ? cursor.getLong(index) : defaultValue;
  }

  private static long toMillis(long timestamp) {
    if (timestamp <= 0) return timestamp;
    return timestamp < 100000000000L ? timestamp * 1000L : timestamp;
  }

  private static final class MessageParts {
    private final String body;
    private final List<Attachment> attachments;

    private MessageParts(String body, List<Attachment> attachments) {
      this.body = body;
      this.attachments = attachments;
    }
  }
}
