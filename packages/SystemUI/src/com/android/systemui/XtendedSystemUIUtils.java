package com.android.systemui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class XtendedSystemUIUtils {

    private static NotificationManager mNotificationManager;
    private static Integer mNotificationId;

    private static final String TAG = "XtendedSystemUIUtils";

    public static boolean settingStatusBoolean(String settingName, Context ctx) {
        try {
            if (android.provider.Settings.System.getIntForUser(ctx.getContentResolver(),settingName,UserHandle.USER_CURRENT) == 1) {
                return true;
            } else {
                return false;
            }
        } catch (android.provider.Settings.SettingNotFoundException e) {
                return false;
        }
    }

    public static void generateNotification(String contentTitle, String summary,
                                            int icon, String contentText,
                                            String appName, String channelDescription,
                                            Context context) {
        mNotificationId = 122;
        String channelId = "default_channel_id";

        Notification.Builder builder = new Notification.Builder(context, channelId);
        NotificationManager mNotificationManager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        int importance = NotificationManager.IMPORTANCE_HIGH; //Set the importance level
        NotificationChannel notificationChannel = new NotificationChannel(channelId, channelDescription, importance);
        mNotificationManager.createNotificationChannel(notificationChannel);
        builder.setContentTitle(contentTitle)
               .setSmallIcon(icon)
               .setContentText(contentText)
               .setDefaults(Notification.DEFAULT_ALL)
               .setColor(Color.RED)
               .setStyle(new Notification.BigTextStyle().bigText(summary))
               .setTicker(appName)
               .setAutoCancel(true);
        SystemUI.overrideNotificationAppName(context, builder, false);
        Notification notification = builder.build();
        mNotificationManager.createNotificationChannel(notificationChannel);
        mNotificationManager.notify(mNotificationId, notification);
    }

    public static void dismissNotification() {
        Log.d("Dilemmino", "Ciao " + String.valueOf(mNotificationId));
        if (mNotificationId != null)
             Log.d("Dilemmino", "Ciao2 " + String.valueOf(mNotificationId));
            mNotificationManager.cancel(mNotificationId);
    }

}
