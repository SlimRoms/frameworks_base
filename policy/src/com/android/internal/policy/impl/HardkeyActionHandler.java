/*
 * Copyright (C) 2014 The TeamEos Project
 * Author: Randall Rushing aka Bigrushdog
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
 * 
 * Single tap, double tap, and long press logic for hardware key events
 * Monitors user configuration changes, sets sane defaults, executes actions,
 * lets PhoneWindowManager know relevant configuration changes. This handler
 * fully consumes all key events it watches
 *
 */

package com.android.internal.policy.impl;

import com.android.internal.util.actions.ActionHandler;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowManagerPolicy.WindowState;

public class HardkeyActionHandler extends ActionHandler {
    private interface ActionReceiver {
        public void onActionDispatched(HardKeyButton button, String task);
    }

    private static final String TAG = HardkeyActionHandler.class.getSimpleName();

    // messages to PWM to do some actions we can't really do here
    static final int MSG_FIRE_HOME = 7102;
    static final int MSG_UPDATE_MENU_KEY = 7106;
    static final int MSG_DO_HAPTIC_FB = 7107;

    // fire rocket boosters
    private static final int BOOST_LEVEL = 1000 * 1000;

    private static final int KEY_MASK_HOME = 0x01;
    private static final int KEY_MASK_BACK = 0x02;
    private static final int KEY_MASK_MENU = 0x04;
    private static final int KEY_MASK_ASSIST = 0x08;
    private static final int KEY_MASK_APP_SWITCH = 0x10;
    private static final int KEY_MASK_CAMERA = 0x20;
    private static final int KEY_MASK_VOLUME = 0x40;

    // lock our configuration changes
    private final Object mLock = new Object();

    private HardKeyButton mBackButton;
    private HardKeyButton mHomeButton;
    private HardKeyButton mRecentButton;
    private HardKeyButton mMenuButton;
    private HardKeyButton mAssistButton;

    // Behavior of HOME button during incomming call ring.
    // (See Settings.Secure.RING_HOME_BUTTON_BEHAVIOR.)
    int mRingHomeBehavior;

    private ActionReceiver mActionReceiver = new ActionReceiver() {
        @Override
        public void onActionDispatched(HardKeyButton button, String task) {
            if (task.equals(HardKeyButton.HOME)) {
                mHandler.sendEmptyMessage(MSG_FIRE_HOME);
                return;
            } else if (task.equals(HardKeyButton.SLEEP)) {
                // can't consume UP event if screen is off, do it manually
                button.setPressed(false);
                button.setWasConsumed(false);
            }
            performTask(task);
        }
    };

    private int mDeviceHardwareKeys;

    private SettingsObserver mObserver;
    private Handler mHandler;
    private PowerManager mPm;

    public HardkeyActionHandler(Context context, Handler handler) {
        super(context);
        mHandler = handler;
        mPm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        mDeviceHardwareKeys = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_deviceHardwareKeys);

        mBackButton = new HardKeyButton(context,
                Settings.System.HARDWARE_BUTTON_BACK_LONGPRESS,
                Settings.System.HARDWARE_BUTTON_BACK_DOUBLETAP, handler, mActionReceiver);

        mHomeButton = new HardKeyButton(context,
                Settings.System.HARDWARE_BUTTON_HOME_LONGPRESS,
                Settings.System.HARDWARE_BUTTON_HOME_DOUBLETAP, handler, mActionReceiver);

        mRecentButton = new HardKeyButton(context,
                Settings.System.HARDWARE_BUTTON_RECENT_SINGLETAP,
                Settings.System.HARDWARE_BUTTON_RECENT_LONGPRESS,
                Settings.System.HARDWARE_BUTTON_RECENT_DOUBLETAP, handler, mActionReceiver);

        mMenuButton = new HardKeyButton(context,
                Settings.System.HARDWARE_BUTTON_MENU_SINGLETAP,
                Settings.System.HARDWARE_BUTTON_MENU_LONGPRESS,
                Settings.System.HARDWARE_BUTTON_MENU_DOUBLETAP, handler, mActionReceiver);

        mAssistButton = new HardKeyButton(context,
                Settings.System.HARDWARE_BUTTON_ASSIST_SINGLETAP,
                Settings.System.HARDWARE_BUTTON_ASSIST_LONGPRESS,
                Settings.System.HARDWARE_BUTTON_ASSIST_DOUBLETAP, handler, mActionReceiver);

        mBackButton.setDefaults(HardKeyButton.BACK,
                HardKeyButton.NONE,
                HardKeyButton.NONE);
        mHomeButton.setDefaults(HardKeyButton.HOME,
                HardKeyButton.MENU,
                HardKeyButton.RECENTS);
        mRecentButton.setDefaults(HardKeyButton.RECENTS,
                HardKeyButton.NONE,
                HardKeyButton.NONE);
        mMenuButton.setDefaults(HardKeyButton.MENU,
                HardKeyButton.NONE,
                HardKeyButton.NONE);
        mAssistButton.setDefaults(HardKeyButton.ASSIST,
                HardKeyButton.VOICE,
                HardKeyButton.NONE);

        mObserver = new SettingsObserver(mHandler);
        mObserver.observe();
    }

    void fireBooster(HardKeyButton button) {
        if (!button.isDoubleTapPending()) {
            mPm.cpuBoost(BOOST_LEVEL);
        }
    }

    boolean handleKeyEvent(WindowState win, int keyCode, int repeatCount, boolean down,
            boolean canceled,
            boolean longPress, boolean keyguardOn) {
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            if (!down && mHomeButton.isPressed()) {
                mHomeButton.setPressed(false);
                if (mHomeButton.wasConsumed()) {
                    mHomeButton.setWasConsumed(false);
                    return true;
                }

                if (!mHomeButton.keyHasDoubleTapRecents()) {
                    cancelPreloadRecentApps();
                }

                if (canceled) {
                    return true;
                }

                // If an incoming call is ringing, HOME is totally disabled.
                // (The user is already on the InCallUI at this point,
                // and his ONLY options are to answer or reject the call.)
                TelecomManager telecomManager = getTelecommService();
                if (telecomManager != null && telecomManager.isRinging()) {
                    if ((mRingHomeBehavior
                            & Settings.Secure.RING_HOME_BUTTON_BEHAVIOR_ANSWER) != 0) {
                        Log.i(TAG, "Answering with HOME button.");
                        telecomManager.acceptRingingCall();
                        return true;
                    } else {
                        Log.i(TAG, "Ignoring HOME; there's a ringing incoming call.");
                        return true;
                    }
                }

                if (mHomeButton.isDoubleTapEnabled()) {
                    mHomeButton.cancelDTTimeout();
                    mHomeButton.setDoubleTapPending(true);
                    mHomeButton.postDTTimeout();
                    return true;
                }

                mHomeButton.fireSingleTap();
                return true;
            }

            // If a system window has focus, then it doesn't make sense
            // right now to interact with applications.
            //
            // NOTE: I don't think this code block is reachable here structured
            // as it
            //
            WindowManager.LayoutParams attrs = win != null ? win.getAttrs() : null;
            if (attrs != null) {
                final int type = attrs.type;
                if (type == WindowManager.LayoutParams.TYPE_KEYGUARD
                        || type == WindowManager.LayoutParams.TYPE_KEYGUARD_SCRIM
                        || type == WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG) {
                    // the "app" is keyguard, so give it the key
                    // false by default, but we consume everything, so do
                    // nothing
                    return true;
                }
                final int typeCount = WINDOW_TYPES_WHERE_HOME_DOESNT_WORK.length;
                for (int i = 0; i < typeCount; i++) {
                    if (type == WINDOW_TYPES_WHERE_HOME_DOESNT_WORK[i]) {
                        // don't do anything, but also don't pass it to the app
                        return true;
                    }
                }
            }

            if (!down) {
                return true;
            }

            if (repeatCount == 0) {
                mHomeButton.setPressed(true);
                fireBooster(mHomeButton);
                if (mHomeButton.isDoubleTapPending()) {
                    mHomeButton.setDoubleTapPending(false);
                    mHomeButton.cancelDTTimeout();
                    mHomeButton.fireDoubleTap();
                    mHomeButton.setWasConsumed(true);
                } else if (mHomeButton.keyHasLongPressRecents()
                        || mHomeButton.keyHasDoubleTapRecents()) {
                    preloadRecentApps();
                }
            } else if (longPress) {
                if (!keyguardOn
                        && !mHomeButton.wasConsumed()
                        && mHomeButton.isLongTapEnabled()) {
                    mHomeButton.setPressed(true);
                    if (!mHomeButton.keyHasLongPressRecents()) {
                        cancelPreloadRecentApps();
                    }
                    mHandler.sendEmptyMessage(MSG_DO_HAPTIC_FB);
                    mHomeButton.fireLongPress();
                    mHomeButton.setWasConsumed(true);
                }
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (!down && mMenuButton.isPressed()) {
                mMenuButton.setPressed(false);

                if (mMenuButton.wasConsumed()) {
                    mMenuButton.setWasConsumed(false);
                    return true;
                }

                if (!mMenuButton.keyHasDoubleTapRecents()) {
                    cancelPreloadRecentApps();
                }

                if (canceled || keyguardOn) {
                    return true;
                }

                if (mMenuButton.isDoubleTapEnabled()) {
                    mMenuButton.setDoubleTapPending(true);
                    mMenuButton.cancelDTTimeout();
                    mMenuButton.postDTTimeout();
                    return true;
                }

                if (!mMenuButton.keyHasSingleTapRecent()) {
                    cancelPreloadRecentApps();
                }

                mMenuButton.fireSingleTap();
                return true;
            }

            if (!down) {
                return true;
            }

            if (repeatCount == 0) {
                mMenuButton.setPressed(true);
                fireBooster(mMenuButton);
                if (mMenuButton.isDoubleTapPending()) {
                    mMenuButton.setDoubleTapPending(false);
                    mMenuButton.cancelDTTimeout();
                    mMenuButton.fireDoubleTap();
                    mMenuButton.setWasConsumed(true);
                } else if (mMenuButton.keyHasLongPressRecents()
                        || mMenuButton.keyHasDoubleTapRecents()
                        || mMenuButton.keyHasSingleTapRecent()) {
                    preloadRecentApps();
                }
            } else if (longPress) {
                if (!keyguardOn
                        && !mMenuButton.wasConsumed()
                        && mMenuButton.isLongTapEnabled()) {
                    mMenuButton.setPressed(true);
                    if (!mMenuButton.keyHasLongPressRecents()) {
                        cancelPreloadRecentApps();
                    }
                    mHandler.sendEmptyMessage(MSG_DO_HAPTIC_FB);
                    mMenuButton.fireLongPress();
                    mMenuButton.setWasConsumed(true);
                }
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            if (!down && mRecentButton.isPressed()) {
                mRecentButton.setPressed(false);

                if (mRecentButton.wasConsumed()) {
                    mRecentButton.setWasConsumed(false);
                    return true;
                }

                if (!mRecentButton.keyHasDoubleTapRecents()) {
                    cancelPreloadRecentApps();
                }

                if (canceled || keyguardOn) {
                    return true;
                }

                if (mRecentButton.isDoubleTapEnabled()) {
                    mRecentButton.setDoubleTapPending(true);
                    mRecentButton.cancelDTTimeout();
                    mRecentButton.postDTTimeout();
                    return true;
                }

                if (!mRecentButton.keyHasSingleTapRecent()) {
                    cancelPreloadRecentApps();
                }

                mRecentButton.fireSingleTap();
                return true;
            }

            if (!down) {
                return true;
            }

            if (repeatCount == 0) {
                mRecentButton.setPressed(true);
                fireBooster(mRecentButton);
                if (mRecentButton.isDoubleTapPending()) {
                    mRecentButton.setDoubleTapPending(false);
                    mRecentButton.cancelDTTimeout();
                    mRecentButton.fireDoubleTap();
                    mRecentButton.setWasConsumed(true);
                } else if (mRecentButton.keyHasLongPressRecents()
                        || mRecentButton.keyHasDoubleTapRecents()
                        || mRecentButton.keyHasSingleTapRecent()) {
                    preloadRecentApps();
                }
            } else if (longPress) {
                if (!keyguardOn
                        && !mRecentButton.wasConsumed()
                        && mRecentButton.isLongTapEnabled()) {
                    mRecentButton.setPressed(true);
                    if (!mRecentButton.keyHasLongPressRecents()) {
                        cancelPreloadRecentApps();
                    }
                    mHandler.sendEmptyMessage(MSG_DO_HAPTIC_FB);
                    mRecentButton.fireLongPress();
                    mRecentButton.setWasConsumed(true);
                }
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_ASSIST) {
            if (!down && mAssistButton.isPressed()) {
                mAssistButton.setPressed(false);

                if (mAssistButton.wasConsumed()) {
                    mAssistButton.setWasConsumed(false);
                    return true;
                }

                if (!mAssistButton.keyHasDoubleTapRecents()) {
                    cancelPreloadRecentApps();
                }

                if (canceled || keyguardOn) {
                    return true;
                }

                if (mAssistButton.isDoubleTapEnabled()) {
                    mAssistButton.setDoubleTapPending(true);
                    mAssistButton.cancelDTTimeout();
                    mAssistButton.postDTTimeout();
                    return true;
                }

                if (!mAssistButton.keyHasSingleTapRecent()) {
                    cancelPreloadRecentApps();
                }
                mAssistButton.fireSingleTap();
                return true;
            }

            if (!down) {
                return true;
            }

            if (repeatCount == 0) {
                mAssistButton.setPressed(true);
                fireBooster(mAssistButton);
                if (mAssistButton.isDoubleTapPending()) {
                    mAssistButton.setDoubleTapPending(false);
                    mAssistButton.cancelDTTimeout();
                    mAssistButton.fireDoubleTap();
                    mAssistButton.setWasConsumed(true);
                } else if (mAssistButton.keyHasLongPressRecents()
                        || mAssistButton.keyHasDoubleTapRecents()
                        || mAssistButton.keyHasSingleTapRecent()) {
                    preloadRecentApps();
                }
            } else if (longPress) {
                if (!keyguardOn
                        && !mAssistButton.wasConsumed()
                        && mAssistButton.isLongTapEnabled()) {
                    mAssistButton.setPressed(true);
                    if (!mAssistButton.keyHasLongPressRecents()) {
                        cancelPreloadRecentApps();
                    }
                    mHandler.sendEmptyMessage(MSG_DO_HAPTIC_FB);
                    mAssistButton.fireLongPress();
                    mAssistButton.setWasConsumed(true);
                }
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!down && mBackButton.isPressed()) {
                mBackButton.setPressed(false);

                if (mBackButton.wasConsumed()) {
                    mBackButton.setWasConsumed(false);
                    return true;
                }

                if (!mBackButton.keyHasDoubleTapRecents()) {
                    cancelPreloadRecentApps();
                }

                if (canceled || keyguardOn) {
                    return true;
                }

                if (mBackButton.isDoubleTapEnabled()) {
                    mBackButton.setDoubleTapPending(true);
                    mBackButton.cancelDTTimeout();
                    mBackButton.postDTTimeout();
                    return true;
                }

                mBackButton.fireSingleTap();
                return true;
            }

            if (!down) {
                return true;
            }

            if (repeatCount == 0) {
                mBackButton.setPressed(true);
                fireBooster(mBackButton);
                if (mBackButton.isDoubleTapPending()) {
                    mBackButton.setDoubleTapPending(false);
                    mBackButton.cancelDTTimeout();
                    mBackButton.fireDoubleTap();
                    mBackButton.setWasConsumed(true);
                } else if (mBackButton.keyHasLongPressRecents()
                        || mBackButton.keyHasDoubleTapRecents()) {
                    preloadRecentApps();
                }
            } else if (longPress) {
                if (!keyguardOn
                        && !mBackButton.wasConsumed()) {
                    mBackButton.setPressed(true);
                    if (isLockTaskOn()) {
                        turnOffLockTask();
                        mHandler.sendEmptyMessage(MSG_DO_HAPTIC_FB);
                        mBackButton.setWasConsumed(true);
                    } else {
                        if (mBackButton.isLongTapEnabled()) {
                            if (!mBackButton.keyHasLongPressRecents()) {
                                cancelPreloadRecentApps();
                            }
                            mBackButton.fireLongPress();
                            mHandler.sendEmptyMessage(MSG_DO_HAPTIC_FB);
                            mBackButton.setWasConsumed(true);
                        }
                    }
                }
            }
            return true;
        }
        return false;
    }

    private class HardKeyButton {
        static final String NONE = "";
        static final String BACK = ActionHandler.SYSTEMUI_TASK_BACK;
        static final String HOME = ActionHandler.SYSTEMUI_TASK_HOME;
        static final String RECENTS = ActionHandler.SYSTEMUI_TASK_RECENTS;
        static final String MENU = ActionHandler.SYSTEMUI_TASK_MENU;
        static final String ASSIST = ActionHandler.SYSTEMUI_TASK_ASSIST;
        static final String VOICE = ActionHandler.SYSTEMUI_TASK_VOICE_SEARCH;
        static final String SLEEP = ActionHandler.SYSTEMUI_TASK_SCREENOFF;

        private Handler mHandler;
        private ActionReceiver mActionReceiver;

        private String mSingleTapUri = NONE;
        private String mLongPressUri = NONE;
        private String mDoubleTapUri = NONE;
        private String mSingleTap = NONE;
        private String mLongPress = NONE;
        private String mDoubleTap = NONE;
        private String mSingleTapDef = NONE;
        private String mLongPressDef = NONE;
        private String mDoubleTapDef = NONE;

        private boolean mStaticSingleTap = false;
        private boolean mDoubleTapPending = false;
        private boolean mIsPressed = false;
        private boolean mWasConsumed = false;

        public HardKeyButton(Context ctx, String singleTapUri, String longPressUri,
                String doubleTapUri, Handler handler, ActionReceiver receiver) {
            mSingleTapUri = singleTapUri;
            mLongPressUri = longPressUri;
            mDoubleTapUri = doubleTapUri;
            mHandler = handler;
            mActionReceiver = receiver;
        }

        public HardKeyButton(Context ctx, String longPressUri,
                String doubleTapUri, Handler handler, ActionReceiver receiver) {
            this(ctx, NONE, longPressUri, doubleTapUri, handler, receiver);
            mStaticSingleTap = true;
        }

        void setDefaults(String singleTapDef, String longPressDef, String doubleTapDef) {
            mSingleTapDef = singleTapDef;
            if (mStaticSingleTap) {
                mSingleTap = mSingleTapDef;
            }
            mLongPressDef = longPressDef;
            mDoubleTapDef = doubleTapDef;
        }

        final Runnable mDoubleTapTimeout = new Runnable() {
            public void run() {
                if (mDoubleTapPending) {
                    mDoubleTapPending = false;
                    if (!keyHasSingleTapRecent()) {
                        cancelPreloadRecentApps();
                    }
                    mActionReceiver.onActionDispatched(HardKeyButton.this, mSingleTap);
                }
            }
        };

        final Runnable mSTRunnable = new Runnable() {
            public void run() {
                mActionReceiver.onActionDispatched(HardKeyButton.this, mSingleTap);
            }
        };

        final Runnable mDTRunnable = new Runnable() {
            public void run() {
                mActionReceiver.onActionDispatched(HardKeyButton.this, mDoubleTap);
            }
        };

        final Runnable mLPRunnable = new Runnable() {
            public void run() {
                mActionReceiver.onActionDispatched(HardKeyButton.this, mLongPress);
            }
        };

        boolean keyHasSingleTapRecent() {
            return RECENTS.equals(mSingleTap);
        }

        boolean keyHasLongPressRecents() {
            return RECENTS.equals(mLongPress);
        }

        boolean keyHasDoubleTapRecents() {
            return RECENTS.equals(mDoubleTap);
        }

        boolean keyHasMenuAction() {
            return MENU.equals(mSingleTap)
                    || MENU.equals(mLongPress)
                    || MENU.equals(mDoubleTap);
        }

        boolean isDoubleTapEnabled() {
            return !isActionEmpty(mDoubleTap)
                    || !ActionHandler.SYSTEMUI_TASK_NO_ACTION.equals(mDoubleTap);
        }

        boolean isLongTapEnabled() {
            return !isActionEmpty(mLongPress);
        }

        void updateActions(ContentResolver cr) {
            if (!mStaticSingleTap) {
                mSingleTap = getActionFromProvider(cr, mSingleTapUri, mSingleTapDef);
            }
            mDoubleTap = getActionFromProvider(cr, mDoubleTapUri, mDoubleTapDef);
            mLongPress = getActionFromProvider(cr, mLongPressUri, mLongPressDef);
        }

        void setDoubleTapPending(boolean pending) {
            mDoubleTapPending = pending;
        }

        boolean isDoubleTapPending() {
            return mDoubleTapPending;
        }

        void setPressed(boolean pressed) {
            mIsPressed = pressed;
        }

        boolean isPressed() {
            return mIsPressed;
        }

        void setWasConsumed(boolean consumed) {
            mWasConsumed = consumed;
        }

        boolean wasConsumed() {
            return mWasConsumed;
        }

        String getActionFromProvider(ContentResolver cr, String uri,
                String def) {
            String tmp = Settings.System.getStringForUser(cr, uri, UserHandle.USER_CURRENT);
            tmp = checkEmpty(tmp, def);
            return tmp;
        }

        String checkEmpty(String action, String def) {
            if (isActionEmpty(action)) {
                action = def;
            }
            return action;
        }

        boolean isActionEmpty(String action) {
            return TextUtils.isEmpty(action)
                    || action.startsWith("empty")
                    || null == action;
        }

        void fireDoubleTap() {
            mHandler.post(mDTRunnable);
        }

        void fireLongPress() {
            mHandler.post(mLPRunnable);
        }

        void fireSingleTap() {
            mHandler.post(mSTRunnable);
        }

        void cancelDTTimeout() {
            mHandler.removeCallbacks(mDoubleTapTimeout);
        }

        void postDTTimeout() {
            mHandler.postDelayed(mDoubleTapTimeout, ViewConfiguration.getDoubleTapTimeout());
        }
    }

    private TelecomManager getTelecommService() {
        return (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
    }

    private static final int[] WINDOW_TYPES_WHERE_HOME_DOESNT_WORK = {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
    };

    @Override
    public boolean handleAction(String action) {
        // TODO Auto-generated method stub
        return false;
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            // Observe all users' changes
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HARDWARE_BUTTON_ASSIST_SINGLETAP), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HARDWARE_BUTTON_MENU_SINGLETAP), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HARDWARE_BUTTON_RECENT_SINGLETAP), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HARDWARE_BUTTON_ASSIST_DOUBLETAP), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HARDWARE_BUTTON_ASSIST_LONGPRESS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HARDWARE_BUTTON_BACK_DOUBLETAP), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HARDWARE_BUTTON_BACK_LONGPRESS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HARDWARE_BUTTON_HOME_DOUBLETAP), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HARDWARE_BUTTON_HOME_LONGPRESS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HARDWARE_BUTTON_MENU_DOUBLETAP), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HARDWARE_BUTTON_MENU_LONGPRESS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HARDWARE_BUTTON_RECENT_DOUBLETAP), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.HARDWARE_BUTTON_RECENT_LONGPRESS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.Secure.RING_HOME_BUTTON_BEHAVIOR), false, this,
                    UserHandle.USER_ALL);

            updateKeyAssignments();
        }

        @Override
        public void onChange(boolean selfChange) {
            updateKeyAssignments();
        }
    }

    private void updateKeyAssignments() {
        ContentResolver cr = mContext.getContentResolver();
        synchronized (mLock) {
            final boolean hasMenu = (mDeviceHardwareKeys & KEY_MASK_MENU) != 0;
            final boolean hasHome = (mDeviceHardwareKeys & KEY_MASK_HOME) != 0;
            final boolean hasAssist = (mDeviceHardwareKeys & KEY_MASK_ASSIST) != 0;
            final boolean hasAppSwitch = (mDeviceHardwareKeys & KEY_MASK_APP_SWITCH) != 0;

            mBackButton.updateActions(cr);
            mHomeButton.updateActions(cr);
            mRecentButton.updateActions(cr);
            mMenuButton.updateActions(cr);
            mAssistButton.updateActions(cr);

            boolean hasMenuKeyEnabled = false;

            if (hasHome) {
                hasMenuKeyEnabled = mHomeButton.keyHasMenuAction();
            }
            if (hasMenu) {
                hasMenuKeyEnabled |= mMenuButton.keyHasMenuAction();
            }
            if (hasAssist) {
                hasMenuKeyEnabled |= mAssistButton.keyHasMenuAction();
            }
            if (hasAppSwitch) {
                hasMenuKeyEnabled |= mRecentButton.keyHasMenuAction();
            }
            hasMenuKeyEnabled |= mBackButton.keyHasMenuAction();

            // let PWM know to update menu key settings
            Message msg = mHandler.obtainMessage(MSG_UPDATE_MENU_KEY);
            msg.arg1 = hasMenuKeyEnabled ? 1 : 0;
            mHandler.sendMessage(msg);

            mRingHomeBehavior = Settings.Secure.getIntForUser(cr,
                    Settings.Secure.RING_HOME_BUTTON_BEHAVIOR,
                    Settings.Secure.RING_HOME_BUTTON_BEHAVIOR_DEFAULT,
                    UserHandle.USER_CURRENT);
        }
    }
}
