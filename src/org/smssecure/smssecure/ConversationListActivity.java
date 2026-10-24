/**
 * Copyright (C) 2014 Open Whisper Systems
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

import android.content.Intent;
import android.content.DialogInterface;
import android.Manifest;
import android.database.ContentObserver;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.app.ProgressDialog;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.widget.SearchView;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.widget.Toast;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.util.MessageSearchIndexer;
import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.service.KeyCachingService;
import org.smssecure.smssecure.util.dualsim.SubscriptionInfoCompat;
import org.smssecure.smssecure.util.dualsim.SubscriptionManagerCompat;
import org.smssecure.smssecure.util.DynamicLanguage;
import org.smssecure.smssecure.util.DynamicTheme;
import org.smssecure.smssecure.util.SilencePreferences;

import java.util.List;

public class ConversationListActivity extends PassphraseRequiredActionBarActivity
    implements ConversationListFragment.ConversationSelectedListener
{
  private static final String TAG = ConversationListActivity.class.getSimpleName();
  private static final int REQUEST_POST_NOTIFICATIONS = 812;

  private final DynamicTheme    dynamicTheme    = new DynamicTheme   ();
  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();

  private ConversationListFragment fragment;
  private ContentObserver observer;
  private MasterSecret masterSecret;
  private ProgressDialog messageIndexProgress;
  private AsyncTask<Void, Integer, MessageSearchIndexer.IndexStats> messageIndexTask;
  private volatile boolean messageIndexCancelRequested;

  private List<SubscriptionInfoCompat> activeSubscriptions;

  @Override
  protected void onPreCreate() {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
  }

  @Override
  protected void onCreate(Bundle icicle, @NonNull MasterSecret masterSecret) {
    this.masterSecret = masterSecret;
    this.activeSubscriptions = SubscriptionManagerCompat.from(this).getActiveSubscriptionInfoList();

    getSupportActionBar().setDisplayOptions(ActionBar.DISPLAY_SHOW_HOME | ActionBar.DISPLAY_SHOW_TITLE);
    getSupportActionBar().setTitle(R.string.app_name);
    fragment = initFragment(android.R.id.content, new ConversationListFragment(), masterSecret, dynamicLanguage.getCurrentLocale());

    initializeContactUpdatesReceiver();
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
    requestNotificationPermissionIfNeeded();
  }

  private void requestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    {
      return;
    }

    android.content.SharedPreferences preferences = getPreferences(MODE_PRIVATE);
    if (!preferences.getBoolean("asked_post_notifications", false)) {
      preferences.edit().putBoolean("asked_post_notifications", true).apply();
      ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.POST_NOTIFICATIONS},
                                        REQUEST_POST_NOTIFICATIONS);
    }
  }

  @Override
  public void onDestroy() {
    if (messageIndexTask != null) messageIndexTask.cancel(true);
    if (messageIndexProgress != null && messageIndexProgress.isShowing()) messageIndexProgress.dismiss();
    if (observer != null) getContentResolver().unregisterContentObserver(observer);
    super.onDestroy();
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuInflater inflater = this.getMenuInflater();
    menu.clear();

    inflater.inflate(R.menu.text_secure_normal, menu);

    menu.findItem(R.id.menu_clear_passphrase).setVisible(!SilencePreferences.isPasswordDisabled(this));

    inflateViewIdentities(menu);

    inflater.inflate(R.menu.conversation_list, menu);
    MenuItem menuItem = menu.findItem(R.id.menu_search);
    initializeSearch(menuItem);
    MenuItem indexItem = menu.findItem(R.id.menu_build_message_index);
    if (indexItem != null) {
      int indexedConversations = DatabaseFactory.getMessageSearchIndexDatabase(this).getReadyIndexCount();
      indexItem.setTitle(indexedConversations > 0
                         ? R.string.conversation_list__menu_update_message_index
                         : R.string.conversation_list__menu_build_message_index);
    }

    super.onPrepareOptionsMenu(menu);
    return true;
  }

  private void inflateViewIdentities(Menu menu) {
    if (Build.VERSION.SDK_INT >= 22 && activeSubscriptions.size() > 1) {
      menu.findItem(R.id.menu_my_identity).setVisible(false);
      MenuItem menuItem = menu.findItem(R.id.menu_my_identity_dual_sim);
      SubMenu identitiesMenu = menuItem.getSubMenu();
      for (SubscriptionInfoCompat subscriptionInfo : activeSubscriptions) {
        final int subscriptionId = subscriptionInfo.getSubscriptionId();
        identitiesMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, subscriptionInfo.getDisplayName())
                      .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(MenuItem item) {
                          handleMyIdentity(subscriptionId);
                          return true;
                        }
                      });
      }
    } else {
      menu.findItem(R.id.menu_my_identity_dual_sim).setVisible(false);
    }
  }

  private void initializeSearch(MenuItem searchViewItem) {
    SearchView searchView = (SearchView)MenuItemCompat.getActionView(searchViewItem);
    searchView.setQueryHint(getString(R.string.ConversationListActivity_search));
    searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
      @Override
      public boolean onQueryTextSubmit(String query) {
        if (fragment != null) {
          fragment.setQueryFilter(query);
          return true;
        }

        return false;
      }

      @Override
      public boolean onQueryTextChange(String newText) {
        return onQueryTextSubmit(newText);
      }
    });

    MenuItemCompat.setOnActionExpandListener(searchViewItem, new MenuItemCompat.OnActionExpandListener() {
      @Override
      public boolean onMenuItemActionExpand(MenuItem menuItem) {
        return true;
      }

      @Override
      public boolean onMenuItemActionCollapse(MenuItem menuItem) {
        if (fragment != null) {
          fragment.resetQueryFilter();
        }

        return true;
      }
    });
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);

    switch (item.getItemId()) {
    case R.id.menu_archived_conversations: handleSwitchToArchive();        return true;
    case R.id.menu_new_group:              createGroup();                  return true;
    case R.id.menu_settings:               handleDisplaySettings();        return true;
    case R.id.menu_clear_passphrase:       handleClearPassphrase();        return true;
    case R.id.menu_mark_all_read:          handleMarkAllRead();            return true;
    case R.id.menu_import_export:          handleImportExport();           return true;
    case R.id.menu_build_message_index:    handleBuildMessageIndex();      return true;
    case R.id.menu_my_identity:            handleMyIdentity();             return true;
    }

    return false;
  }

  private void handleBuildMessageIndex() {
    if (messageIndexTask != null) return;
    messageIndexCancelRequested = false;

    messageIndexProgress = new ProgressDialog(this);
    messageIndexProgress.setTitle(R.string.message_index_title);
    messageIndexProgress.setMessage(getString(R.string.ConversationActivity_searching_messages));
    messageIndexProgress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
    messageIndexProgress.setIndeterminate(true);
    messageIndexProgress.setCancelable(false);
    messageIndexProgress.setButton(DialogInterface.BUTTON_NEGATIVE, getString(android.R.string.cancel),
        new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface dialog, int which) {
            messageIndexCancelRequested = true;
          }
        });
    messageIndexProgress.show();

    final android.content.Context appContext = getApplicationContext();
    final MasterSecret indexingSecret = masterSecret;
    messageIndexTask = new AsyncTask<Void, Integer, MessageSearchIndexer.IndexStats>() {
      @Override
      protected MessageSearchIndexer.IndexStats doInBackground(Void... params) {
        try {
          return MessageSearchIndexer.indexAll(appContext, indexingSecret, new MessageSearchIndexer.ProgressListener() {
            @Override public void onProgress(int completed, int total, int messages) {
              publishProgress(completed, total, messages);
            }

            @Override public boolean isCancelled() {
              return ConversationListActivity.this.messageIndexCancelRequested ||
                     (ConversationListActivity.this.messageIndexTask != null &&
                      ConversationListActivity.this.messageIndexTask.isCancelled());
            }
          });
        } catch (Exception e) {
          Log.w(TAG, "Unable to build the message search index", e);
          return null;
        }
      }

      @Override
      protected void onProgressUpdate(Integer... values) {
        if (messageIndexProgress == null || values.length < 3) return;
        int completed = values[0];
        int total = values[1];
        int messages = values[2];
        messageIndexProgress.setIndeterminate(total <= 0);
        if (total > 0) {
          messageIndexProgress.setMax(total);
          messageIndexProgress.setProgress(completed);
        }
        messageIndexProgress.setMessage(getString(R.string.message_index_progress, completed, total, messages));
      }

      @Override
      protected void onPostExecute(MessageSearchIndexer.IndexStats stats) {
        finishMessageIndex(stats);
      }

      @Override
      protected void onCancelled(MessageSearchIndexer.IndexStats stats) {
        finishMessageIndex(stats);
      }
    };
    messageIndexTask.execute();
  }

  private void finishMessageIndex(MessageSearchIndexer.IndexStats stats) {
    messageIndexTask = null;
    if (messageIndexProgress != null && messageIndexProgress.isShowing()) messageIndexProgress.dismiss();
    messageIndexProgress = null;

    if (isFinishing()) return;
    invalidateOptionsMenu();
    if (stats == null) {
      Toast.makeText(this, getString(R.string.message_index_failed, 1), Toast.LENGTH_LONG).show();
    } else if (stats.cancelled) {
      Toast.makeText(this, getString(R.string.message_index_partial, stats.completedConversations), Toast.LENGTH_LONG).show();
    } else if (stats.failures > 0) {
      Toast.makeText(this, getString(R.string.message_index_failed, stats.failures), Toast.LENGTH_LONG).show();
    } else {
      Toast.makeText(this, getString(R.string.message_index_complete, stats.indexedMessages,
                                     stats.completedConversations), Toast.LENGTH_LONG).show();
    }
  }

  @Override
  public void onCreateConversation(long threadId, Recipients recipients, int distributionType, long lastSeen) {
    Intent intent = new Intent(this, ConversationActivity.class);
    intent.putExtra(ConversationActivity.RECIPIENTS_EXTRA, recipients.getIds());
    intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, threadId);
    intent.putExtra(ConversationActivity.DISTRIBUTION_TYPE_EXTRA, distributionType);
    intent.putExtra(ConversationActivity.TIMING_EXTRA, System.currentTimeMillis());
    intent.putExtra(ConversationActivity.LAST_SEEN_EXTRA, lastSeen);

    startActivity(intent);
    overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
  }

  @Override
  public void onSwitchToArchive() {
    Intent intent = new Intent(this, ConversationListArchiveActivity.class);
    startActivity(intent);
  }

  private void createGroup() {
    Intent intent = new Intent(this, GroupCreateActivity.class);
    startActivity(intent);
  }

  private void handleSwitchToArchive() {
    onSwitchToArchive();
  }

  private void handleDisplaySettings() {
    Intent preferencesIntent = new Intent(this, ApplicationPreferencesActivity.class);
    startActivity(preferencesIntent);
  }

  private void handleClearPassphrase() {
    Intent intent = new Intent(this, KeyCachingService.class);
    intent.setAction(KeyCachingService.CLEAR_KEY_ACTION);
    startService(intent);
  }

  private void handleImportExport() {
    startActivity(new Intent(this, ImportExportActivity.class));
  }

  private void handleMyIdentity() {
    if (activeSubscriptions.size() < 2) {
      int subscriptionId = activeSubscriptions.get(0).getSubscriptionId();
      handleMyIdentity(subscriptionId);
    }
  }

  private void handleMyIdentity(int subscriptionId) {
    Intent intent = new Intent(this, ViewIdentityActivity.class);
    intent.putExtra("subscription_id", subscriptionId);
    startActivity(intent);
  }

  private void handleMarkAllRead() {
    new AsyncTask<Void, Void, Void>() {
      @Override
      protected Void doInBackground(Void... params) {
        DatabaseFactory.getThreadDatabase(ConversationListActivity.this).setAllThreadsRead();
        MessageNotifier.updateNotification(ConversationListActivity.this, masterSecret);
        return null;
      }
    }.execute();
  }

  private void initializeContactUpdatesReceiver() {
    observer = new ContentObserver(null) {
      @Override
      public void onChange(boolean selfChange) {
        super.onChange(selfChange);
        Log.w(TAG, "Detected android contact data changed, refreshing cache");
        RecipientFactory.clearCache();
        ConversationListActivity.this.runOnUiThread(new Runnable() {
          @Override
          public void run() {
            fragment.getListAdapter().notifyDataSetChanged();
          }
        });
      }
    };

    getContentResolver().registerContentObserver(ContactsContract.Contacts.CONTENT_URI,
                                                 true, observer);
  }
}
