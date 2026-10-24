package org.smssecure.smssecure.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;

/** Compatibility helpers for Android 13's explicit dynamic receiver export flags. */
public final class ReceiverUtil {

  private ReceiverUtil() {}

  public static Intent registerNotExported(Context context, BroadcastReceiver receiver, IntentFilter filter) {
    if (Build.VERSION.SDK_INT >= 33) {
      return context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }
    return context.registerReceiver(receiver, filter);
  }

  public static Intent registerNotExported(Context context, BroadcastReceiver receiver, IntentFilter filter,
                                           String broadcastPermission, Handler scheduler)
  {
    if (Build.VERSION.SDK_INT >= 33) {
      return context.registerReceiver(receiver, filter, broadcastPermission, scheduler, Context.RECEIVER_NOT_EXPORTED);
    }
    return context.registerReceiver(receiver, filter, broadcastPermission, scheduler);
  }
}
