/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.keyguard;

import android.annotation.AnyThread;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Typeface;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.ZenModeConfig;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.Log;

import androidx.core.graphics.drawable.IconCompat;
import androidx.slice.Slice;
import androidx.slice.SliceProvider;
import androidx.slice.builders.ListBuilder;
import androidx.slice.builders.ListBuilder.RowBuilder;
import androidx.slice.builders.SliceAction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SystemUIAppComponentFactory;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.omni.OmniJawsClient;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.wakelock.SettableWakeLock;
import com.android.systemui.util.wakelock.WakeLock;

import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

/**
 * Simple Slice provider that shows the current date.
 */
public class KeyguardSliceProvider extends SliceProvider implements
        NextAlarmController.NextAlarmChangeCallback, ZenModeController.Callback,
        NotificationMediaManager.MediaListener, StatusBarStateController.StateListener,
        SystemUIAppComponentFactory.ContextInitializer,
        OmniJawsClient.OmniJawsObserver {

    private String TAG = KeyguardSliceProvider.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final StyleSpan BOLD_STYLE = new StyleSpan(Typeface.BOLD);
    public static final String KEYGUARD_SLICE_URI = "content://com.android.systemui.keyguard/main";
    private static final String KEYGUARD_HEADER_URI =
            "content://com.android.systemui.keyguard/header";
    public static final String KEYGUARD_DATE_URI = "content://com.android.systemui.keyguard/date";
    public static final String KEYGUARD_WEATHER_URI = "content://com.android.systemui.keyguard/weather";
    public static final String KEYGUARD_NEXT_ALARM_URI =
            "content://com.android.systemui.keyguard/alarm";
    public static final String KEYGUARD_DND_URI = "content://com.android.systemui.keyguard/dnd";
    public static final String KEYGUARD_MEDIA_URI =
            "content://com.android.systemui.keyguard/media";
    public static final String KEYGUARD_ACTION_URI =
            "content://com.android.systemui.keyguard/action";

    /**
     * Only show alarms that will ring within N hours.
     */
    @VisibleForTesting
    static final int ALARM_VISIBILITY_HOURS = 12;

    private static final Object sInstanceLock = new Object();
    private static KeyguardSliceProvider sInstance;

    protected final Uri mSliceUri;
    protected final Uri mHeaderUri;
    protected final Uri mDateUri;
    protected final Uri mWeatherUri;
    protected final Uri mAlarmUri;
    protected final Uri mDndUri;
    protected final Uri mMediaUri;
    private final Date mCurrentTime = new Date();
    private final Handler mHandler;
    private final Handler mMediaHandler;
    private final AlarmManager.OnAlarmListener mUpdateNextAlarm = this::updateNextAlarm;
    @Inject
    public DozeParameters mDozeParameters;
    @VisibleForTesting
    protected SettableWakeLock mMediaWakeLock;
    @Inject
    public ZenModeController mZenModeController;
    private String mDatePattern;
    private DateFormat mDateFormat;
    private String mLastText;
    private boolean mRegistered;
    private String mNextAlarm;
    @Inject
    public NextAlarmController mNextAlarmController;
    @Inject
    public AlarmManager mAlarmManager;
    @Inject
    public ContentResolver mContentResolver;
    private int mLsDateSel;
    private String mLsDateSPattern;
    private AlarmManager.AlarmClockInfo mNextAlarmInfo;
    private PendingIntent mPendingIntent;
    @Inject
    public NotificationMediaManager mMediaManager;
    @Inject
    public StatusBarStateController mStatusBarStateController;
    @Inject
    public KeyguardBypassController mKeyguardBypassController;
    private CharSequence mMediaTitle;
    private CharSequence mMediaArtist;
    protected boolean mDozing;
    private int mStatusBarState;
    private boolean mMediaIsVisible;
    private SystemUIAppComponentFactory.ContextAvailableCallback mContextAvailableCallback;

    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherInfo;
    private OmniJawsClient.PackageInfo mPackageInfo;
    private boolean mWeatherEnabled;
    private boolean mShowWeatherSlice;

    /**
     * Receiver responsible for time ticking and updating the date format.
     */
    @VisibleForTesting
    final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_DATE_CHANGED.equals(action)) {
                synchronized (this) {
                    updateClockLocked();
                }
            } else if (Intent.ACTION_LOCALE_CHANGED.equals(action)) {
                synchronized (this) {
                    cleanDateFormatLocked();
                }
            }
        }
    };

    @VisibleForTesting
    final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onTimeChanged() {
                    synchronized (this) {
                        updateClockLocked();
                    }
                }

                @Override
                public void onTimeZoneChanged(TimeZone timeZone) {
                    synchronized (this) {
                        cleanDateFormatLocked();
                    }
                }
            };

    public static KeyguardSliceProvider getAttachedInstance() {
        return KeyguardSliceProvider.sInstance;
    }

    public KeyguardSliceProvider() {
        mHandler = new Handler();
        mMediaHandler = new Handler();
        mSliceUri = Uri.parse(KEYGUARD_SLICE_URI);
        mHeaderUri = Uri.parse(KEYGUARD_HEADER_URI);
        mDateUri = Uri.parse(KEYGUARD_DATE_URI);
        mWeatherUri = Uri.parse(KEYGUARD_WEATHER_URI);
        mAlarmUri = Uri.parse(KEYGUARD_NEXT_ALARM_URI);
        mDndUri = Uri.parse(KEYGUARD_DND_URI);
        mMediaUri = Uri.parse(KEYGUARD_MEDIA_URI);
    }

    @AnyThread
    @Override
    public Slice onBindSlice(Uri sliceUri) {
        Trace.beginSection("KeyguardSliceProvider#onBindSlice");
        Slice slice;
        synchronized (this) {
            ListBuilder builder = new ListBuilder(getContext(), mSliceUri, ListBuilder.INFINITY);
            if (needsMediaLocked()) {
                addMediaLocked(builder);
            }
            addWeather(builder);
            builder.addRow(new RowBuilder(mDateUri).setTitle(mLastText));
            addNextAlarmLocked(builder);
            addZenModeLocked(builder);
            addPrimaryActionLocked(builder);
            slice = builder.build();
        }
        Trace.endSection();
        return slice;
    }

    protected boolean needsMediaLocked() {
        boolean keepWhenAwake = mKeyguardBypassController != null
                && mKeyguardBypassController.getBypassEnabled() && mDozeParameters.getAlwaysOn();
        String currentClock = Settings.Secure.getString(
                mContentResolver, Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE);
        boolean isTypeClockSelected = currentClock == null ? false : currentClock.contains("Type");
        // Show header if music is playing and the status bar is in the shade state. This way, an
        // animation isn't necessary when pressing power and transitioning to AOD.
        boolean keepWhenShade = mStatusBarState == StatusBarState.SHADE && mMediaIsVisible;
        return !TextUtils.isEmpty(mMediaTitle) && mMediaIsVisible && (mDozing || keepWhenAwake
                || keepWhenShade) && !isTypeClockSelected;
    }

    protected void addMediaLocked(ListBuilder listBuilder) {
        if (TextUtils.isEmpty(mMediaTitle)) {
            return;
        }
        ListBuilder.HeaderBuilder headerBuilder = new ListBuilder.HeaderBuilder(mHeaderUri);
        headerBuilder.setTitle(mMediaTitle);
        if (!TextUtils.isEmpty(mMediaArtist)) {
            headerBuilder.setSubtitle(mMediaArtist);
        }
        listBuilder.setHeader(headerBuilder);
    }

    protected void addPrimaryActionLocked(ListBuilder builder) {
        // Add simple action because API requires it; Keyguard handles presenting
        // its own slices so this action + icon are actually never used.
        IconCompat icon = IconCompat.createWithResource(getContext(),
                R.drawable.ic_access_alarms_big);
        SliceAction action = SliceAction.createDeeplink(mPendingIntent, icon,
                ListBuilder.ICON_IMAGE, mLastText);
        RowBuilder primaryActionRow = new RowBuilder(Uri.parse(KEYGUARD_ACTION_URI))
                .setPrimaryAction(action);
        builder.addRow(primaryActionRow);
    }

    protected void addNextAlarmLocked(ListBuilder builder) {
        if (TextUtils.isEmpty(mNextAlarm)) {
            return;
        }
        IconCompat alarmIcon = IconCompat.createWithResource(getContext(),
                R.drawable.ic_access_alarms_big);
        RowBuilder alarmRowBuilder = new RowBuilder(mAlarmUri)
                .setTitle(mNextAlarm)
                .addEndItem(alarmIcon, ListBuilder.ICON_IMAGE);
        builder.addRow(alarmRowBuilder);
    }

    /**
     * Add zen mode (DND) icon to slice if it's enabled.
     * @param builder The slice builder.
     */
    protected void addZenModeLocked(ListBuilder builder) {
        if (!isDndOn()) {
            return;
        }
        RowBuilder dndBuilder = new RowBuilder(mDndUri)
                .setContentDescription(getContext().getResources()
                        .getString(R.string.accessibility_quick_settings_dnd))
                .addEndItem(
                    IconCompat.createWithResource(getContext(), R.drawable.stat_sys_dnd),
                    ListBuilder.ICON_IMAGE);
        builder.addRow(dndBuilder);
    }

    /**
     * Return true if DND is enabled.
     */
    protected boolean isDndOn() {
        return mZenModeController.getZen() != Settings.Global.ZEN_MODE_OFF;
    }

    protected void addWeather(ListBuilder builder) {
        if (!mWeatherClient.isOmniJawsEnabled()) return;
        if (!mWeatherEnabled || !mShowWeatherSlice || mWeatherInfo == null || mPackageInfo == null) {
            return;
        }
        String temperatureText = mWeatherInfo.temp + " " + mWeatherInfo.tempUnits;
        Icon conditionIcon = Icon.createWithResource(
                mPackageInfo.packageName, mPackageInfo.resourceID);
        IconCompat conditionIconCompat = conditionIcon == null ? null
                : IconCompat.createFromIcon(getContext(), conditionIcon);
        RowBuilder weatherRowBuilder = new RowBuilder(mWeatherUri)
                .setTitle(temperatureText)
                .addEndItem(conditionIconCompat, ListBuilder.ICON_IMAGE);
        builder.addRow(weatherRowBuilder);
    }

    @Override
    public void weatherUpdated() {
        queryAndUpdateWeather();
        mContentResolver.notifyChange(mSliceUri, null /* observer */);
    }

    @Override
    public void weatherError(int errorReason) {
        if (DEBUG) Log.d(TAG, "weatherError " + errorReason);
    }

    private void queryAndUpdateWeather() {
        try {
            if (DEBUG) Log.d(TAG, "queryAndUpdateWeather.isOmniJawsEnabled " + mWeatherClient.isOmniJawsEnabled());
            mWeatherClient.queryWeather();
            mWeatherInfo = mWeatherClient.getWeatherInfo();
            setPackageInfo();
            if (DEBUG) Log.w(TAG, "queryAndUpdateWeather mPackageName: " + mPackageInfo.packageName);
            if (DEBUG) Log.w(TAG, "queryAndUpdateWeather mDrawableResID: " + mPackageInfo.resourceID);
        } catch(Exception e) {
            // Do nothing
        }
    }

    private void setPackageInfo() {
        mPackageInfo = null;
        if (mWeatherInfo != null){
              Drawable conditionImage = mWeatherClient.getWeatherConditionImage(mWeatherInfo.conditionCode);
              mPackageInfo = mWeatherClient.getPackageInfo();
        }
    }

    private XSettingsObserver mXSettingsObserver;

    private class XSettingsObserver extends ContentObserver {
        XSettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mContentResolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.LOCKSCREEN_DATE_SELECTION),
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.OMNI_LOCKSCREEN_WEATHER_ENABLED),
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.OMNIJAWS_WEATHER_ICON_PACK),
                    false, this, UserHandle.USER_ALL);

            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.AICP_LOCKSCREEN_WEATHER_STYLE),
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            if (uri.equals(Settings.Secure.getUriFor(Settings.Secure.LOCKSCREEN_DATE_SELECTION))) {
                updateDateSkeleton();
                mContentResolver.notifyChange(mSliceUri, null /* observer */);
            } else if (uri.equals(Settings.System.getUriFor(Settings.System.OMNI_LOCKSCREEN_WEATHER_ENABLED))) {
                updateLockscreenWeather();
                mContentResolver.notifyChange(mSliceUri, null /* observer */);
            } else if (uri.equals(Settings.System.getUriFor(Settings.System.OMNIJAWS_WEATHER_ICON_PACK))) {
                queryAndUpdateWeather();
                mContentResolver.notifyChange(mSliceUri, null /* observer */);
            } else if (uri.equals(Settings.System.getUriFor(Settings.System.AICP_LOCKSCREEN_WEATHER_STYLE))) {
                updateLockscreenWeatherStyle();
                mContentResolver.notifyChange(mSliceUri, null /* observer */);
            }
        }

        public void updateDateSkeleton() {
            mLsDateSel = Settings.Secure.getIntForUser(mContentResolver, Settings.Secure.LOCKSCREEN_DATE_SELECTION, 0, UserHandle.USER_CURRENT);
            switch (mLsDateSel) {
            case 4: case 6: case 8:
                 mDatePattern = getContext().getString(R.string.abbrev_wday_day_no_year);
                 break;
            case 5: case 7: case 9:
                 mDatePattern = getContext().getString(R.string.abbrev_wday_no_year);
                 break;
            case 10:
                 mDatePattern = getContext().getString(R.string.abbrev_wday_month_no_year);
                 break;
            default:
                 mDatePattern = getContext().getString(R.string.system_ui_aod_date_pattern);
                 break;
            }
            updateClockLocked();
        }

        public void updateLockscreenWeather() {
            mWeatherEnabled = Settings.System.getIntForUser(mContentResolver, Settings.System.OMNI_LOCKSCREEN_WEATHER_ENABLED, 0, UserHandle.USER_CURRENT) != 0;
        }

        public void updateLockscreenWeatherStyle() {
            mShowWeatherSlice = Settings.System.getIntForUser(mContentResolver, Settings.System.AICP_LOCKSCREEN_WEATHER_STYLE, 0, UserHandle.USER_CURRENT) != 0;
        }
    }

    @Override
    public boolean onCreateSliceProvider() {
        mContextAvailableCallback.onContextAvailable(getContext());
        inject();
        synchronized (KeyguardSliceProvider.sInstanceLock) {
            KeyguardSliceProvider oldInstance = KeyguardSliceProvider.sInstance;
            if (oldInstance != null) {
                oldInstance.onDestroy();
            }
            mDatePattern = getContext().getString(R.string.system_ui_aod_date_pattern);
            mPendingIntent = PendingIntent.getActivity(getContext(), 0,
                    new Intent(getContext(), KeyguardSliceProvider.class), 0);
            mMediaManager.addCallback(this);
            mStatusBarStateController.addCallback(this);
            mNextAlarmController.addCallback(this);
            mZenModeController.addCallback(this);
            mXSettingsObserver = new XSettingsObserver(mHandler);
            mXSettingsObserver.updateDateSkeleton();
            mXSettingsObserver.updateLockscreenWeatherStyle();
            mXSettingsObserver.updateLockscreenWeather();
            mXSettingsObserver.observe();
            mWeatherClient = new OmniJawsClient(getContext());
            mWeatherClient.addSettingsObserver();
            mWeatherClient.addObserver(this);
            queryAndUpdateWeather();
            KeyguardSliceProvider.sInstance = this;
            registerClockUpdate();
            updateClockLocked();
        }
        return true;
    }

    @VisibleForTesting
    protected void inject() {
        SystemUIFactory.getInstance().getRootComponent().inject(this);
        mMediaWakeLock = new SettableWakeLock(WakeLock.createPartial(getContext(), "media"),
                "media");
    }

    @VisibleForTesting
    protected void onDestroy() {
        synchronized (KeyguardSliceProvider.sInstanceLock) {
            mNextAlarmController.removeCallback(this);
            mZenModeController.removeCallback(this);
            mMediaWakeLock.setAcquired(false);
            mAlarmManager.cancel(mUpdateNextAlarm);
            if (mRegistered) {
                mRegistered = false;
                getKeyguardUpdateMonitor().removeCallback(mKeyguardUpdateMonitorCallback);
                getContext().unregisterReceiver(mIntentReceiver);
            }
            KeyguardSliceProvider.sInstance = null;
        }
    }

    @Override
    public void onZenChanged(int zen) {
        notifyChange();
    }

    @Override
    public void onConfigChanged(ZenModeConfig config) {
        notifyChange();
    }

    private void updateNextAlarm() {
        synchronized (this) {
            if (withinNHoursLocked(mNextAlarmInfo, ALARM_VISIBILITY_HOURS)) {
                String pattern = android.text.format.DateFormat.is24HourFormat(getContext(),
                        ActivityManager.getCurrentUser()) ? "HH:mm" : "h:mm";
                mNextAlarm = android.text.format.DateFormat.format(pattern,
                        mNextAlarmInfo.getTriggerTime()).toString();
            } else {
                mNextAlarm = "";
            }
        }
        notifyChange();
    }

    private boolean withinNHoursLocked(AlarmManager.AlarmClockInfo alarmClockInfo, int hours) {
        if (alarmClockInfo == null) {
            return false;
        }

        long limit = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(hours);
        return mNextAlarmInfo.getTriggerTime() <= limit;
    }

    /**
     * Registers a broadcast receiver for clock updates, include date, time zone and manually
     * changing the date/time via the settings app.
     */
    @VisibleForTesting
    protected void registerClockUpdate() {
        synchronized (this) {
            if (mRegistered) {
                return;
            }

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_DATE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            getContext().registerReceiver(mIntentReceiver, filter, null /* permission*/,
                    null /* scheduler */);
            getKeyguardUpdateMonitor().registerCallback(mKeyguardUpdateMonitorCallback);
            mRegistered = true;
        }
    }

    @VisibleForTesting
    boolean isRegistered() {
        synchronized (this) {
            return mRegistered;
        }
    }

    protected void updateClockLocked() {
        final String text = getFormattedDateLocked();
        if (!text.equals(mLastText)) {
            mLastText = text;
            notifyChange();
        }
    }

    protected String getFormattedDateLocked() {
        final Locale l = Locale.getDefault();
        DateFormat format = DateFormat.getInstanceForSkeleton(mDatePattern, l);
        format.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE);
        mDateFormat = format;
        mCurrentTime.setTime(System.currentTimeMillis());
        return mDateFormat.format(mCurrentTime);
    }

    @VisibleForTesting
    void cleanDateFormatLocked() {
        mDateFormat = null;
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        synchronized (this) {
            mNextAlarmInfo = nextAlarm;
            mAlarmManager.cancel(mUpdateNextAlarm);

            long triggerAt = mNextAlarmInfo == null ? -1 : mNextAlarmInfo.getTriggerTime()
                    - TimeUnit.HOURS.toMillis(ALARM_VISIBILITY_HOURS);
            if (triggerAt > 0) {
                mAlarmManager.setExact(AlarmManager.RTC, triggerAt, "lock_screen_next_alarm",
                        mUpdateNextAlarm, mHandler);
            }
        }
        updateNextAlarm();
    }

    private KeyguardUpdateMonitor getKeyguardUpdateMonitor() {
        return Dependency.get(KeyguardUpdateMonitor.class);
    }

    /**
     * Called whenever new media metadata is available.
     * @param metadata New metadata.
     */
    @Override
    public void onPrimaryMetadataOrStateChanged(MediaMetadata metadata,
            @PlaybackState.State int state) {
        synchronized (this) {
            boolean nextVisible = NotificationMediaManager.isPlayingState(state);
            mMediaHandler.removeCallbacksAndMessages(null);
            if (mMediaIsVisible && !nextVisible && mStatusBarState != StatusBarState.SHADE) {
                // We need to delay this event for a few millis when stopping to avoid jank in the
                // animation. The media app might not send its update when buffering, and the slice
                // would end up without a header for 0.5 second.
                mMediaWakeLock.setAcquired(true);
                mMediaHandler.postDelayed(() -> {
                    synchronized (this) {
                        updateMediaStateLocked(metadata, state);
                        mMediaWakeLock.setAcquired(false);
                    }
                }, 2000);
            } else {
                mMediaWakeLock.setAcquired(false);
                updateMediaStateLocked(metadata, state);
            }
        }
    }

    private void updateMediaStateLocked(MediaMetadata metadata, @PlaybackState.State int state) {
        boolean nextVisible = NotificationMediaManager.isPlayingState(state);
        CharSequence title = null;
        if (metadata != null) {
            title = metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
            if (TextUtils.isEmpty(title)) {
                title = getContext().getResources().getString(R.string.music_controls_no_title);
            }
        }
        CharSequence artist = metadata == null ? null : metadata.getText(
                MediaMetadata.METADATA_KEY_ARTIST);

        if (nextVisible == mMediaIsVisible && TextUtils.equals(title, mMediaTitle)
                && TextUtils.equals(artist, mMediaArtist)) {
            return;
        }
        mMediaTitle = title;
        mMediaArtist = artist;
        mMediaIsVisible = nextVisible;
        notifyChange();
    }

    protected void notifyChange() {
        mContentResolver.notifyChange(mSliceUri, null /* observer */);
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        final boolean notify;
        synchronized (this) {
            boolean neededMedia = needsMediaLocked();
            mDozing = isDozing;
            notify = neededMedia != needsMediaLocked();
        }
        if (notify) {
            notifyChange();
        }
    }

    @Override
    public void onStateChanged(int newState) {
        final boolean notify;
        synchronized (this) {
            boolean needsMedia = needsMediaLocked();
            mStatusBarState = newState;
            notify = needsMedia != needsMediaLocked();
        }
        if (notify) {
            notifyChange();
        }
    }

    @Override
    public void setContextAvailableCallback(
            SystemUIAppComponentFactory.ContextAvailableCallback callback) {
        mContextAvailableCallback = callback;
    }
}
