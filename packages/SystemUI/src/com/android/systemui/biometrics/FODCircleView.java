/**
 * Copyright (C) 2019-2020 The LineageOS Project
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
 * limitations under the License.
 */

package com.android.systemui.biometrics;

import android.app.admin.DevicePolicyManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Point;
import android.net.Uri;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Spline;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.ImageView;

import com.android.internal.util.xtended.XtendedUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dependency;
import com.android.systemui.R;

import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreen;
import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreenCallback;

import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;

public class FODCircleView extends ImageView {
    private static final String DOZE_INTENT = "com.android.systemui.doze.pulse";
    private final int mPositionX;
    private final int mPositionY;
    private final int mSize;
    private final int mDreamingMaxOffset;
    private final int mNavigationBarSize;
    private final boolean mShouldBoostBrightness;
    private final boolean mShouldEnableDimlayer;
    private final Paint mPaintFingerprintBackground = new Paint();
    private final LayoutParams mParams = new LayoutParams();
    private final LayoutParams mPressedParams = new LayoutParams();
    private final WindowManager mWindowManager;

    private FODIconView mFODIcon;
    private IFingerprintInscreen mFingerprintInscreenDaemon;
    private vendor.lineage.biometrics.fingerprint.inscreen.V1_1.IFingerprintInscreen
        mFingerprintInscreenDaemonV1_1;

    private int mDreamingOffsetX;
    private int mColorBackground;
    private int mDreamingOffsetY;

    private boolean mIsBouncer;
    private boolean mIsBiometricRunning;
    private boolean mIsCircleShowing;
    private boolean mIsDreaming;
    private boolean mIsKeyguard;
    private boolean mTouchedOutside;

    private boolean mIsScreenDimming;
    private boolean mFodIconNeedsDim;
    private int mCurrentBrightness;
    private int mDefaultScreenBrightness;
    private int mScreenBrightnessDimConfig;
    private Spline mFODiconBrightnessToDimAmountSpline;

    private PowerManager mPowerManager;

    private boolean mDozeEnabled;
    private boolean mFodGestureEnable;
    private boolean mPressPending;
    private boolean mScreenTurnedOn;

    private PowerManager.WakeLock mWakeLock;

    private Handler mHandler;

    private final ImageView mPressedView;

    private LockPatternUtils mLockPatternUtils;

    private Timer mBurnInProtectionTimer;

    private FODAnimation mFODAnimation;
    private boolean mIsRecognizingAnimEnabled;
    private boolean mIsFodAnimationAvailable = false;

    private int mDefaultPressedColor;
    private int mPressedColor;
    private final int[] PRESSED_COLOR = {
        R.drawable.fod_icon_pressed,
        R.drawable.fod_icon_pressed_cyan,
        R.drawable.fod_icon_pressed_green,
        R.drawable.fod_icon_pressed_yellow,
        R.drawable.fod_icon_pressed_light_yellow
    };

    private IFingerprintInscreenCallback mFingerprintInscreenCallback =
            new IFingerprintInscreenCallback.Stub() {
        @Override
        public void onFingerDown() {
            if (mFodGestureEnable && !mScreenTurnedOn) {
                if (mDozeEnabled) {
                    mHandler.post(() -> mContext.sendBroadcast(new Intent(DOZE_INTENT)));
                } else {
                    mWakeLock.acquire(3000);
                    mHandler.post(() -> mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                        PowerManager.WAKE_REASON_GESTURE, FODCircleView.class.getSimpleName()));
                }
                mPressPending = true;
            } else {
                mHandler.post(() -> showCircle());
            }
        }

        @Override
        public void onFingerUp() {
            mHandler.post(() -> hideCircle());
            if (mFodGestureEnable && mPressPending) {
                mPressPending = false;
            }
        }
    };

    private KeyguardUpdateMonitor mUpdateMonitor;

    private KeyguardUpdateMonitorCallback mMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType,
                boolean isStrongBiometric) {
            // We assume that if biometricSourceType matches Fingerprint it will be
            // handled here, so we hide only when other biometric types authenticate
            if (biometricSourceType != BiometricSourceType.FINGERPRINT) {
                hide();
            }
        }

        @Override
        public void onBiometricRunningStateChanged(boolean running,
                BiometricSourceType biometricSourceType) {
            if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
                mIsBiometricRunning = running;
            }
        }

        @Override
        public void onDreamingStateChanged(boolean dreaming) {
            mIsDreaming = dreaming;
            updateAlpha();

            if (mIsKeyguard && mUpdateMonitor.isFingerprintDetectionRunning()) {
                show();
                updateAlpha();
            } else {
                hide();
            }

            if (dreaming) {
                mBurnInProtectionTimer = new Timer();
                mBurnInProtectionTimer.schedule(new BurnInProtectionTask(), 0, 60 * 1000);
            } else if (mBurnInProtectionTimer != null) {
                mBurnInProtectionTimer.cancel();
                updatePosition();
            }
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            mIsKeyguard = showing;
            updateStyle();
            if (mIsRecognizingAnimEnabled) {
                mFODAnimation.setAnimationKeyguard(mIsKeyguard);
            }

            if (mFODIcon != null) {
                mFODIcon.setIsKeyguard(mIsKeyguard);
            }

        }

        @Override
        public void onKeyguardBouncerChanged(boolean isBouncer) {
            mIsBouncer = isBouncer;
            updateStyle();
            if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                if (isPinOrPattern(mUpdateMonitor.getCurrentUser()) || !isBouncer) {
                    show();
                } else {
                    hide();
                }
            } else {
                hide();
            }
            if (mIsRecognizingAnimEnabled) {
                mFODAnimation.setAnimationKeyguard(mIsBouncer);
            }
        }

        @Override
        public void onScreenTurnedOff() {
           mScreenTurnedOn = false;
            if (mFodGestureEnable){
                hideCircle();
            }else{
                hide();
            }
        }

        @Override
        public void onScreenTurnedOn() {
            if (!mFodGestureEnable && mUpdateMonitor.isFingerprintDetectionRunning()) {
                show();
            }
            if (mFodGestureEnable && mPressPending) {
                mHandler.post(() -> showCircle());
                mPressPending = false;
            }
            mScreenTurnedOn = true;
        }

        @Override
        public void onBiometricHelp(int msgId, String helpString,
                BiometricSourceType biometricSourceType) {
            if (msgId == -1 && mIsFodAnimationAvailable) { // Auth error
                mHandler.post(() -> mFODAnimation.hideFODanimation());
            }
        }

    };

    private class FodGestureSettingsObserver extends ContentObserver {
        FodGestureSettingsObserver(Context context, Handler handler) {
            super(handler);
        }

        void registerListener() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(
                    Settings.Secure.DOZE_ENABLED),
                    false, this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(
                    Settings.System.FOD_GESTURE),
                    false, this, UserHandle.USER_ALL);
            updateSettings();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            updateSettings();
        }

        public void updateSettings() {
            mDozeEnabled = Settings.Secure.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.DOZE_ENABLED, 1,
                    UserHandle.USER_CURRENT) == 1;
            mFodGestureEnable = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.System.FOD_GESTURE, 1,
                    UserHandle.USER_CURRENT) == 1;
            }
        }

    private FodGestureSettingsObserver mFodGestureSettingsObserver;

    public FODCircleView(Context context) {
        super(context);

        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        if (daemon == null) {
            throw new RuntimeException("Unable to get IFingerprintInscreen");
        }

        try {
            mShouldBoostBrightness = daemon.shouldBoostBrightness();
            mPositionX = daemon.getPositionX();
            mPositionY = daemon.getPositionY();
            mSize = daemon.getSize();
            mShouldEnableDimlayer = mFingerprintInscreenDaemonV1_1 == null ||
                    mFingerprintInscreenDaemonV1_1.shouldEnableDimlayer();
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to retrieve FOD circle position or size");
        }

        Resources res = mContext.getResources();

        mColorBackground = res.getColor(R.color.config_fodColorBackground);
        mDefaultPressedColor = res.getInteger(com.android.internal.R.
             integer.config_fod_pressed_color);
        mPaintFingerprintBackground.setColor(mColorBackground);
        mPaintFingerprintBackground.setAntiAlias(true);

        mPowerManager = context.getSystemService(PowerManager.class);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                 FODCircleView.class.getSimpleName());

        mWindowManager = mContext.getSystemService(WindowManager.class);
        mIsFodAnimationAvailable = XtendedUtils.isPackageInstalled(context,
                                    context.getResources().getString(
                                    com.android.internal.R.string.config_fodAnimationPackage));
        if (mIsFodAnimationAvailable) {
            mFODAnimation = new FODAnimation(mContext, mWindowManager, mPositionX, mPositionY);
        }

        mFodIconNeedsDim = res.getBoolean(R.bool.config_fodIconNeedsDim);
        float[] icon_dim_amount =
                getFloatArray(res.obtainTypedArray(R.array.config_FODiconDimAmount));
        float[] display_brightness =
                getFloatArray(res.obtainTypedArray(R.array.config_FODiconDisplayBrightness));
        mFODiconBrightnessToDimAmountSpline =
                Spline.createSpline(display_brightness, icon_dim_amount);
        mDefaultScreenBrightness = mPowerManager.getDefaultScreenBrightnessSetting();
        mScreenBrightnessDimConfig = res.getInteger(
                com.android.internal.R.integer.config_screenBrightnessDim);

        mFODIcon = new FODIconView(mContext, mSize, mPositionX, mPositionY);
        mNavigationBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);

        mDreamingMaxOffset = (int) (mSize * 0.1f);

        mHandler = new Handler(Looper.getMainLooper());

        mParams.height = mSize;
        mParams.width = mSize;
        mParams.format = PixelFormat.TRANSLUCENT;

        mParams.packageName = "android";
        mParams.type = WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY;
        mParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        mParams.gravity = Gravity.TOP | Gravity.LEFT;

        mPressedParams.copyFrom(mParams);
        mPressedParams.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND |
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;

        mParams.setTitle("Fingerprint on display");
        mPressedParams.setTitle("Fingerprint on display.touched");

        if (!mShouldEnableDimlayer) {
            mParams.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            mParams.dimAmount = 0.0f;
        }

        mPressedView = new ImageView(context)  {
            @Override
            protected void onDraw(Canvas canvas) {
                if (mIsCircleShowing) {
                    setImageResource(PRESSED_COLOR[mPressedColor]);
                }
                super.onDraw(canvas);
            }
        };

        mWindowManager.addView(this, mParams);

        mCustomSettingsObserver.observe();
        mCustomSettingsObserver.update();
        updatePosition();
        hide();

        mLockPatternUtils = new LockPatternUtils(mContext);

        mUpdateMonitor = Dependency.get(KeyguardUpdateMonitor.class);
        mUpdateMonitor.registerCallback(mMonitorCallback);

        if (context.getResources().getBoolean(
            com.android.internal.R.bool.config_supportsScreenOffInDisplayFingerprint)){
            mFodGestureSettingsObserver = new FodGestureSettingsObserver(context, mHandler);
            mFodGestureSettingsObserver.registerListener();
        }

    }

    private CustomSettingsObserver mCustomSettingsObserver = new CustomSettingsObserver(mHandler);
    private class CustomSettingsObserver extends ContentObserver {

        CustomSettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.FOD_ANIM),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.FOD_COLOR),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.SCREEN_IS_DIMMING),
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(Settings.System.getUriFor(Settings.System.FOD_ANIM)) ||
                    uri.equals(Settings.System.getUriFor(Settings.System.FOD_COLOR))) {
                updateStyle();
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS))) {
                updateIconDim();
            } else if (uri.equals(Settings.Global.getUriFor(
                    Settings.Global.SCREEN_IS_DIMMING))) {
                updateIconDim();
            }
        }

        public void update() {
            updateStyle();
            updateIconDim();
        }
    }

    private float getDimAlpha() {
        return mFODiconBrightnessToDimAmountSpline.interpolate(
                mIsScreenDimming ? mScreenBrightnessDimConfig : mCurrentBrightness);
    }

    public void updateIconDim() {
        mCurrentBrightness = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, mDefaultScreenBrightness, UserHandle.USER_CURRENT);
        mIsScreenDimming = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.SCREEN_IS_DIMMING, 0) == 1;
        if (mFodIconNeedsDim) {
            int icon_dim_mount = Math.round(getDimAlpha());
            mHandler.post(() -> setColorFilter(Color.argb(icon_dim_mount, 0, 0, 0), PorterDuff.Mode.SRC_ATOP));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mIsCircleShowing) {
            canvas.drawCircle(mSize / 2, mSize / 2, mSize / 2.0f, mPaintFingerprintBackground);
        }
        super.onDraw(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);

        boolean newIsInside = (x > 0 && x < mSize) && (y > 0 && y < mSize);
        mTouchedOutside = false;

        if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            mTouchedOutside = true;
            return true;
        }

        if (event.getAction() == MotionEvent.ACTION_DOWN && newIsInside) {
            showCircle();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            hideCircle();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (!newIsInside) {
                hideCircle();
            }
            return true;
        }

        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        updateStyle();
        updatePosition();
    }

    public IFingerprintInscreen getFingerprintInScreenDaemon() {
        if (mFingerprintInscreenDaemon == null) {
            try {
                mFingerprintInscreenDaemon = IFingerprintInscreen.getService();
                if (mFingerprintInscreenDaemon != null) {
                    mFingerprintInscreenDaemon.setCallback(mFingerprintInscreenCallback);
                    mFingerprintInscreenDaemon.asBinder().linkToDeath((cookie) -> {
                        mFingerprintInscreenDaemon = null;
                    }, 0);
                    mFingerprintInscreenDaemonV1_1 =
                        vendor.lineage.biometrics.fingerprint.inscreen.V1_1.IFingerprintInscreen
                                .castFrom(mFingerprintInscreenDaemon);
                }
            } catch (NoSuchElementException | RemoteException e) {
                // do nothing
            }
        }
        return mFingerprintInscreenDaemon;
    }

    public void dispatchPress() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onPress();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchRelease() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onRelease();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchShow() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onShowFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchHide() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onHideFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void showCircle() {
        if (mTouchedOutside) return;
        mIsCircleShowing = true;

        setKeepScreenOn(true);

        setDim(true);
        dispatchPress();

        setImageDrawable(null);
        invalidate();
        if (mIsRecognizingAnimEnabled) {
            mFODAnimation.showFODanimation();
        }
    }

    public void hideCircle() {
        mIsCircleShowing = false;

        invalidate();

        dispatchRelease();
        setDim(false);

        setKeepScreenOn(false);
        if (mIsRecognizingAnimEnabled) {
            mFODAnimation.hideFODanimation();
        }
    }

    public void show() {
        if (mUpdateMonitor.userNeedsStrongAuth()) {
            // Keyguard requires strong authentication (not biometrics)
            return;
        }

        if (!mFodGestureEnable && !mUpdateMonitor.isScreenOn()) {
            // Keyguard is shown just after screen turning off
            return;
        }

        if (mIsBouncer && !isPinOrPattern(mUpdateMonitor.getCurrentUser())) {
            // Ignore show calls when Keyguard password screen is being shown
            return;
        }

        if (mIsKeyguard && mUpdateMonitor.getUserCanSkipBouncer(mUpdateMonitor.getCurrentUser())) {
            // Ignore show calls if user can skip bouncer
            return;
        }

        if (mIsKeyguard && !mIsBiometricRunning) {
            return;
        }

        mFODIcon.show();
        updatePosition();

        dispatchShow();
        setVisibility(View.VISIBLE);
    }

    public void hide() {
        mFODIcon.hide();
        setVisibility(View.GONE);
        hideCircle();
        dispatchHide();
    }

    private void updateAlpha() {
        setAlpha(mIsDreaming ? 0.5f : 1.0f);
    }

    private void updateStyle() {
        mIsRecognizingAnimEnabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_RECOGNIZING_ANIMATION, 0) != 0;
        mPressedColor = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_COLOR, mDefaultPressedColor);

        if (mIsFodAnimationAvailable && mFODAnimation != null) {
            mFODAnimation.update(mIsRecognizingAnimEnabled);
        }
    }

    private void updatePosition() {
        Display defaultDisplay = mWindowManager.getDefaultDisplay();

        Point size = new Point();
        defaultDisplay.getRealSize(size);

        int rotation = defaultDisplay.getRotation();
        int x, y;
        switch (rotation) {
            case Surface.ROTATION_0:
                x = mPositionX;
                y = mPositionY;
                break;
            case Surface.ROTATION_90:
                x = mPositionY;
                y = mPositionX;
                break;
            case Surface.ROTATION_180:
                x = mPositionX;
                y = size.y - mPositionY - mSize;
                break;
            case Surface.ROTATION_270:
                x = size.x - mPositionY - mSize - mNavigationBarSize;
                y = mPositionX;
                break;
            default:
                throw new IllegalArgumentException("Unknown rotation: " + rotation);
        }

        mPressedParams.x = mParams.x = x;
        mPressedParams.y = mParams.y = y;

        if (mIsDreaming) {
            mParams.x += mDreamingOffsetX;
            mParams.y += mDreamingOffsetY;
            if (mIsRecognizingAnimEnabled) {
                mFODAnimation.updateParams(mParams.y);
            }
        }

        mWindowManager.updateViewLayout(this, mParams);
        FODIconView fODIconView = this.mFODIcon;

        if (mPressedView.getParent() != null) {
            mWindowManager.updateViewLayout(mPressedView, mPressedParams);
        }

        WindowManager.LayoutParams layoutParams3 = this.mParams;
        fODIconView.updatePosition(layoutParams3.x, layoutParams3.y);
    }

    private void setDim(boolean dim) {
        if (dim) {
            int curBrightness = Settings.System.getInt(getContext().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 100);
            int dimAmount = 0;

            IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
            try {
                dimAmount = daemon.getDimAmount(curBrightness);
            } catch (RemoteException e) {
                // do nothing
            }

            if (mShouldBoostBrightness) {
                mPressedParams.screenBrightness = 1.0f;
            }

            mPressedParams.dimAmount = dimAmount / 255.0f;
            if (mPressedView.getParent() == null) {
                mWindowManager.addView(mPressedView, mPressedParams);
            } else {
                mWindowManager.updateViewLayout(mPressedView, mPressedParams);
            }
        } else {
            if (mShouldBoostBrightness) {
                mPressedParams.screenBrightness = 0.0f;
            }
            mPressedParams.dimAmount = 0.0f;
            if (mPressedView.getParent() != null) {
                mWindowManager.removeView(mPressedView);
            }
        }
    }

    private boolean isPinOrPattern(int userId) {
        int passwordQuality = mLockPatternUtils.getActivePasswordQuality(userId);
        switch (passwordQuality) {
            // PIN
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX:
            // Pattern
            case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
                return true;
        }

        return false;
    }

    private class BurnInProtectionTask extends TimerTask {
        @Override
        public void run() {
            long now = System.currentTimeMillis() / 1000 / 60;

            mDreamingOffsetX = (int) (now % (mDreamingMaxOffset * 4));
            if (mDreamingOffsetX > mDreamingMaxOffset * 2) {
                mDreamingOffsetX = mDreamingMaxOffset * 4 - mDreamingOffsetX;
            }

            // Let y to be not synchronized with x, so that we get maximum movement
            mDreamingOffsetY = (int) ((now + mDreamingMaxOffset / 3) % (mDreamingMaxOffset * 2));
            if (mDreamingOffsetY > mDreamingMaxOffset * 2) {
                mDreamingOffsetY = mDreamingMaxOffset * 4 - mDreamingOffsetY;
            }

            mDreamingOffsetX -= mDreamingMaxOffset;
            mDreamingOffsetY -= mDreamingMaxOffset;

            mHandler.post(() -> updatePosition());
        }
    };

    private static float[] getFloatArray(TypedArray array) {
        int length = array.length();
        float[] floatArray = new float[length];
        for (int i = 0; i < length; i++) {
            floatArray[i] = array.getFloat(i, Float.NaN);
        }
        array.recycle();
        return floatArray;
    }
}
