package org.smssecure.smssecure.util;

import org.smssecure.smssecure.crypto.MasterCipher;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.model.MessageRecord;
import org.smssecure.smssecure.protocol.AutoInitiate;

import org.whispersystems.libsignal.InvalidMessageException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A compact per-conversation snapshot. The serialized message text is compressed and
 * encrypted with the app's MasterSecret before it is written to the database.
 */
public final class PersistentMessageSearchIndex {

  private static final int MAGIC = 0x534D5349;
  private static final int VERSION = 1;
  private static final int MAX_ENTRIES = 1000000;
  private static final int MAX_STRING_BYTES = 4 * 1024 * 1024;
  private static final Charset UTF8 = Charset.forName("UTF-8");

  private PersistentMessageSearchIndex() {}

  public static String getSearchableText(MessageRecord record) {
    if (record.isGroupUpdate() || record.isGroupQuit() || record.isMmsNotification() ||
        record.isProcessedKeyExchange() || (record.isKeyExchange() && record.isOutgoing()))
    {
      return record.getDisplayBody().toString();
    }

    String body = record.getBody().getBody();
    return body == null ? "" : AutoInitiate.stripTag(body);
  }

  public static byte[] encrypt(List<Entry> entries, MasterSecret masterSecret) throws IOException {
    ByteArrayOutputStream compressedOutput = new ByteArrayOutputStream();
    byte[] compressed = null;

    try {
      DataOutputStream output = new DataOutputStream(new GZIPOutputStream(compressedOutput));
      output.writeInt(MAGIC);
      output.writeInt(VERSION);
      output.writeInt(entries.size());

      for (Entry entry : entries) {
        output.writeInt(entry.position);
        output.writeLong(entry.messageId);
        output.writeBoolean(entry.mms);
        output.writeLong(entry.dateReceived);
        writeString(output, entry.body);
      }

      output.close();
      compressed = compressedOutput.toByteArray();
      byte[] encrypted = new MasterCipher(masterSecret).encryptBytes(compressed);
      if (encrypted == null) throw new IOException("Unable to encrypt message search index");
      return encrypted;
    } finally {
      if (compressed != null) Arrays.fill(compressed, (byte)0);
      try { compressedOutput.close(); } catch (IOException ignored) {}
    }
  }

  public static List<Entry> decrypt(byte[] encrypted, MasterSecret masterSecret) throws IOException {
    byte[] compressed = null;
    try {
      compressed = new MasterCipher(masterSecret).decryptBytes(encrypted);
      DataInputStream input = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(compressed)));

      if (input.readInt() != MAGIC || input.readInt() != VERSION) {
        throw new IOException("Unsupported message search index format");
      }

      int count = input.readInt();
      if (count < 0 || count > MAX_ENTRIES) throw new IOException("Invalid message search index size");

      List<Entry> entries = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        int position = input.readInt();
        long messageId = input.readLong();
        boolean mms = input.readBoolean();
        long dateReceived = input.readLong();
        String body = readString(input);
        entries.add(new Entry(position, messageId, mms, dateReceived, body));
      }

      input.close();
      return entries;
    } catch (InvalidMessageException e) {
      throw new IOException("Unable to decrypt message search index", e);
    } finally {
      if (compressed != null) Arrays.fill(compressed, (byte)0);
    }
  }

  private static void writeString(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(UTF8);
    if (bytes.length > MAX_STRING_BYTES) throw new IOException("Message is too large to index");
    output.writeInt(bytes.length);
    output.write(bytes);
    Arrays.fill(bytes, (byte)0);
  }

  private static String readString(DataInputStream input) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("Invalid indexed message size");
    byte[] bytes = new byte[length];
    input.readFully(bytes);
    try {
      return new String(bytes, UTF8);
    } finally {
      Arrays.fill(bytes, (byte)0);
    }
  }

  public static class Entry {
    public final int position;
    public final long messageId;
    public final boolean mms;
    public final long dateReceived;
    public final String body;

    public Entry(int position, long messageId, boolean mms, long dateReceived, String body) {
      this.position = position;
      this.messageId = messageId;
      this.mms = mms;
      this.dateReceived = dateReceived;
      this.body = body == null ? "" : body;
    }
  }
}
