/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;
import static android.provider.Settings.System.QS_SHOW_BATTERY_PERCENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.annotation.ColorInt;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.service.notification.ZenModeConfig;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.util.Pair;
import android.view.ContextThemeWrapper;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Space;
import android.widget.TextView;
import android.database.ContentObserver;
import android.content.ContentResolver;
import android.os.UserHandle;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.xtended.FileUtils;
import com.android.internal.util.xtended.XtendedUtils;
import com.android.systemui.Dependency;
import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.DualToneHandler;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.privacy.OngoingPrivacyChip;
import com.android.systemui.privacy.PrivacyChipBuilder;
import com.android.systemui.privacy.PrivacyChipEvent;
import com.android.systemui.privacy.PrivacyItem;
import com.android.systemui.privacy.PrivacyItemController;
import com.android.systemui.qs.QSDetail.Callback;
import com.android.systemui.qs.carrier.QSCarrierGroup;
import com.android.systemui.settings.BrightnessController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.info.DataUsageView;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager;
import com.android.systemui.statusbar.phone.StatusBarWindowView;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.DateView;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.tuner.TunerService;

import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * View that contains the top-most bits of the screen (primarily the status bar with date, time, and
 * battery) and also contains the {@link QuickQSPanel} along with some of the panel's inner
 * contents.
 */
public class QuickStatusBarHeader extends RelativeLayout implements
        View.OnClickListener, NextAlarmController.NextAlarmChangeCallback,
        ZenModeController.Callback, LifecycleOwner, TunerService.Tunable {
    private static final String TAG = "QuickStatusBarHeader";
    private static final boolean DEBUG = false;
    public static final String QS_SHOW_INFO_HEADER = "qs_show_info_header";
    /** Delay for auto fading out the long press tooltip after it's fully visible (in ms). */
    private static final long AUTO_FADE_OUT_DELAY_MS = DateUtils.SECOND_IN_MILLIS * 6;
    private static final int FADE_ANIMATION_DURATION_MS = 300;
    private static final int TOOLTIP_NOT_YET_SHOWN_COUNT = 0;
    public static final int MAX_TOOLTIP_SHOWN_COUNT = 2;

    private final Handler mHandler = new Handler();

    private static final String QS_SHOW_AUTO_BRIGHTNESS =
            Settings.Secure.QS_SHOW_AUTO_BRIGHTNESS;
    public static final String QQS_SHOW_BRIGHTNESS_SLIDER = "qqs_show_brightness_slider";

    private final NextAlarmController mAlarmController;
    private final ZenModeController mZenController;
    private final StatusBarIconController mStatusBarIconController;
    private final ActivityStarter mActivityStarter;

    private QSPanel mQsPanel;

    private boolean mExpanded;
    private boolean mListening;
    private boolean mQsDisabled;

    private QSCarrierGroup mCarrierGroup;
    protected QuickQSPanel mHeaderQsPanel;
    protected QSTileHost mHost;
    private TintedIconManager mIconManager;
    private TouchAnimator mStatusIconsAlphaAnimator;
    private TouchAnimator mHeaderTextContainerAlphaAnimator;
    private TouchAnimator mPrivacyChipAlphaAnimator;
    private DualToneHandler mDualToneHandler;
    private final CommandQueue mCommandQueue;

    private View mSystemIconsView;
    private View mQuickQsStatusIcons;
    private View mHeaderTextContainerView;

    private int mRingerMode = AudioManager.RINGER_MODE_NORMAL;
    private AlarmManager.AlarmClockInfo mNextAlarm;

    // Data Usage
    private View mDataUsageLayout;
    private ImageView mDataUsageImage;
    private DataUsageView mDataUsageView;

    private ImageView mNextAlarmIcon;
    /** {@link TextView} containing the actual text indicating when the next alarm will go off. */
    private TextView mNextAlarmTextView;
    private View mNextAlarmContainer;
    private View mStatusSeparator;
    private ImageView mRingerModeIcon;
    private TextView mRingerModeTextView;
    private View mRingerContainer;
    private Clock mClockView;
    private DateView mDateView;
    private OngoingPrivacyChip mPrivacyChip;
    private Space mSpace;
    private BatteryMeterView mBatteryRemainingIcon;
    private RingerModeTracker mRingerModeTracker;
    private boolean mAllIndicatorsEnabled;
    private boolean mMicCameraIndicatorsEnabled;
    private BroadcastDispatcher mBroadcastDispatcher;

    private PrivacyItemController mPrivacyItemController;
    private final UiEventLogger mUiEventLogger;
    // Used for RingerModeTracker
    private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);

    private TextView mSystemInfoText;
    private int mSystemInfoMode;
    private ImageView mSystemInfoIcon;
    private View mSystemInfoLayout;
    private String mSysCPUTemp;
    private String mSysBatTemp;
    private String mSysGPUFreq;
    private String mSysGPULoad;
    private int mSysCPUTempMultiplier;
    private int mSysBatTempMultiplier;

    // QS Logo
    private ImageView mXtendedLogo;
    private ImageView mXtendedLogoRight;
    private int mLogoStyle;
    private int mShowLogo;
    private int mLogoColor;

    protected ContentResolver mContentResolver;

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = getContext().getContentResolver();
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_SYSTEM_INFO), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                   .getUriFor(Settings.System.QS_PANEL_LOGO), false,
                   this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                   .getUriFor(Settings.System.QS_PANEL_LOGO_STYLE), false,
                   this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                   .getUriFor(Settings.System.QS_PANEL_LOGO_COLOR), false,
		    this, UserHandle.USER_ALL);
            }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }
    private SettingsObserver mSettingsObserver = new SettingsObserver(mHandler);
    private boolean mHasTopCutout = false;
    private int mStatusBarPaddingTop = 0;
    private int mRoundedCornerPadding = 0;
    private int mContentMarginStart;
    private int mContentMarginEnd;
    private int mWaterfallTopInset;
    private int mCutOutPaddingLeft;
    private int mCutOutPaddingRight;
    private float mExpandedHeaderAlpha = 1.0f;
    private float mKeyguardExpansionFraction;
    private boolean mPrivacyChipLogged = false;

    private PrivacyItemController.Callback mPICCallback = new PrivacyItemController.Callback() {
        @Override
        public void onPrivacyItemsChanged(List<PrivacyItem> privacyItems) {
            mPrivacyChip.setPrivacyList(privacyItems);
            setChipVisibility(!privacyItems.isEmpty());
        }

        @Override
        public void onFlagAllChanged(boolean flag) {
            if (mAllIndicatorsEnabled != flag) {
                mAllIndicatorsEnabled = flag;
                update();
            }
        }

        @Override
        public void onFlagMicCameraChanged(boolean flag) {
            if (mMicCameraIndicatorsEnabled != flag) {
                mMicCameraIndicatorsEnabled = flag;
                update();
            }
        }

        private void update() {
            StatusIconContainer iconContainer = requireViewById(R.id.statusIcons);
            iconContainer.setIgnoredSlots(getIgnoredIconSlots());
            setChipVisibility(!mPrivacyChip.getPrivacyList().isEmpty());
        }
    };

    private View mQuickQsBrightness;
    private BrightnessController mBrightnessController;
    private boolean mIsQsAutoBrightnessEnabled;

    private int mBrightnessSlider = 2;

    @Inject
    public QuickStatusBarHeader(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            NextAlarmController nextAlarmController, ZenModeController zenModeController,
            StatusBarIconController statusBarIconController,
            ActivityStarter activityStarter, PrivacyItemController privacyItemController,
            CommandQueue commandQueue, RingerModeTracker ringerModeTracker,
            UiEventLogger uiEventLogger, BroadcastDispatcher broadcastDispatcher) {
        super(context, attrs);
        mAlarmController = nextAlarmController;
        mZenController = zenModeController;
        mStatusBarIconController = statusBarIconController;
        mActivityStarter = activityStarter;
        mPrivacyItemController = privacyItemController;
        mDualToneHandler = new DualToneHandler(
                new ContextThemeWrapper(context, R.style.QSHeaderTheme));
        mCommandQueue = commandQueue;
        mRingerModeTracker = ringerModeTracker;
        mSystemInfoMode = getQsSystemInfoMode();
        mContentResolver = context.getContentResolver();
        mSettingsObserver.observe();
        mUiEventLogger = uiEventLogger;
        mBroadcastDispatcher = broadcastDispatcher;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mSystemIconsView = findViewById(R.id.quick_status_bar_system_icons);
        mQuickQsStatusIcons = findViewById(R.id.quick_qs_status_icons);
        StatusIconContainer iconContainer = findViewById(R.id.statusIcons);

        mAllIndicatorsEnabled = mPrivacyItemController.getAllIndicatorsAvailable();
        mMicCameraIndicatorsEnabled = mPrivacyItemController.getMicCameraAvailable();

        // Ignore privacy icons because they show in the space above QQS
        iconContainer.addIgnoredSlots(getIgnoredIconSlots());
        iconContainer.setShouldRestrictIcons(false);
        mIconManager = new TintedIconManager(iconContainer, mCommandQueue);

        mQuickQsBrightness = findViewById(R.id.quick_qs_brightness_bar);
        mBrightnessController = new BrightnessController(getContext(),
                mQuickQsBrightness.findViewById(R.id.brightness_icon),
                mQuickQsBrightness.findViewById(R.id.brightness_slider),
                mBroadcastDispatcher);

        // Views corresponding to the header info section (e.g. ringer and next alarm).
        mHeaderTextContainerView = findViewById(R.id.header_text_container);
        mStatusSeparator = findViewById(R.id.status_separator);
        mNextAlarmIcon = findViewById(R.id.next_alarm_icon);
        mNextAlarmTextView = findViewById(R.id.next_alarm_text);
        mNextAlarmContainer = findViewById(R.id.alarm_container);
        mNextAlarmContainer.setOnClickListener(this::onClick);
        mRingerModeIcon = findViewById(R.id.ringer_mode_icon);
        mRingerModeTextView = findViewById(R.id.ringer_mode_text);
        mRingerContainer = findViewById(R.id.ringer_container);
        mRingerContainer.setOnClickListener(this::onClick);
        mPrivacyChip = findViewById(R.id.privacy_chip);
        mPrivacyChip.setOnClickListener(this::onClick);
        mCarrierGroup = findViewById(R.id.carrier_group);
        mSystemInfoLayout = findViewById(R.id.system_info_layout);
        mSystemInfoIcon = findViewById(R.id.system_info_icon);
        mSystemInfoText = findViewById(R.id.system_info_text);
        mXtendedLogo = findViewById(R.id.qs_panel_logo);
	mXtendedLogoRight = findViewById(R.id.qs_panel_logo_right);

        updateResources();

        Rect tintArea = new Rect(0, 0, 0, 0);
        int colorForeground = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.colorForeground);
        float intensity = getColorIntensity(colorForeground);
        int fillColor = mDualToneHandler.getSingleColor(intensity);

        // Set light text on the header icons because they will always be on a black background
        applyDarkness(R.id.clock, tintArea, 0, DarkIconDispatcher.DEFAULT_ICON_TINT);

        // Set the correct tint for the status icons so they contrast
        mIconManager.setTint(fillColor);
        mNextAlarmIcon.setImageTintList(ColorStateList.valueOf(fillColor));
        mRingerModeIcon.setImageTintList(ColorStateList.valueOf(fillColor));
        mSystemInfoIcon.setImageTintList(ColorStateList.valueOf(fillColor));

        mClockView = findViewById(R.id.clock);
        mClockView.setOnClickListener(this);
	mClockView.setQsHeader();
        mDateView = findViewById(R.id.date);
        mDateView.setOnClickListener(this);
        mDataUsageLayout = findViewById(R.id.daily_data_usage_layout);
        mDataUsageImage = findViewById(R.id.daily_data_usage_icon);
        mDataUsageView = findViewById(R.id.data_sim_usage);

        updateDataUsageImage();
        // Set the correct tint for the data usage icons so they contrast
        mDataUsageImage.setImageTintList(ColorStateList.valueOf(fillColor));
        mSpace = findViewById(R.id.space);

        // Tint for the battery icons are handled in setupHost()
        mBatteryRemainingIcon = findViewById(R.id.batteryRemainingIcon);
        mBatteryRemainingIcon.setIsQsHeader(true);
        mBatteryRemainingIcon.setPercentShowMode(getBatteryPercentMode());
        mBatteryRemainingIcon.setOnClickListener(this);
        mRingerModeTextView.setSelected(true);
        mNextAlarmTextView.setSelected(true);
        updateSettings();

        Dependency.get(TunerService.class).addTunable(this,
                StatusBarIconController.ICON_BLACKLIST,
                QS_SHOW_AUTO_BRIGHTNESS, QQS_SHOW_BRIGHTNESS_SLIDER);
    }

    public QuickQSPanel getHeaderQsPanel() {
        return mHeaderQsPanel;
    }

    private List<String> getIgnoredIconSlots() {
        ArrayList<String> ignored = new ArrayList<>();
        if (getChipEnabled()) {
            ignored.add(mContext.getResources().getString(
                    com.android.internal.R.string.status_bar_camera));
            ignored.add(mContext.getResources().getString(
                    com.android.internal.R.string.status_bar_microphone));
            if (mAllIndicatorsEnabled) {
                ignored.add(mContext.getResources().getString(
                        com.android.internal.R.string.status_bar_location));
            }
        }

        return ignored;
    }

    private void updateStatusText() {
        boolean changed = updateRingerStatus() || updateAlarmStatus();

        if (changed) {
            boolean alarmVisible = mNextAlarmTextView.getVisibility() == View.VISIBLE;
            boolean ringerVisible = mRingerModeTextView.getVisibility() == View.VISIBLE;
            mStatusSeparator.setVisibility(alarmVisible && ringerVisible ? View.VISIBLE
                    : View.GONE);
        }
    }

    private void setChipVisibility(boolean chipVisible) {
        if (chipVisible && getChipEnabled()) {
            mPrivacyChip.setVisibility(View.VISIBLE);
            // Makes sure that the chip is logged as viewed at most once each time QS is opened
            // mListening makes sure that the callback didn't return after the user closed QS
            if (!mPrivacyChipLogged && mListening) {
                mPrivacyChipLogged = true;
                mUiEventLogger.log(PrivacyChipEvent.ONGOING_INDICATORS_CHIP_VIEW);
            }
        } else {
            mPrivacyChip.setVisibility(View.GONE);
        }
    }

    private int getQsSystemInfoMode() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.QS_SYSTEM_INFO, 0);
    }

    private void updateSystemInfoText() {
        mSystemInfoText.setVisibility(View.GONE);
        mSystemInfoIcon.setVisibility(View.GONE);
        if (mSystemInfoMode == 0) return;
        int defaultMultiplier = 1;
        String systemInfoText = "";
        switch (mSystemInfoMode) {
            case 1:
                mSystemInfoIcon.setImageDrawable(getContext().getDrawable(R.drawable.ic_thermometer));
                systemInfoText = getSystemInfo(mSysCPUTemp, mSysCPUTempMultiplier, "\u2103", true);
                break;
            case 2:
                mSystemInfoIcon.setImageDrawable(getContext().getDrawable(R.drawable.ic_thermometer));
                systemInfoText = getSystemInfo(mSysBatTemp, mSysBatTempMultiplier, "\u2103", true);
                break;
            case 3:
                mSystemInfoIcon.setImageDrawable(getContext().getDrawable(R.drawable.ic_gpu));
                systemInfoText = getSystemInfo(mSysGPUFreq, defaultMultiplier, "Mhz", true);
                break;
            case 4:
                mSystemInfoIcon.setImageDrawable(getContext().getDrawable(R.drawable.ic_gpu));
                systemInfoText = getSystemInfo(mSysGPULoad, defaultMultiplier, "", false);
                break;
        }
        if (systemInfoText != null && !systemInfoText.isEmpty()) {
            mSystemInfoText.setText(systemInfoText);
            mSystemInfoIcon.setVisibility(View.VISIBLE);
            mSystemInfoText.setVisibility(View.VISIBLE);
        }
    }

    private String getSystemInfo(String sysPath, int multiplier, String unit, boolean returnFormatted) {
        if (!sysPath.isEmpty() && FileUtils.fileExists(sysPath)) {
            String value = FileUtils.readOneLine(sysPath);
            return returnFormatted ? String.format("%s", Integer.parseInt(value) / multiplier) + unit : value;
        }
        return null;
    }

    private boolean updateRingerStatus() {
        boolean isOriginalVisible = mRingerModeTextView.getVisibility() == View.VISIBLE;
        CharSequence originalRingerText = mRingerModeTextView.getText();

        boolean ringerVisible = false;
        if (!ZenModeConfig.isZenOverridingRinger(mZenController.getZen(),
                mZenController.getConsolidatedPolicy())) {
            if (mRingerMode == AudioManager.RINGER_MODE_VIBRATE) {
                mRingerModeIcon.setImageResource(R.drawable.ic_volume_ringer_vibrate);
                mRingerModeTextView.setText(R.string.qs_status_phone_vibrate);
                ringerVisible = true;
            } else if (mRingerMode == AudioManager.RINGER_MODE_SILENT) {
                mRingerModeIcon.setImageResource(R.drawable.ic_volume_ringer_mute);
                mRingerModeTextView.setText(R.string.qs_status_phone_muted);
                ringerVisible = true;
            }
        }
        mRingerModeIcon.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);
        mRingerModeTextView.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);
        mRingerContainer.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);

        return isOriginalVisible != ringerVisible ||
                !Objects.equals(originalRingerText, mRingerModeTextView.getText());
    }

    private boolean updateAlarmStatus() {
        boolean isOriginalVisible = mNextAlarmTextView.getVisibility() == View.VISIBLE;
        CharSequence originalAlarmText = mNextAlarmTextView.getText();

        boolean alarmVisible = false;
        if (mNextAlarm != null) {
            alarmVisible = true;
            mNextAlarmTextView.setText(formatNextAlarm(mNextAlarm));
        }
        mNextAlarmIcon.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);
        mNextAlarmTextView.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);
        mNextAlarmContainer.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);

        return isOriginalVisible != alarmVisible ||
                !Objects.equals(originalAlarmText, mNextAlarmTextView.getText());
    }

    private void applyDarkness(int id, Rect tintArea, float intensity, int color) {
        View v = findViewById(id);
        if (v instanceof DarkReceiver) {
            ((DarkReceiver) v).onDarkChanged(tintArea, intensity, color);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();

        // Update color schemes in landscape to use wallpaperTextColor
        boolean shouldUseWallpaperTextColor =
                newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    /**
     * The height of QQS should always be the status bar height + 128dp. This is normally easy, but
     * when there is a notch involved the status bar can remain a fixed pixel size.
     */
    private void updateMinimumHeight() {
        int sbHeight = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        int qqsHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.qs_quick_header_panel_height);

        if (mBrightnessSlider != 0) {
            qqsHeight += mContext.getResources().getDimensionPixelSize(
                    R.dimen.brightness_mirror_height)
                    + mContext.getResources().getDimensionPixelSize(
                    R.dimen.qs_tile_margin_top);
        }

        setMinimumHeight(sbHeight + qqsHeight);
    }

    private void updateResources() {
        Resources resources = mContext.getResources();
        updateMinimumHeight();

        mRoundedCornerPadding = resources.getDimensionPixelSize(
                R.dimen.rounded_corner_content_padding);
        mStatusBarPaddingTop = resources.getDimensionPixelSize(R.dimen.status_bar_padding_top);

        // Update height for a few views, especially due to landscape mode restricting space.
        /*mHeaderTextContainerView.getLayoutParams().height =
                resources.getDimensionPixelSize(R.dimen.qs_header_tooltip_height);
        mHeaderTextContainerView.setLayoutParams(mHeaderTextContainerView.getLayoutParams());*/

        mSystemIconsView.getLayoutParams().height = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height);
        mSystemIconsView.setLayoutParams(mSystemIconsView.getLayoutParams());

        RelativeLayout.LayoutParams headerPanel = (RelativeLayout.LayoutParams)
                mHeaderQsPanel.getLayoutParams();
        headerPanel.addRule(RelativeLayout.BELOW, R.id.quick_qs_status_icons);

        RelativeLayout.LayoutParams lpQuickQsBrightness = (RelativeLayout.LayoutParams)
                mQuickQsBrightness.getLayoutParams();
        lpQuickQsBrightness.addRule(RelativeLayout.BELOW, R.id.header_text_container);

        if (mBrightnessSlider != 0) {
            if (mBrightnessSlider == 1) {
                headerPanel.addRule(RelativeLayout.BELOW, R.id.quick_qs_brightness_bar);
            } else if (mBrightnessSlider == 2) {
                lpQuickQsBrightness.addRule(RelativeLayout.BELOW, R.id.quick_qs_panel);
            }
            if (mIsQsAutoBrightnessEnabled && resources.getBoolean(
                    com.android.internal.R.bool.config_automatic_brightness_available)) {
                mQuickQsBrightness.findViewById(R.id.brightness_icon).setVisibility(View.VISIBLE);
            } else {
                mQuickQsBrightness.findViewById(R.id.brightness_icon).setVisibility(View.GONE);
            }
            mQuickQsBrightness.setVisibility(View.VISIBLE);
        } else {
            mQuickQsBrightness.setVisibility(View.GONE);
        }

        mHeaderQsPanel.setLayoutParams(headerPanel);
        mQuickQsBrightness.setLayoutParams(lpQuickQsBrightness);

        ViewGroup.LayoutParams lp = getLayoutParams();
        if (mQsDisabled) {
            lp.height = resources.getDimensionPixelSize(
                    com.android.internal.R.dimen.quick_qs_offset_height);
        } else {
            lp.height = WRAP_CONTENT;
        }
        setLayoutParams(lp);

        updateStatusIconAlphaAnimator();
        updateHeaderTextContainerAlphaAnimator();
        updatePrivacyChipAlphaAnimator();

    }

      private void updateSettings() {
        Resources resources = mContext.getResources();
        mSysCPUTemp = resources.getString(
                  com.android.internal.R.string.config_sysCPUTemp);
        mSysBatTemp = resources.getString(
                  com.android.internal.R.string.config_sysBatteryTemp);
        mSysGPUFreq = resources.getString(
                  com.android.internal.R.string.config_sysGPUFreq);
        mSysGPULoad = resources.getString(
                  com.android.internal.R.string.config_sysGPULoad);
        mSysCPUTempMultiplier = resources.getInteger(
                  com.android.internal.R.integer.config_sysCPUTempMultiplier);
        mSysBatTempMultiplier = resources.getInteger(
                  com.android.internal.R.integer.config_sysBatteryTempMultiplier);

        mSystemInfoMode = getQsSystemInfoMode();
        updateSystemInfoText();
        updateResources();
        updateDataUsageView();
        updateDataUsageImage();
        updateLogoSettings();
     }

    private void updateDataUsageView() {
        if (mDataUsageView.isDataUsageEnabled() != 0) {
            if (XtendedUtils.isConnected(mContext)) {
                DataUsageView.updateUsage();
                mDataUsageLayout.setVisibility(View.VISIBLE);
                mDataUsageImage.setVisibility(View.VISIBLE);
                mDataUsageView.setVisibility(View.VISIBLE);
            } else {
                mDataUsageView.setVisibility(View.GONE);
                mDataUsageImage.setVisibility(View.GONE);
                mDataUsageLayout.setVisibility(View.GONE);
            }
        } else {
            mDataUsageView.setVisibility(View.GONE);
            mDataUsageImage.setVisibility(View.GONE);
            mDataUsageLayout.setVisibility(View.GONE);
        }
    }

    public void updateDataUsageImage() {
        if (mDataUsageView.isDataUsageEnabled() == 0) {
            mDataUsageImage.setVisibility(View.GONE);
        } else {
            if (XtendedUtils.isWiFiConnected(mContext)) {
                mDataUsageImage.setImageDrawable(mContext.getDrawable(R.drawable.ic_data_usage_wifi));
            } else {
                mDataUsageImage.setImageDrawable(mContext.getDrawable(R.drawable.ic_data_usage_cellular));
            }
            mDataUsageImage.setVisibility(View.VISIBLE);
        }
    }

    private void updateStatusIconAlphaAnimator() {
        mStatusIconsAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mQuickQsStatusIcons, "alpha", 1, 0, 0)
                .build();
    }

    private void updateHeaderTextContainerAlphaAnimator() {
        mHeaderTextContainerAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mHeaderTextContainerView, "alpha", 0, 0, mExpandedHeaderAlpha)
                .build();
    }

    private void updatePrivacyChipAlphaAnimator() {
        mPrivacyChipAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mPrivacyChip, "alpha", 1, 0, 1)
                .build();
    }

    private int getBatteryPercentMode() {
        boolean showBatteryPercent = Settings.System
                .getIntForUser(getContext().getContentResolver(),
                QS_SHOW_BATTERY_PERCENT, 0, UserHandle.USER_CURRENT) == 1;
        return showBatteryPercent ?
               BatteryMeterView.MODE_ON : BatteryMeterView.MODE_ESTIMATE;
    }

    public void setBatteryPercentMode() {
        mBatteryRemainingIcon.setPercentShowMode(getBatteryPercentMode());
    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        mHeaderQsPanel.setExpanded(expanded);
	mDateView.setVisibility(mClockView.isClockDateEnabled() ? View.INVISIBLE : View.VISIBLE);
        updateSystemInfoText();
        updateEverything();
        updateDataUsageView();
    }

    /**
     * Animates the inner contents based on the given expansion details.
     *
     * @param forceExpanded whether we should show the state expanded forcibly
     * @param expansionFraction how much the QS panel is expanded/pulled out (up to 1f)
     * @param panelTranslationY how much the panel has physically moved down vertically (required
     *                          for keyguard animations only)
     */
    public void setExpansion(boolean forceExpanded, float expansionFraction,
                             float panelTranslationY) {
        final float keyguardExpansionFraction = forceExpanded ? 1f : expansionFraction;
        if (mStatusIconsAlphaAnimator != null) {
            mStatusIconsAlphaAnimator.setPosition(keyguardExpansionFraction);
        }

        if (forceExpanded) {
            // If the keyguard is showing, we want to offset the text so that it comes in at the
            // same time as the panel as it slides down.
            mHeaderTextContainerView.setTranslationY(panelTranslationY);
        } else {
            mHeaderTextContainerView.setTranslationY(0f);
        }

        if (mHeaderTextContainerAlphaAnimator != null) {
            mHeaderTextContainerAlphaAnimator.setPosition(keyguardExpansionFraction);
            if (keyguardExpansionFraction > 0) {
                mHeaderTextContainerView.setVisibility(VISIBLE);
            } else {
                mHeaderTextContainerView.setVisibility(INVISIBLE);
            }
        }
        if (mPrivacyChipAlphaAnimator != null) {
            mPrivacyChip.setExpanded(expansionFraction > 0.5);
            mPrivacyChipAlphaAnimator.setPosition(keyguardExpansionFraction);
        }

        if (mBrightnessSlider != 0) {
            if (keyguardExpansionFraction > 0) {
                mQuickQsBrightness.setVisibility(INVISIBLE);
            } else {
                mQuickQsBrightness.setVisibility(VISIBLE);
            }
        }
        if (expansionFraction < 1 && expansionFraction > 0.99) {
            if (mHeaderQsPanel.switchTileLayout()) {
                updateResources();
            }
        }
        mKeyguardExpansionFraction = keyguardExpansionFraction;
        updateSystemInfoText();
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mHeaderQsPanel.setDisabledByPolicy(disabled);
        mHeaderTextContainerView.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        mQuickQsStatusIcons.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        mQuickQsBrightness.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        updateResources();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mRingerModeTracker.getRingerModeInternal().observe(this, ringer -> {
            mRingerMode = ringer;
            updateStatusText();
        });
        mStatusBarIconController.addIconGroup(mIconManager);
        requestApplyInsets();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        // Handle padding of the clock
        DisplayCutout cutout = insets.getDisplayCutout();
        Pair<Integer, Integer> cornerCutoutPadding = StatusBarWindowView.cornerCutoutMargins(
                cutout, getDisplay());
        Pair<Integer, Integer> padding =
                StatusBarWindowView.paddingNeededForCutoutAndRoundedCorner(
                        cutout, cornerCutoutPadding, -1);
        if (padding == null) {
            mSystemIconsView.setPaddingRelative(
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_start), 0,
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_end), 0);
        } else {
            mSystemIconsView.setPadding(padding.first, 0, padding.second, 0);

        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mSpace.getLayoutParams();
        boolean cornerCutout = cornerCutoutPadding != null
                && (cornerCutoutPadding.first == 0 || cornerCutoutPadding.second == 0);
        if (cutout != null) {
            Rect topCutout = cutout.getBoundingRectTop();
            if (topCutout.isEmpty() || cornerCutout) {
                mHasTopCutout = false;
                lp.width = 0;
                mSpace.setVisibility(View.GONE);
            } else {
                mHasTopCutout = true;
                lp.width = topCutout.width();
                mSpace.setVisibility(View.VISIBLE);
            }
        }
        mSpace.setLayoutParams(lp);
        setChipVisibility(mPrivacyChip.getVisibility() == View.VISIBLE);
        mCutOutPaddingLeft = padding.first;
        mCutOutPaddingRight = padding.second;
        mWaterfallTopInset = cutout == null ? 0 : cutout.getWaterfallInsets().top;
        updateClockPadding();
        return super.onApplyWindowInsets(insets);
    }

    private void updateClockPadding() {
        int clockPaddingLeft = 0;
        int clockPaddingRight = 0;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        int leftMargin = lp.leftMargin;
        int rightMargin = lp.rightMargin;

        // The clock might collide with cutouts, let's shift it out of the way.
        // We only do that if the inset is bigger than our own padding, since it's nicer to
        // align with
        if (mCutOutPaddingLeft > 0) {
            // if there's a cutout, let's use at least the rounded corner inset
            int cutoutPadding = Math.max(mCutOutPaddingLeft, mRoundedCornerPadding);
            int contentMarginLeft = isLayoutRtl() ? mContentMarginEnd : mContentMarginStart;
            clockPaddingLeft = Math.max(cutoutPadding - contentMarginLeft - leftMargin, 0);
        }
        if (mCutOutPaddingRight > 0) {
            // if there's a cutout, let's use at least the rounded corner inset
            int cutoutPadding = Math.max(mCutOutPaddingRight, mRoundedCornerPadding);
            int contentMarginRight = isLayoutRtl() ? mContentMarginStart : mContentMarginEnd;
            clockPaddingRight = Math.max(cutoutPadding - contentMarginRight - rightMargin, 0);
        }

        mSystemIconsView.setPadding(clockPaddingLeft,
                mWaterfallTopInset + mStatusBarPaddingTop,
                clockPaddingRight,
                0);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        mRingerModeTracker.getRingerModeInternal().removeObservers(this);
        mStatusBarIconController.removeIconGroup(mIconManager);
        super.onDetachedFromWindow();
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mHeaderQsPanel.setListening(listening);
        if (mHeaderQsPanel.switchTileLayout()) {
            updateResources();
        }
        mListening = listening;

        if (listening) {
            mZenController.addCallback(this);
            mAlarmController.addCallback(this);
            mBrightnessController.registerCallbacks();
            mLifecycle.setCurrentState(Lifecycle.State.RESUMED);
            // Get the most up to date info
            mAllIndicatorsEnabled = mPrivacyItemController.getAllIndicatorsAvailable();
            mMicCameraIndicatorsEnabled = mPrivacyItemController.getMicCameraAvailable();
            mPrivacyItemController.addCallback(mPICCallback);
        } else {
            mZenController.removeCallback(this);
            mAlarmController.removeCallback(this);
            mBrightnessController.unregisterCallbacks();
            mLifecycle.setCurrentState(Lifecycle.State.CREATED);
            mPrivacyItemController.removeCallback(mPICCallback);
            mPrivacyChipLogged = false;
        }
    }

    @Override
    public void onClick(View v) {
        if (v == mClockView || v == mNextAlarmTextView) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    AlarmClock.ACTION_SHOW_ALARMS), 0);
        } else if (v == mNextAlarmContainer && mNextAlarmContainer.isVisibleToUser()) {
            if (mNextAlarm.getShowIntent() != null) {
                mActivityStarter.postStartActivityDismissingKeyguard(
                        mNextAlarm.getShowIntent());
            } else {
                Log.d(TAG, "No PendingIntent for next alarm. Using default intent");
                mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                        AlarmClock.ACTION_SHOW_ALARMS), 0);
            }
        } else if (v == mPrivacyChip) {
            // Makes sure that the builder is grabbed as soon as the chip is pressed
            PrivacyChipBuilder builder = mPrivacyChip.getBuilder();
            if (builder.getAppsAndTypes().size() == 0) return;
            Handler mUiHandler = new Handler(Looper.getMainLooper());
            mUiEventLogger.log(PrivacyChipEvent.ONGOING_INDICATORS_CHIP_CLICK);
            mUiHandler.post(() -> {
                mActivityStarter.postStartActivityDismissingKeyguard(
                        new Intent(Intent.ACTION_REVIEW_ONGOING_PERMISSION_USAGE), 0);
                mHost.collapsePanels();
            });
        } else if (v == mRingerContainer && mRingerContainer.isVisibleToUser()) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    Settings.ACTION_SOUND_SETTINGS), 0);
        } else if (v == mDateView) {
            Uri.Builder builder = CalendarContract.CONTENT_URI.buildUpon();
            builder.appendPath("time");
            builder.appendPath(Long.toString(System.currentTimeMillis()));
            Intent todayIntent = new Intent(Intent.ACTION_VIEW, builder.build());
            mActivityStarter.postStartActivityDismissingKeyguard(todayIntent, 0);
        } else if (v == mBatteryRemainingIcon) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                Intent.ACTION_POWER_USAGE_SUMMARY), 0);
        }
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        mNextAlarm = nextAlarm;
        updateStatusText();
    }

    @Override
    public void onZenChanged(int zen) {
        updateStatusText();
    }

    @Override
    public void onConfigChanged(ZenModeConfig config) {
        updateStatusText();
    }

    public void updateEverything() {
        post(() -> setClickable(!mExpanded));
    }

    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        setupHost(qsPanel.getHost());
    }

    public void setupHost(final QSTileHost host) {
        mHost = host;
        //host.setHeaderView(mExpandIndicator);
        mHeaderQsPanel.setQSPanelAndHeader(mQsPanel, this);
        mHeaderQsPanel.setHost(host, null /* No customization in header */);


        Rect tintArea = new Rect(0, 0, 0, 0);
        int colorForeground = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.colorForeground);
        float intensity = getColorIntensity(colorForeground);
        int fillColor = mDualToneHandler.getSingleColor(intensity);
        mBatteryRemainingIcon.onDarkChanged(tintArea, intensity, fillColor);
        if(mSystemInfoText != null &&  mSystemInfoIcon != null) {
            updateSystemInfoText();
        }
    }

    public void setCallback(Callback qsPanelCallback) {
        mHeaderQsPanel.setCallback(qsPanelCallback);
    }

    private String formatNextAlarm(AlarmManager.AlarmClockInfo info) {
        if (info == null) {
            return "";
        }
        String skeleton = android.text.format.DateFormat
                .is24HourFormat(mContext, ActivityManager.getCurrentUser()) ? "EHm" : "Ehma";
        String pattern = android.text.format.DateFormat
                .getBestDateTimePattern(Locale.getDefault(), skeleton);
        return android.text.format.DateFormat.format(pattern, info.getTriggerTime()).toString();
    }

    public static float getColorIntensity(@ColorInt int color) {
        return color == Color.WHITE ? 0 : 1;
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    public void setContentMargins(int marginStart, int marginEnd) {
        mContentMarginStart = marginStart;
        mContentMarginEnd = marginEnd;
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view == mHeaderQsPanel) {
                // QS panel doesn't lays out some of its content full width
                mHeaderQsPanel.setContentMargins(marginStart, marginEnd);
            } else if (view != mQuickQsBrightness) {
                MarginLayoutParams lp = (MarginLayoutParams) view.getLayoutParams();
                lp.setMarginStart(marginStart);
                lp.setMarginEnd(marginEnd);
                view.setLayoutParams(lp);
            }
        }
        updateClockPadding();
    }

    public void setExpandedScrollAmount(int scrollY) {
        // The scrolling of the expanded qs has changed. Since the header text isn't part of it,
        // but would overlap content, we're fading it out.
        float newAlpha = 1.0f;
        if (mHeaderTextContainerView.getHeight() > 0) {
            newAlpha = MathUtils.map(0, mHeaderTextContainerView.getHeight() / 2.0f, 1.0f, 0.0f,
                    scrollY);
            newAlpha = Interpolators.ALPHA_OUT.getInterpolation(newAlpha);
        }
        mHeaderTextContainerView.setScrollY(scrollY);
        if (newAlpha != mExpandedHeaderAlpha) {
            mExpandedHeaderAlpha = newAlpha;
            mHeaderTextContainerView.setAlpha(MathUtils.lerp(0.0f, mExpandedHeaderAlpha,
                    mKeyguardExpansionFraction));
            updateHeaderTextContainerAlphaAnimator();
        }
    }

    private boolean getChipEnabled() {
        return mMicCameraIndicatorsEnabled || mAllIndicatorsEnabled;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case QQS_SHOW_BRIGHTNESS_SLIDER:
                mBrightnessSlider = TunerService.parseInteger(newValue, 2);
                updateResources();
                break;
            case QS_SHOW_AUTO_BRIGHTNESS:
                mIsQsAutoBrightnessEnabled = TunerService.parseIntegerSwitch(newValue, true);
                updateResources();
                break;
            default:
                break;
        }
    }

    public void updateLogoSettings() {
        Drawable logo = null;

        if (mContext == null) {
            return;
        }
        mShowLogo = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_PANEL_LOGO, 0,
                UserHandle.USER_CURRENT);
        mLogoColor = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_PANEL_LOGO_COLOR, 0xffff8800,
                UserHandle.USER_CURRENT);
        mLogoStyle = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_PANEL_LOGO_STYLE, 0,
                UserHandle.USER_CURRENT);

        switch(mLogoStyle) {
                // Xtnd Old
            case 1:
                logo = mContext.getDrawable(R.drawable.ic_xtnd_logo);
                break;
                // XTND Short
            case 2:
                logo = mContext.getDrawable(R.drawable.ic_xtnd_short);
                break;
                // GZR Skull
            case 3:
                logo = mContext.getResources().getDrawable(R.drawable.status_bar_gzr_skull_logo);
                break;
                // GZR Circle
            case 4:
                logo = mContext.getResources().getDrawable(R.drawable.status_bar_gzr_circle_logo);
                break;
                // Batman
            case 5:
                logo = mContext.getDrawable(R.drawable.ic_batman_logo);
                break;
                // Deadpool
            case 6:
                logo = mContext.getDrawable(R.drawable.ic_deadpool_logo);
                break;
                // Superman
            case 7:
                logo = mContext.getDrawable(R.drawable.ic_superman_logo);
                break;
                // Ironman
            case 8:
                logo = mContext.getDrawable(R.drawable.ic_ironman_logo);
                break;
                // Spiderman
            case 9:
                logo = mContext.getDrawable(R.drawable.ic_spiderman_logo);
                break;
                // Decepticons
            case 10:
                logo = mContext.getDrawable(R.drawable.ic_decpeticons_logo);
                break;
                // Minions
            case 11:
                logo = mContext.getDrawable(R.drawable.ic_minions_logo);
                break;
            case 12:
                logo = mContext.getDrawable(R.drawable.ic_android_logo);
                break;
                // Shit
            case 13:
                logo = mContext.getDrawable(R.drawable.ic_apple_logo);
                break;
                // Shitty Logo
            case 14:
                logo = mContext.getDrawable(R.drawable.ic_ios_logo);
                break;
                // Others
            case 15:
                logo = mContext.getDrawable(R.drawable.ic_blackberry);
                break;
            case 16:
                logo = mContext.getDrawable(R.drawable.ic_cake);
                break;
            case 17:
                logo = mContext.getDrawable(R.drawable.ic_blogger);
                break;
            case 18:
                logo = mContext.getDrawable(R.drawable.ic_biohazard);
                break;
            case 19:
                logo = mContext.getDrawable(R.drawable.ic_linux);
                break;
            case 20:
                logo = mContext.getDrawable(R.drawable.ic_yin_yang);
                break;
            case 21:
                logo = mContext.getDrawable(R.drawable.ic_windows);
                break;
            case 22:
                logo = mContext.getDrawable(R.drawable.ic_robot);
                break;
            case 23:
                logo = mContext.getDrawable(R.drawable.ic_ninja);
                break;
            case 24:
                logo = mContext.getDrawable(R.drawable.ic_heart);
                break;
            case 25:
                logo = mContext.getDrawable(R.drawable.ic_ghost);
                break;
            case 26:
                logo = mContext.getDrawable(R.drawable.ic_google);
                break;
            case 27:
                logo = mContext.getDrawable(R.drawable.ic_human_male);
                break;
            case 28:
                logo = mContext.getDrawable(R.drawable.ic_human_female);
                break;
            case 29:
                logo = mContext.getDrawable(R.drawable.ic_human_male_female);
                break;
            case 30:
                logo = mContext.getDrawable(R.drawable.ic_gender_male);
                break;
            case 31:
                logo = mContext.getDrawable(R.drawable.ic_gender_female);
                break;
            case 32:
                logo = mContext.getDrawable(R.drawable.ic_gender_male_female);
                break;
            case 33:
                logo = mContext.getDrawable(R.drawable.ic_guitar_electric);
                break;
            case 34:
                logo = mContext.getDrawable(R.drawable.ic_emoticon);
                break;
            case 35:
                logo = mContext.getDrawable(R.drawable.ic_emoticon_neutral);
                break;
            case 36:
                logo = mContext.getDrawable(R.drawable.ic_emoticon_happy);
                break;
            case 37:
                logo = mContext.getDrawable(R.drawable.ic_emoticon_sad);
                break;
            case 38:
                logo = mContext.getDrawable(R.drawable.ic_emoticon_tongue);
                break;
            case 39:
                logo = mContext.getDrawable(R.drawable.ic_emoticon_dead);
                break;
            case 40:
                logo = mContext.getDrawable(R.drawable.ic_emoticon_cool);
                break;
            case 41:
                logo = mContext.getDrawable(R.drawable.ic_emoticon_devil);
                break;
            case 0:
            default: // Default (Xtended Main)
                logo = mContext.getDrawable(R.drawable.status_bar_logo);
                break;
        }
        if (mShowLogo == 1) {
	    mXtendedLogoRight.setImageDrawable(null);
            mXtendedLogoRight.setVisibility(View.GONE);
            mXtendedLogo.setVisibility(View.VISIBLE);
            mXtendedLogo.setImageDrawable(logo);
            mXtendedLogo.setColorFilter(mLogoColor, PorterDuff.Mode.MULTIPLY);
	} else if (mShowLogo == 2) {
            mXtendedLogo.setImageDrawable(null);
            mXtendedLogo.setVisibility(View.GONE);
            mXtendedLogoRight.setVisibility(View.VISIBLE);
	    mXtendedLogoRight.setImageDrawable(logo);
	    mXtendedLogoRight.setColorFilter(mLogoColor, PorterDuff.Mode.MULTIPLY);
	} else {
            mXtendedLogo.setImageDrawable(null);
            mXtendedLogo.setVisibility(View.GONE);
            mXtendedLogoRight.setImageDrawable(null);
            mXtendedLogoRight.setVisibility(View.GONE);
        }
    }
}
