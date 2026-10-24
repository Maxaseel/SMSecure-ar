/**
 * Copyright (C) 2015 Open Whisper Systems
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
package org.smssecure.smssecure;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.AlertDialog;
import android.support.v7.view.ActionMode;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.ItemAnimator.ItemAnimatorFinishedListener;
import android.support.v7.widget.RecyclerView.OnScrollListener;
import android.text.ClipboardManager;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;

import org.smssecure.smssecure.ConversationAdapter.HeaderViewHolder;
import org.smssecure.smssecure.ConversationAdapter.ItemClickListener;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.MessageSearchIndexDatabase;
import org.smssecure.smssecure.database.MmsSmsDatabase;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.database.loaders.ConversationLoader;
import org.smssecure.smssecure.database.model.MediaMmsMessageRecord;
import org.smssecure.smssecure.database.model.MessageRecord;
import org.smssecure.smssecure.mms.Slide;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.protocol.AutoInitiate;
import org.smssecure.smssecure.sms.MessageSender;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.task.ProgressDialogAsyncTask;
import org.smssecure.smssecure.util.SaveAttachmentTask;
import org.smssecure.smssecure.util.SaveAttachmentTask.Attachment;
import org.smssecure.smssecure.util.StickyHeaderDecoration;
import org.smssecure.smssecure.util.PersistentMessageSearchIndex;
import org.smssecure.smssecure.util.TextHighlighter;
import org.smssecure.smssecure.util.ViewUtil;
import org.smssecure.smssecure.util.SilencePreferences;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ConversationFragment extends Fragment
  implements LoaderManager.LoaderCallbacks<Cursor>
{
  private static final String TAG = ConversationFragment.class.getSimpleName();

  private static final long   PARTIAL_CONVERSATION_LIMIT = 500L;

  private final ActionModeCallback actionModeCallback     = new ActionModeCallback();
  private final ItemClickListener  selectionClickListener = new ConversationFragmentItemClickListener();

  private ConversationFragmentListener listener;

  private MasterSecret                masterSecret;
  private Recipients                  recipients;
  private long                        threadId;
  private long                        lastSeen;
  private boolean                     firstLoad;
  private ActionMode                  actionMode;
  private Locale                      locale;
  private RecyclerView                list;
  private RecyclerView.ItemDecoration lastSeenDecoration;
  private View                        loadMoreView;
  private View                        composeDivider;
  private View                        scrollToBottomButton;
  private TextView                    scrollDateHeader;
  private final Handler               searchHandler = new Handler();
  private AlertDialog                 searchDialog;
  private EditText                    searchInput;
  private TextView                    searchStatus;
  private ProgressBar                 searchProgress;
  private MessageSearchResultsAdapter searchResultsAdapter;
  private AsyncTask<String, Void, SearchResponse> searchTask;
  private Runnable                    pendingSearch;
  private String                      activeSearchQuery = "";
  private int                         pendingSearchPosition = -1;
  private volatile List<PersistentMessageSearchIndex.Entry> cachedSearchIndex;
  private volatile long               cachedSearchIndexGeneration = -1;

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    this.masterSecret = getArguments().getParcelable("master_secret");
    this.locale       = (Locale) getArguments().getSerializable(PassphraseRequiredActionBarActivity.LOCALE_EXTRA);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
    final View view = inflater.inflate(R.layout.conversation_fragment, container, false);
    list                 = ViewUtil.findById(view, android.R.id.list);
    composeDivider       = ViewUtil.findById(view, R.id.compose_divider);
    scrollToBottomButton = ViewUtil.findById(view, R.id.scroll_to_bottom_button);
    scrollDateHeader     = ViewUtil.findById(view, R.id.scroll_date_header);

    scrollToBottomButton.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(final View view) {
        scrollToBottom();
      }
    });

    final LinearLayoutManager layoutManager = new LinearLayoutManager(getActivity(), LinearLayoutManager.VERTICAL, true);
    list.setHasFixedSize(false);
    list.setLayoutManager(layoutManager);

    loadMoreView = inflater.inflate(R.layout.load_more_header, container, false);
    loadMoreView.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        Bundle args = new Bundle();
        args.putLong("limit", 0);
        getLoaderManager().restartLoader(0, args, ConversationFragment.this);
      }
    });
    return view;
  }

  @Override
  public void onActivityCreated(Bundle bundle) {
    super.onActivityCreated(bundle);

    initializeResources();
    initializeListAdapter();
  }

  @Override
  public void onAttach(Activity activity) {
    super.onAttach(activity);
    this.listener = (ConversationFragmentListener)activity;
  }

  @Override
  public void onResume() {
    super.onResume();

    if (list.getAdapter() != null) {
      list.getAdapter().notifyDataSetChanged();
    }
  }

  @Override
  public void onDestroyView() {
    if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
    if (searchTask != null) searchTask.cancel(true);
    if (searchDialog != null && searchDialog.isShowing()) searchDialog.dismiss();
    cachedSearchIndex = null;
    cachedSearchIndexGeneration = -1;
    super.onDestroyView();
  }

  public void onNewIntent() {
    if (actionMode != null) {
      actionMode.finish();
    }

    initializeResources();
    initializeListAdapter();

    if (threadId == -1) {
      getLoaderManager().restartLoader(0, Bundle.EMPTY, this);
    }
  }

  public void reloadList() {
    getLoaderManager().restartLoader(0, Bundle.EMPTY, this);
  }

  private void initializeResources() {
    this.recipients     = RecipientFactory.getRecipientsForIds(getActivity(), getActivity().getIntent().getLongArrayExtra("recipients"), true);
    this.threadId       = this.getActivity().getIntent().getLongExtra("thread_id", -1);
    this.lastSeen       = this.getActivity().getIntent().getLongExtra(ConversationActivity.LAST_SEEN_EXTRA, -1);
    this.firstLoad      = true;

    OnScrollListener scrollListener = new ConversationScrollListener(getActivity());
    list.addOnScrollListener(scrollListener);
  }

  private void initializeListAdapter() {
    if (this.recipients != null && this.threadId != -1) {
      ConversationAdapter adapter = new ConversationAdapter(getActivity(), masterSecret, locale, selectionClickListener, null, this.recipients);
      list.setAdapter(adapter);
      list.addItemDecoration(new StickyHeaderDecoration(adapter, false, false));

      setLastSeen(lastSeen);
      getLoaderManager().restartLoader(0, Bundle.EMPTY, this);
      list.getItemAnimator().setMoveDuration(120);
    }
  }

  private void setCorrectMenuVisibility(Menu menu) {
    Set<MessageRecord> messageRecords = getListAdapter().getSelectedItems();

    if (actionMode != null && messageRecords.size() == 0) {
      actionMode.finish();
      return;
    }

    if (messageRecords.size() > 1) {
      menu.findItem(R.id.menu_context_forward).setVisible(false);
      menu.findItem(R.id.menu_context_details).setVisible(false);
      menu.findItem(R.id.menu_context_save_attachment).setVisible(false);
      menu.findItem(R.id.menu_context_resend).setVisible(false);
    } else {
      MessageRecord messageRecord = messageRecords.iterator().next();

      menu.findItem(R.id.menu_context_resend).setVisible(messageRecord.isFailed());
      menu.findItem(R.id.menu_context_save_attachment).setVisible(messageRecord.isMms()              &&
                                                                  !messageRecord.isMmsNotification() &&
                                                                  ((MediaMmsMessageRecord)messageRecord).containsMediaSlide());

      menu.findItem(R.id.menu_context_forward).setVisible(true);
      menu.findItem(R.id.menu_context_details).setVisible(true);
      menu.findItem(R.id.menu_context_copy).setVisible(true);
    }
  }

  private ConversationAdapter getListAdapter() {
    return (ConversationAdapter) list.getAdapter();
  }

  private MessageRecord getSelectedMessageRecord() {
    Set<MessageRecord> messageRecords = getListAdapter().getSelectedItems();

    if (messageRecords.size() == 1) return messageRecords.iterator().next();
    else                            throw new AssertionError();
  }

  public void reload(Recipients recipients, long threadId) {
    this.recipients = recipients;

    if (this.threadId != threadId) {
      this.threadId = threadId;
      cachedSearchIndex = null;
      cachedSearchIndexGeneration = -1;
      initializeListAdapter();
    }
  }

  public void scrollToBottom() {
    list.scrollToPosition(0);
  }

  public void showMessageSearch() {
    if (getActivity() == null) return;

    final Context context = getActivity();
    final LinearLayout dialogContent = new LinearLayout(context);
    dialogContent.setOrientation(LinearLayout.VERTICAL);
    int padding = (int)(16 * context.getResources().getDisplayMetrics().density);
    dialogContent.setPadding(padding, 0, padding, 0);

    searchInput = new EditText(context);
    searchInput.setSingleLine(true);
    searchInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    searchInput.setHint(R.string.ConversationActivity_search_messages_hint);
    dialogContent.addView(searchInput, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                                                     LinearLayout.LayoutParams.WRAP_CONTENT));

    LinearLayout statusRow = new LinearLayout(context);
    statusRow.setOrientation(LinearLayout.HORIZONTAL);
    statusRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

    searchProgress = new ProgressBar(context);
    searchProgress.setIndeterminate(true);
    searchProgress.setVisibility(View.GONE);
    statusRow.addView(searchProgress, new LinearLayout.LayoutParams(padding, padding));

    searchStatus = new TextView(context);
    searchStatus.setText(R.string.ConversationActivity_search_messages_hint);
    searchStatus.setPadding(padding / 2, padding / 2, 0, padding / 2);
    statusRow.addView(searchStatus, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    dialogContent.addView(statusRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                                                  LinearLayout.LayoutParams.WRAP_CONTENT));

    ListView resultsList = new ListView(context);
    searchResultsAdapter = new MessageSearchResultsAdapter(context);
    resultsList.setAdapter(searchResultsAdapter);
    dialogContent.addView(resultsList, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                                                     (int)(360 * context.getResources().getDisplayMetrics().density)));

    searchDialog = new AlertDialog.Builder(context)
        .setTitle(R.string.ConversationActivity_search_messages)
        .setView(dialogContent)
        .setNegativeButton(android.R.string.cancel, null)
        .create();

    searchDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
      @Override
      public void onDismiss(DialogInterface dialog) {
        if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
        pendingSearch = null;
        if (searchTask != null) searchTask.cancel(true);
        searchTask = null;
        searchDialog = null;
        searchInput = null;
        searchStatus = null;
        searchProgress = null;
        searchResultsAdapter = null;
      }
    });

    resultsList.setOnItemClickListener((parent, view, position, id) -> {
      if (searchResultsAdapter == null || searchInput == null) return;
      SearchResult result = searchResultsAdapter.getItem(position);
      String query = searchInput.getText().toString().trim();
      if (!TextUtils.isEmpty(query)) {
        jumpToSearchResult(result, query);
        searchDialog.dismiss();
      }
    });

    searchInput.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
        queueMessageSearch(s.toString().trim());
      }
      @Override public void afterTextChanged(Editable s) {}
    });

    searchDialog.show();
    if (searchDialog.getWindow() != null) {
      searchDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
    searchInput.requestFocus();
    if (!TextUtils.isEmpty(activeSearchQuery)) {
      searchInput.setText(activeSearchQuery);
      searchInput.setSelection(searchInput.length());
    }
  }

  private void queueMessageSearch(final String query) {
    if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
    if (searchTask != null) searchTask.cancel(true);

    if (TextUtils.isEmpty(query)) {
      if (searchProgress != null) searchProgress.setVisibility(View.GONE);
      if (searchStatus != null) searchStatus.setText(R.string.ConversationActivity_search_messages_hint);
      if (searchResultsAdapter != null) searchResultsAdapter.setResults(query, Collections.<SearchResult>emptyList());
      return;
    }

    if (searchProgress != null) searchProgress.setVisibility(View.VISIBLE);
    if (searchStatus != null) searchStatus.setText(R.string.ConversationActivity_searching_messages);
    pendingSearch = new Runnable() {
      @Override public void run() {
        startMessageSearch(query);
      }
    };
    searchHandler.postDelayed(pendingSearch, 250);
  }

  private void startMessageSearch(final String query) {
    if (getActivity() == null) return;

    final Context context = getActivity().getApplicationContext();
    final long searchThreadId = threadId;
    final MasterSecret searchSecret = masterSecret;

    searchTask = new AsyncTask<String, Void, SearchResponse>() {
      @Override
      protected SearchResponse doInBackground(String... queries) {
        Cursor cursor = null;
        MmsSmsDatabase.Reader reader = null;
        List<SearchResult> results = new ArrayList<>();
        int resultCount = 0;
        int position = 0;

        try {
          String query = queries[0];
          MessageSearchIndexDatabase indexDatabase = DatabaseFactory.getMessageSearchIndexDatabase(context);
          MessageSearchIndexDatabase.IndexRecord storedIndex = indexDatabase.getReadyIndex(searchThreadId);
          ThreadDatabase.SearchMetadata metadata = DatabaseFactory.getThreadDatabase(context).getSearchMetadata(searchThreadId);
          long generation = indexDatabase.getThreadGeneration(searchThreadId);

          if (storedIndex != null && storedIndex.messageCount == metadata.messageCount &&
              storedIndex.threadDate == metadata.threadDate)
          {
            List<PersistentMessageSearchIndex.Entry> entries;
            boolean loaded = cachedSearchIndex != null && cachedSearchIndexGeneration == generation;
            if (loaded) {
              entries = cachedSearchIndex;
            } else {
              entries = PersistentMessageSearchIndex.decrypt(storedIndex.encryptedPayload, searchSecret);
            }

            for (PersistentMessageSearchIndex.Entry entry : entries) {
              if (isCancelled()) break;
              int match = TextHighlighter.indexOfIgnoreCase(entry.body, query, 0);
              if (match >= 0) {
                resultCount++;
                if (results.size() < 500) {
                  int start = Math.max(0, match - 48);
                  int end = Math.min(entry.body.length(), match + query.length() + 80);
                  String excerpt = (start > 0 ? "…" : "") + entry.body.substring(start, end) +
                                   (end < entry.body.length() ? "…" : "");
                  results.add(new SearchResult(entry.position, excerpt, entry.dateReceived));
                }
              }
            }

            return new SearchResponse(results, resultCount, false, loaded ? null : entries, generation);
          }

          MmsSmsDatabase database = DatabaseFactory.getMmsSmsDatabase(context);
          cursor = database.getConversation(searchThreadId);
          reader = database.readerFor(cursor, searchSecret);

          MessageRecord record;
          while (!isCancelled() && (record = reader.getNext()) != null) {
            String body = getSearchableMessageText(record);
            int match = TextHighlighter.indexOfIgnoreCase(body, queries[0], 0);

            if (match >= 0) {
              resultCount++;
              if (results.size() < 500) {
                int start = Math.max(0, match - 48);
                int end = Math.min(body.length(), match + queries[0].length() + 80);
                String excerpt = (start > 0 ? "…" : "") + body.substring(start, end) + (end < body.length() ? "…" : "");
                results.add(new SearchResult(position, excerpt, record.getDateReceived()));
              }
            }

            position++;
          }

          return new SearchResponse(results, resultCount, false);
        } catch (Exception e) {
          Log.w(TAG, "Unable to search conversation messages", e);
          try {
            DatabaseFactory.getMessageSearchIndexDatabase(context).markDirty(searchThreadId);
          } catch (Exception ignored) {
            Log.w(TAG, "Unable to invalidate an unusable message index", ignored);
          }
          return new SearchResponse(results, resultCount, true);
        } finally {
          if (reader != null) reader.close();
          else if (cursor != null) cursor.close();
        }
      }

      @Override
      protected void onPostExecute(SearchResponse response) {
        if (searchDialog == null || !searchDialog.isShowing() || searchInput == null || searchStatus == null) return;

        String currentQuery = searchInput.getText().toString().trim();
        if (!query.equals(currentQuery)) return;

        searchTask = null;
        if (response.cacheEntries != null) {
          cachedSearchIndex = response.cacheEntries;
          cachedSearchIndexGeneration = response.cacheGeneration;
        }
        searchProgress.setVisibility(View.GONE);
        searchResultsAdapter.setResults(query, response.results);

        if (response.failed) {
          searchStatus.setText(R.string.ConversationActivity_message_search_failed);
        } else if (response.resultCount == 0) {
          searchStatus.setText(R.string.ConversationActivity_no_matching_messages);
        } else if (response.resultCount > response.results.size()) {
          searchStatus.setText(getString(R.string.ConversationActivity_search_results_limited,
                                         response.results.size(), response.resultCount));
        } else {
          searchStatus.setText(getString(R.string.ConversationActivity_search_results,
                                         response.resultCount));
        }
      }
    }.execute(query);
  }

  private void jumpToSearchResult(SearchResult result, String query) {
    activeSearchQuery = query;
    ConversationAdapter adapter = getListAdapter();
    adapter.setSearchQuery(query);
    Cursor currentCursor = adapter.getCursor();

    if (currentCursor != null && result.position < currentCursor.getCount()) {
      scrollToSearchPosition(result.position);
    } else {
      pendingSearchPosition = result.position;
      Bundle args = new Bundle();
      args.putLong("limit", 0);
      getLoaderManager().restartLoader(0, args, this);
    }
  }

  private static String getSearchableMessageText(MessageRecord record) {
    return PersistentMessageSearchIndex.getSearchableText(record);
  }

  private void scrollToSearchPosition(final int position) {
    list.post(new Runnable() {
      @Override public void run() {
        Cursor cursor = getListAdapter().getCursor();
        if (cursor == null || cursor.getCount() == 0) return;

        int target = Math.min(Math.max(0, position), cursor.getCount() - 1);
        ((LinearLayoutManager)list.getLayoutManager()).scrollToPositionWithOffset(target, list.getHeight() / 3);
      }
    });
  }

  private static class SearchResult {
    private final int position;
    private final String excerpt;
    private final long dateReceived;

    private SearchResult(int position, String excerpt, long dateReceived) {
      this.position = position;
      this.excerpt = excerpt;
      this.dateReceived = dateReceived;
    }
  }

  private static class SearchResponse {
    private final List<SearchResult> results;
    private final int resultCount;
    private final boolean failed;
    private final List<PersistentMessageSearchIndex.Entry> cacheEntries;
    private final long cacheGeneration;

    private SearchResponse(List<SearchResult> results, int resultCount, boolean failed) {
      this(results, resultCount, failed, null, -1);
    }

    private SearchResponse(List<SearchResult> results, int resultCount, boolean failed,
                           List<PersistentMessageSearchIndex.Entry> cacheEntries, long cacheGeneration)
    {
      this.results = results;
      this.resultCount = resultCount;
      this.failed = failed;
      this.cacheEntries = cacheEntries;
      this.cacheGeneration = cacheGeneration;
    }
  }

  private static class MessageSearchResultsAdapter extends BaseAdapter {
    private final Context context;
    private List<SearchResult> results = Collections.emptyList();
    private String query = "";

    private MessageSearchResultsAdapter(Context context) {
      this.context = context;
    }

    private void setResults(String query, List<SearchResult> results) {
      this.query = query;
      this.results = results;
      notifyDataSetChanged();
    }

    @Override public int getCount() { return results.size(); }
    @Override public SearchResult getItem(int position) { return results.get(position); }
    @Override public long getItemId(int position) { return results.get(position).position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      TextView textView = convertView instanceof TextView ? (TextView)convertView : new TextView(context);
      int padding = (int)(14 * context.getResources().getDisplayMetrics().density);
      textView.setPadding(padding, padding, padding, padding);
      textView.setTextSize(16);
      textView.setMaxLines(4);
      SearchResult result = getItem(position);
      SpannableStringBuilder content = new SpannableStringBuilder(TextHighlighter.highlight(result.excerpt, query));
      content.append('\n');
      int dateStart = content.length();
      Date date = new Date(result.dateReceived);
      content.append(DateFormat.getDateFormat(context).format(date));
      content.append(' ');
      content.append(DateFormat.getTimeFormat(context).format(date));
      content.setSpan(new ForegroundColorSpan(Color.GRAY), dateStart, content.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
      textView.setText(content);
      return textView;
    }
  }

  public void setLastSeen(long lastSeen) {
    this.lastSeen = lastSeen;
    if (lastSeenDecoration != null) {
      list.removeItemDecoration(lastSeenDecoration);
    }

    final Context context = getActivity().getApplicationContext();
    if (!SilencePreferences.hideUnreadMessageDivider(context)) {
      lastSeenDecoration = new ConversationAdapter.LastSeenHeader(getListAdapter(), lastSeen);
      list.addItemDecoration(lastSeenDecoration);
    }
  }

  private void handleCopyMessage(final Set<MessageRecord> messageRecords) {
    List<MessageRecord> messageList = new LinkedList<>(messageRecords);
    Collections.sort(messageList, new Comparator<MessageRecord>() {
      @Override
      public int compare(MessageRecord lhs, MessageRecord rhs) {
        if      (lhs.getDateReceived() < rhs.getDateReceived())  return -1;
        else if (lhs.getDateReceived() == rhs.getDateReceived()) return 0;
        else                                                     return 1;
      }
    });

    StringBuilder    bodyBuilder = new StringBuilder();
    ClipboardManager clipboard   = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
    boolean          first       = true;

    for (MessageRecord messageRecord : messageList) {
      String body = messageRecord.getDisplayBody().toString();

      if (body != null) {
        if (!first) bodyBuilder.append('\n');
        bodyBuilder.append(body);
        first = false;
      }
    }

    String result = bodyBuilder.toString();

    if (!TextUtils.isEmpty(result))
        clipboard.setText(result);
  }

  private void handleDeleteMessages(final Set<MessageRecord> messageRecords) {
    int                 messagesCount = messageRecords.size();
    AlertDialog.Builder builder       = new AlertDialog.Builder(getActivity());

    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setTitle(getActivity().getResources().getQuantityString(R.plurals.ConversationFragment_delete_selected_messages, messagesCount, messagesCount));
    builder.setMessage(getActivity().getResources().getQuantityString(R.plurals.ConversationFragment_this_will_permanently_delete_all_n_selected_messages, messagesCount, messagesCount));
    builder.setCancelable(true);

    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        new ProgressDialogAsyncTask<MessageRecord, Void, Void>(getActivity(),
                                                               R.string.ConversationFragment_deleting,
                                                               R.string.ConversationFragment_deleting_messages)
        {
          @Override
          protected Void doInBackground(MessageRecord... messageRecords) {
            for (MessageRecord messageRecord : messageRecords) {
              boolean threadDeleted;

              if (messageRecord.isMms()) {
                threadDeleted = DatabaseFactory.getMmsDatabase(getActivity()).delete(messageRecord.getId());
              } else {
                threadDeleted = DatabaseFactory.getSmsDatabase(getActivity()).deleteMessage(messageRecord.getId());
              }

              if (threadDeleted) {
                threadId = -1;
                listener.setThreadId(threadId);
              }
            }

            return null;
          }
        }.execute(messageRecords.toArray(new MessageRecord[messageRecords.size()]));
      }
    });

    builder.setNegativeButton(R.string.no, null);
    builder.show();
  }

  private void handleDisplayDetails(MessageRecord message) {
    Intent intent = new Intent(getActivity(), MessageDetailsActivity.class);
    intent.putExtra(MessageDetailsActivity.MASTER_SECRET_EXTRA, masterSecret);
    intent.putExtra(MessageDetailsActivity.MESSAGE_ID_EXTRA, message.getId());
    intent.putExtra(MessageDetailsActivity.THREAD_ID_EXTRA, threadId);
    intent.putExtra(MessageDetailsActivity.TYPE_EXTRA, message.isMms() ? MmsSmsDatabase.MMS_TRANSPORT : MmsSmsDatabase.SMS_TRANSPORT);
    intent.putExtra(MessageDetailsActivity.RECIPIENTS_IDS_EXTRA, recipients.getIds());
    startActivity(intent);
  }

  private void handleForwardMessage(MessageRecord message) {
    Intent composeIntent = new Intent(getActivity(), ShareActivity.class);
    composeIntent.putExtra(Intent.EXTRA_TEXT, message.getDisplayBody().toString());
    if (message.isMms()) {
      MediaMmsMessageRecord mediaMessage = (MediaMmsMessageRecord) message;
      if (mediaMessage.containsMediaSlide()) {
        Slide slide = mediaMessage.getSlideDeck().getSlides().get(0);
        composeIntent.putExtra(Intent.EXTRA_STREAM, slide.getUri());
        composeIntent.setType(slide.getContentType());
      }
    }
    startActivity(composeIntent);
  }

  private void handleResendMessage(final MessageRecord message) {
    final Context context = getActivity().getApplicationContext();
    new AsyncTask<MessageRecord, Void, Void>() {
      @Override
      protected Void doInBackground(MessageRecord... messageRecords) {
        MessageSender.resend(context, masterSecret, messageRecords[0]);
        return null;
      }
    }.execute(message);
  }

  private void handleSaveAttachment(final MediaMmsMessageRecord message) {
    SaveAttachmentTask.showWarningDialog(getActivity(), new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int which) {
        for (Slide slide : message.getSlideDeck().getSlides()) {
          if (slide.hasImage() || slide.hasVideo() || slide.hasAudio()) {
            SaveAttachmentTask saveTask = new SaveAttachmentTask(getActivity(), masterSecret);
            saveTask.execute(new Attachment(slide.getUri(), slide.getContentType(), message.getDateReceived()));
            return;
          }
        }

        Log.w(TAG, "No slide with attachable media found, failing nicely.");
        Toast.makeText(getActivity(),
                       getResources().getQuantityString(R.plurals.ConversationFragment_error_while_saving_attachments_to_sd_card, 1),
                       Toast.LENGTH_LONG).show();
      }
    });
  }

  @Override
  public Loader<Cursor> onCreateLoader(int id, Bundle args) {
    return new ConversationLoader(getActivity(), threadId, args.getLong("limit", PARTIAL_CONVERSATION_LIMIT), lastSeen);
  }


  @Override
  public void onLoadFinished(Loader<Cursor> cursorLoader, Cursor cursor) {
    Log.w(TAG, "onLoadFinished");
    ConversationLoader loader = (ConversationLoader)cursorLoader;

    if (list.getAdapter() != null) {
      if (cursor.getCount() >= PARTIAL_CONVERSATION_LIMIT && loader.hasLimit()) {
        getListAdapter().setFooterView(loadMoreView);
      } else {
        getListAdapter().setFooterView(null);
      }

      if (lastSeen == -1) {
        setLastSeen(loader.getLastSeen());
      }

      getListAdapter().changeCursor(cursor);
      getListAdapter().setSearchQuery(TextUtils.isEmpty(activeSearchQuery) ? null : activeSearchQuery);

      int lastSeenPosition = getListAdapter().findLastSeenPosition(lastSeen);

      if (firstLoad) {
        scrollToLastSeenPosition(lastSeenPosition);
        firstLoad = false;
      }

      if (lastSeenPosition <= 0) {
        setLastSeen(0);
      }

      if (pendingSearchPosition >= 0) {
        int targetPosition = pendingSearchPosition;
        pendingSearchPosition = -1;
        scrollToSearchPosition(targetPosition);
      }
    }
  }

  @Override
  public void onLoaderReset(Loader<Cursor> arg0) {
    if (list.getAdapter() != null) {
      getListAdapter().changeCursor(null);
    }
  }

  private void scrollToLastSeenPosition(final int lastSeenPosition) {
    if (lastSeenPosition > 0) {
      list.post(new Runnable() {
        @Override
        public void run() {
          ((LinearLayoutManager)list.getLayoutManager()).scrollToPositionWithOffset(lastSeenPosition, list.getHeight());
        }
      });
    }
  }

  public interface ConversationFragmentListener {
    void setThreadId(long threadId);
  }

  private class ConversationScrollListener extends OnScrollListener {

    private final Animation              scrollButtonInAnimation;
    private final Animation              scrollButtonOutAnimation;
    private final ConversationDateHeader conversationDateHeader;

    private boolean wasAtBottom           = true;
    private boolean wasAtZoomScrollHeight = false;
    private long    lastPositionId        = -1;

    ConversationScrollListener(@NonNull Context context) {
      this.scrollButtonInAnimation  = AnimationUtils.loadAnimation(context, R.anim.fade_scale_in);
      this.scrollButtonOutAnimation = AnimationUtils.loadAnimation(context, R.anim.fade_scale_out);
      this.conversationDateHeader   = new ConversationDateHeader(context, scrollDateHeader);

      this.scrollButtonInAnimation.setDuration(100);
      this.scrollButtonOutAnimation.setDuration(50);
    }

    @Override
    public void onScrolled(final RecyclerView rv, final int dx, final int dy) {
      boolean currentlyAtBottom           = isAtBottom();
      boolean currentlyAtZoomScrollHeight = isAtZoomScrollHeight();
      int     positionId                  = getHeaderPositionId();

      if (currentlyAtBottom && !wasAtBottom) {
        ViewUtil.fadeOut(composeDivider, 50, View.INVISIBLE);
        ViewUtil.animateOut(scrollToBottomButton, scrollButtonOutAnimation, View.INVISIBLE);
      } else if (!currentlyAtBottom && wasAtBottom) {
        ViewUtil.fadeIn(composeDivider, 500);
      }

      if (currentlyAtZoomScrollHeight && !wasAtZoomScrollHeight) {
        ViewUtil.animateIn(scrollToBottomButton, scrollButtonInAnimation);
      }

      if (positionId != lastPositionId) {
        bindScrollHeader(conversationDateHeader, positionId);
      }

      wasAtBottom           = currentlyAtBottom;
      wasAtZoomScrollHeight = currentlyAtZoomScrollHeight;
      lastPositionId        = positionId;
    }

    @Override
    public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
      if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
        conversationDateHeader.show();
      } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
        conversationDateHeader.hide();
      }
    }

    private boolean isAtBottom() {
      if (list.getChildCount() == 0) return true;

      View    bottomView       = list.getChildAt(0);
      int     firstVisibleItem = ((LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition();
      boolean isAtBottom       = (firstVisibleItem == 0);

      return isAtBottom && bottomView.getBottom() <= list.getHeight();
    }

    private boolean isAtZoomScrollHeight() {
      return ((LinearLayoutManager) list.getLayoutManager()).findFirstCompletelyVisibleItemPosition() > 4;
    }

    private int getHeaderPositionId() {
      return ((LinearLayoutManager)list.getLayoutManager()).findLastVisibleItemPosition();
    }

    private void bindScrollHeader(HeaderViewHolder headerViewHolder, int positionId) {
      if (((ConversationAdapter)list.getAdapter()).getHeaderId(positionId) != -1) {
        ((ConversationAdapter) list.getAdapter()).onBindHeaderViewHolder(headerViewHolder, positionId);
      }
    }
  }

  private class ConversationFragmentItemClickListener implements ItemClickListener {

    @Override
    public void onItemClick(ConversationItem item) {
      if (actionMode != null) {
        MessageRecord messageRecord = item.getMessageRecord();
        ((ConversationAdapter) list.getAdapter()).toggleSelection(messageRecord);
        list.getAdapter().notifyDataSetChanged();

        setCorrectMenuVisibility(actionMode.getMenu());
      }
    }

    @Override
    public void onItemLongClick(ConversationItem item) {
      if (actionMode == null) {
        ((ConversationAdapter) list.getAdapter()).toggleSelection(item.getMessageRecord());
        list.getAdapter().notifyDataSetChanged();

        actionMode = ((AppCompatActivity)getActivity()).startSupportActionMode(actionModeCallback);
      }
    }
  }

  private class ActionModeCallback implements ActionMode.Callback {

    private int statusBarColor;

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
      MenuInflater inflater = mode.getMenuInflater();
      inflater.inflate(R.menu.conversation_context, menu);

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        Window window = getActivity().getWindow();
        statusBarColor = window.getStatusBarColor();
        window.setStatusBarColor(getResources().getColor(R.color.action_mode_status_bar));
        window.setNavigationBarColor(getResources().getColor(android.R.color.black));
      }

      setCorrectMenuVisibility(menu);
      return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
      return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
      ((ConversationAdapter)list.getAdapter()).clearSelection();
      list.getAdapter().notifyDataSetChanged();

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        Window window = getActivity().getWindow();
        window.setStatusBarColor(statusBarColor);
        window.setNavigationBarColor(getResources().getColor(android.R.color.black));
      }

      actionMode = null;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
      switch(item.getItemId()) {
        case R.id.menu_context_copy:
          handleCopyMessage(getListAdapter().getSelectedItems());
          actionMode.finish();
          return true;
        case R.id.menu_context_delete_message:
          handleDeleteMessages(getListAdapter().getSelectedItems());
          actionMode.finish();
          return true;
        case R.id.menu_context_details:
          handleDisplayDetails(getSelectedMessageRecord());
          actionMode.finish();
          return true;
        case R.id.menu_context_forward:
          handleForwardMessage(getSelectedMessageRecord());
          actionMode.finish();
          return true;
        case R.id.menu_context_resend:
          handleResendMessage(getSelectedMessageRecord());
          actionMode.finish();
          return true;
        case R.id.menu_context_save_attachment:
          handleSaveAttachment((MediaMmsMessageRecord)getSelectedMessageRecord());
          actionMode.finish();
          return true;
      }

      return false;
    }
  }

  private static class ConversationDateHeader extends HeaderViewHolder {

    private final Animation animateIn;
    private final Animation animateOut;

    private boolean pendingHide = false;

    private ConversationDateHeader(Context context, TextView textView) {
      super(textView);
      this.animateIn  = AnimationUtils.loadAnimation(context, R.anim.slide_from_top);
      this.animateOut = AnimationUtils.loadAnimation(context, R.anim.slide_to_top);

      this.animateIn.setDuration(100);
      this.animateOut.setDuration(100);
    }

    public void show() {
      if (pendingHide) {
        pendingHide = false;
      } else {
        ViewUtil.animateIn(textView, animateIn);
      }
    }

    public void hide() {
      pendingHide = true;

      textView.postDelayed(new Runnable() {
        @Override
        public void run() {
          if (pendingHide) {
            pendingHide = false;
            ViewUtil.animateOut(textView, animateOut, View.GONE);
          }
        }
      }, 400);
    }
  }
}
