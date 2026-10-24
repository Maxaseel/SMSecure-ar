package org.smssecure.smssecure.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import org.smssecure.smssecure.R;

/** Central channel IDs and creation for Android O and newer. */
public final class NotificationChannels {

  public static final String MESSAGES = "messages";
  public static final String SECURITY = "security";
  public static final String BACKGROUND = "background";

  private NotificationChannels() {}

  public static void create(Context context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

    NotificationManager manager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) return;

    manager.createNotificationChannel(new NotificationChannel(
        MESSAGES, context.getString(R.string.notification_channel_messages), NotificationManager.IMPORTANCE_DEFAULT));
    manager.createNotificationChannel(new NotificationChannel(
        SECURITY, context.getString(R.string.notification_channel_security), NotificationManager.IMPORTANCE_LOW));
    manager.createNotificationChannel(new NotificationChannel(
        BACKGROUND, context.getString(R.string.notification_channel_background), NotificationManager.IMPORTANCE_LOW));
  }
}
