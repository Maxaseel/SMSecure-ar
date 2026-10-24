package org.smssecure.smssecure.util;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.MessageSearchIndexDatabase;
import org.smssecure.smssecure.database.MmsSmsDatabase;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.database.model.MessageRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Builds an encrypted, durable search snapshot only after an explicit user request. */
public final class MessageSearchIndexer {

  private static final String TAG = MessageSearchIndexer.class.getSimpleName();

  private MessageSearchIndexer() {}

  public interface ProgressListener {
    void onProgress(int completedConversations, int totalConversations, int indexedMessages);
    boolean isCancelled();
  }

  public static IndexStats indexAll(Context context, MasterSecret masterSecret, ProgressListener listener) {
    Context appContext = context.getApplicationContext();
    ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(appContext);
    Cursor threads = null;
    int total = 0;
    int completed = 0;
    int indexedMessages = 0;
    int failures = 0;
    boolean cancelled = false;

    try {
      threads = threadDatabase.getAllConversationList();
      total = threads == null ? 0 : threads.getCount();

      while (threads != null && threads.moveToNext()) {
        if (listener != null && listener.isCancelled()) {
          cancelled = true;
          break;
        }

        long threadId = threads.getLong(threads.getColumnIndexOrThrow(ThreadDatabase.ID));
        if (threadId > 0) {
          try {
            int count = indexConversation(appContext, masterSecret, threadId, listener);
            if (count >= 0) indexedMessages += count;
            else failures++;
          } catch (Exception e) {
            failures++;
            Log.w(TAG, "Unable to index conversation " + threadId, e);
            DatabaseFactory.getMessageSearchIndexDatabase(appContext).markDirty(threadId);
          }
        }

        completed++;
        if (listener != null) listener.onProgress(completed, total, indexedMessages);
      }
    } finally {
      if (threads != null) threads.close();
    }

    return new IndexStats(completed, total, indexedMessages, failures, cancelled);
  }

  private static int indexConversation(Context context, MasterSecret masterSecret, long threadId,
                                       ProgressListener listener) throws IOException
  {
    MessageSearchIndexDatabase indexDatabase = DatabaseFactory.getMessageSearchIndexDatabase(context);
    long generation = indexDatabase.beginBuild(threadId);
    MmsSmsDatabase messages = DatabaseFactory.getMmsSmsDatabase(context);
    Cursor cursor = null;
    MmsSmsDatabase.Reader reader = null;
    List<PersistentMessageSearchIndex.Entry> entries = new ArrayList<>();
    int position = 0;

    try {
      cursor = messages.getConversation(threadId);
      if (cursor == null) return -1;
      reader = messages.readerFor(cursor, masterSecret);
      MessageRecord record;

      while ((record = reader.getNext()) != null) {
        if (listener != null && listener.isCancelled()) {
          indexDatabase.markDirty(threadId);
          return -1;
        }

        String body = PersistentMessageSearchIndex.getSearchableText(record);
        entries.add(new PersistentMessageSearchIndex.Entry(position++, record.getId(), record.isMms(),
                                                            record.getDateReceived(), body));
      }

      ThreadDatabase.SearchMetadata metadata = DatabaseFactory.getThreadDatabase(context).getSearchMetadata(threadId);
      byte[] encrypted = PersistentMessageSearchIndex.encrypt(entries, masterSecret);
      boolean stored = indexDatabase.storeIfGenerationMatches(threadId, generation, encrypted,
                                                               metadata.messageCount, metadata.threadDate);
      java.util.Arrays.fill(encrypted, (byte)0);
      return stored ? position : -1;
    } finally {
      if (reader != null) reader.close();
      else if (cursor != null) cursor.close();
      entries.clear();
    }
  }

  public static final class IndexStats {
    public final int completedConversations;
    public final int totalConversations;
    public final int indexedMessages;
    public final int failures;
    public final boolean cancelled;

    private IndexStats(int completedConversations, int totalConversations, int indexedMessages,
                       int failures, boolean cancelled)
    {
      this.completedConversations = completedConversations;
      this.totalConversations = totalConversations;
      this.indexedMessages = indexedMessages;
      this.failures = failures;
      this.cancelled = cancelled;
    }
  }
}
