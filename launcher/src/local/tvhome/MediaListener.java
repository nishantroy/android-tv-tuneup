package local.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Android only tells an app which media sessions are active if the app has an enabled
 * notification listener. This one exists for that reason alone: it overrides nothing and so
 * never looks at a notification. It is enabled once over adb:
 *
 *   adb shell cmd notification allow_listener local.tvhome/local.tvhome.MediaListener
 */
public final class MediaListener extends NotificationListenerService {}
