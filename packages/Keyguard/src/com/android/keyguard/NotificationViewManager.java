/*
 * Copyright (C) 2013 Team AOSPAL
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

package com.android.keyguard;

import android.app.INotificationManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.INotificationListener;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.android.internal.widget.LockPatternUtils;

public class NotificationViewManager {
    private final static String TAG = "Keyguard:NotificationViewManager";

    private final static int MIN_TIME_COVERED = 5000;
    private static final int ANIMATION_MAX_DURATION = 300;

    public static NotificationListenerWrapper NotificationListener = null;
    private static ProximityListener ProximityListener = null;
    private static Sensor ProximitySensor = null;

    private boolean mWokenByPocketMode = false;
    private boolean mIsScreenOn = false;
    private long mTimeCovered = 0;

    private Context mContext;
    private KeyguardViewManager mKeyguardViewManager;
    private INotificationManager mNotificationManager;
    private PowerManager mPowerManager;
    private NotificationHostView mHostView;

    private static LockPatternUtils mLockPatternUtils;


    private Set<String> mExcludedApps = new HashSet<String>();

    public static Configuration config;

    class Configuration extends ContentObserver {
        //User configurable values, set defaults here
        public boolean showAlways = false;
        public boolean pocketMode = false;
        public boolean hideLowPriority = false;
        public boolean hideNonClearable = false;
        public boolean dismissAll = true;
        public boolean wakeOnNotification = false;
        public boolean privacyMode = true;
        public int notificationsHeight = 4;
        public float offsetTop = 0.3f;
        public int notificationColor = 0x55555555;

        public Configuration(Handler handler) {
            super(handler);
            updateSettings();
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_POCKET_MODE), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_WAKE_ON_NOTIFICATION), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_PRIVACY_MODE), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_HEIGHT), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_OFFSET_TOP), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_EXCLUDED_APPS), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_COLOR), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_TYPE), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }

        private void updateSettings() {

            int pocketModeVal = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_POCKET_MODE, 2, UserHandle.USER_CURRENT);
            if (pocketModeVal == 0) {
                pocketMode = true;
                showAlways = true;
            } else if (pocketModeVal == 1) {
                pocketMode = true;
                showAlways = false;
            } else {
                pocketMode = false;
                showAlways = false;
            }
            wakeOnNotification = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_WAKE_ON_NOTIFICATION,
                    wakeOnNotification ? 1 : 0, UserHandle.USER_CURRENT) == 1;

            privacyMode = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_PRIVACY_MODE,
                    privacyMode ? 1 : 0, UserHandle.USER_CURRENT) == 1;

            notificationsHeight = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_HEIGHT,
                    notificationsHeight, UserHandle.USER_CURRENT);
            offsetTop = Settings.System.getFloatForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_OFFSET_TOP,
                    offsetTop, UserHandle.USER_CURRENT);
            String excludedApps = Settings.System.getStringForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_EXCLUDED_APPS,
                    UserHandle.USER_CURRENT);
            //notificationColor = Settings.System.getInt(mContext.getContentResolver(),
              //      Settings.System.LOCKSCREEN_NOTIFICATIONS_COLOR, notificationColor);

            String types = Settings.System.getStringForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_TYPE, UserHandle.USER_CURRENT);
            if (types != null) {
                if (types.contains("1")) {
                    hideNonClearable = false;
                } else {
                    hideNonClearable = true;
                }
                if (types.contains("2")) {
                    hideLowPriority = false;
                } else {
                    hideLowPriority = true;
                }
            }

            createExcludedAppsSet(excludedApps);
        }
    }

    public static final boolean privacyModeEnabled(Context context) {
        boolean securityBypassed = Settings.Secure.getIntForUser(context.getContentResolver(),
                    Settings.Secure.LOCK_TEMP_SECURE_MODE, 0, UserHandle.USER_CURRENT) == 0;
        boolean shakeSecureEnabled = Settings.Secure.getIntForUser(context.getContentResolver(),
                    Settings.Secure.LOCK_SHAKE_TEMP_SECURE, 0, UserHandle.USER_CURRENT) == 1;
        boolean privacyModeEnabled = Settings.System.getIntForUser(context.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_PRIVACY_MODE, 1, UserHandle.USER_CURRENT) == 1;

        if (mLockPatternUtils.isSecure() && shakeSecureEnabled && securityBypassed) {
            return false;
        } else if (mLockPatternUtils.isSecure() && privacyModeEnabled){
            return true;
        } else {
            return false;
        }
    }

    private class ProximityListener implements SensorEventListener {
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.equals(ProximitySensor)) {
                if (!mIsScreenOn) {
                    if (event.values[0] >= ProximitySensor.getMaximumRange()) {
                        if (config.pocketMode && mTimeCovered != 0 && (config.showAlways
                                || mHostView.getNotificationCount() > 0)
                                && System.currentTimeMillis() - mTimeCovered > MIN_TIME_COVERED
                                && Settings.System.getInt(mContext.getContentResolver(),
                                Settings.System.QUIET_HOURS_DIM, 0) != 2) {
                            wakeDevice();
                            mWokenByPocketMode = true;
                            mHostView.showAllNotifications();
                        }
                        mTimeCovered = 0;
                    } else if (mTimeCovered == 0) {
                        mTimeCovered = System.currentTimeMillis();
                    }
                } else if (config.pocketMode && mWokenByPocketMode &&
                        mKeyguardViewManager.isShowing() && event.values[0] < 0.2f){
                    mPowerManager.goToSleep(SystemClock.uptimeMillis());
                    mTimeCovered = System.currentTimeMillis();
                    mWokenByPocketMode = false;
                }
            }
        }
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    }

    public class NotificationListenerWrapper extends INotificationListener.Stub {
        @Override
        public void onNotificationPosted(final StatusBarNotification sbn) {
            boolean screenOffAndNotCovered = !mIsScreenOn && mTimeCovered == 0;
            boolean showNotification = !mHostView.containsNotification(sbn) ||
                    mHostView.getNotification(sbn).when != sbn.getNotification().when;
            boolean added = mHostView.addNotification(sbn,
                    (screenOffAndNotCovered || mIsScreenOn) && showNotification, false);
            if ( added && config.wakeOnNotification && screenOffAndNotCovered
                        && showNotification && mTimeCovered == 0) {
                wakeDevice();
            }
        }
        @Override
        public void onNotificationRemoved(final StatusBarNotification sbn) {
            mHostView.removeNotification(sbn, false);
        }

        public boolean isValidNotification(final StatusBarNotification sbn) {
            return (!mExcludedApps.contains(sbn.getPackageName()));
        }

    }

    public NotificationViewManager(Context context, KeyguardViewManager viewManager) {
        mContext = context;

        mKeyguardViewManager = viewManager;
        mNotificationManager = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));

        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);

        mLockPatternUtils = new LockPatternUtils(mContext);

        config = new Configuration(new Handler());
        config.observe();
    }

    public void unregisterListeners() {
        unregisterNotificationListener();
        unregisterProximityListener();
    }

    public void registerListeners() {
        registerProximityListener();
        registerNotificationListener();
    }

    private void registerProximityListener() {
        if (ProximityListener == null && (config.pocketMode || config.wakeOnNotification)) {
            SensorManager sensorManager = (SensorManager)
                    mContext.getSystemService(Context.SENSOR_SERVICE);
            ProximityListener = new ProximityListener();
            ProximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            sensorManager.registerListener(ProximityListener,
                    ProximitySensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    private void registerNotificationListener() {
        if (NotificationListener == null) {
            NotificationListener = new NotificationListenerWrapper();
            ComponentName cn = new ComponentName(mContext, getClass().getName());
            try {
                mNotificationManager.registerListener(
                        NotificationListener, cn, UserHandle.USER_ALL);
            } catch (RemoteException ex) {
                Log.e(TAG, "Could not register notification listener: " + ex.toString());
            }
        }
    }

    private void unregisterProximityListener() {
        if (ProximityListener != null) {
            SensorManager sensorManager = (SensorManager)
                    mContext.getSystemService(Context.SENSOR_SERVICE);
            sensorManager.unregisterListener(ProximityListener);
            ProximityListener = null;
        }
    }

    private void unregisterNotificationListener() {
        if (NotificationListener != null) {
            try {
                mNotificationManager.unregisterListener(NotificationListener, UserHandle.USER_ALL);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to unregister NotificationListener!");
            }
            NotificationListener = null;
        }
    }

    public void setHostView (NotificationHostView hostView) {
        mHostView = hostView;
    }

    private void wakeDevice() {
        mPowerManager.wakeUp(SystemClock.uptimeMillis());
    }

    public void onScreenTurnedOff() {
        mIsScreenOn = false;
        mWokenByPocketMode = false;
        if (mHostView != null) mHostView.hideAllNotifications();
        if (NotificationListener == null) {
            registerListeners();
        }
    }

    public void onScreenTurnedOn() {
        mIsScreenOn = true;
        mTimeCovered = 0;
        if (mHostView != null) mHostView.bringToFront();
    }

    public void onDismiss() {
        mWokenByPocketMode = false;
        // We don't want the notification and proximity listeners run the whole time,
        // we just need them when screen is off or keyguard is shown.
        // Wait for eventual animations to finish
        new Handler().postDelayed(new Runnable() {
            public void run() {
                unregisterListeners();
            }
        }, ANIMATION_MAX_DURATION);
    }

    /**
     * Create the set of excluded apps given a string of packages delimited with '|'.
     * @param excludedApps
     */
    private void createExcludedAppsSet(String excludedApps) {
        if (TextUtils.isEmpty(excludedApps))
            return;
        String[] appsToExclude = excludedApps.split("\\|");
        mExcludedApps = new HashSet<String>(Arrays.asList(appsToExclude));
    }
}
