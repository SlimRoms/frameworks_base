/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.animation.Animator;

import com.android.internal.util.actions.ActionHandler;
import android.animation.ObjectAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import java.lang.Math;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.SoftkeyActionHandler;
import com.android.systemui.statusbar.phone.SoftkeyActionHandler.ButtonInfo;

public class KeyButtonView extends ImageView {
    private static final String TAG = "StatusBar.KeyButtonView";
    private static final boolean DEBUG = false;

    // TODO: Get rid of this
    public static final float DEFAULT_QUIESCENT_ALPHA = 1f;

    private long mDownTime;
    private long mUpTime;
    private int mTouchSlop;
    private float mDrawingAlpha = 1f;
    private float mQuiescentAlpha = DEFAULT_QUIESCENT_ALPHA;
    private AudioManager mAudioManager;
    private Animator mAnimateToQuiescent = new ObjectAnimator();
    private KeyButtonRipple mRipple;

    private PowerManager mPm;

    private View.OnTouchListener mHomeActionListener;

    private boolean mHasSingleAction = true, mHasDoubleAction, mHasLongAction;
    private boolean mIsRecentsAction = false, mIsRecentsSingleAction, mIsRecentsLongAction,
            mIsRecentsDoubleTapAction;

    private final int mSingleTapTimeout = ViewConfiguration.getTapTimeout();
    private final int mSingleTapTimeoutWithDT = mSingleTapTimeout + 175;
    private int mLongPressTimeout;
    private int mDoubleTapTimeout;
    private ButtonInfo mActions;
    private SoftkeyActionHandler mActionHandler;
    public boolean mHasBlankSingleAction = false;
    private boolean mDoOverrideSingleTap;

    public KeyButtonView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyButtonView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs);

        setDrawingAlpha(mQuiescentAlpha);
        setClickable(true);
        setLongClickable(false);

        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        setBackground(mRipple = new KeyButtonRipple(context, this));
        mPm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    }

    public void setLongPressTimeout(int lpTimeout) {
        mLongPressTimeout = lpTimeout;
    }

    public void setDoubleTapTimeout(int dtTimeout) {
        mDoubleTapTimeout = dtTimeout;
    }

    public void setActionHandler(SoftkeyActionHandler handler) {
        mActionHandler = handler;
    }

    public void setButtonInfo(ButtonInfo actions) {
        this.mActions = actions;

        setTag(mActions.singleAction); // should be OK even if it's null

        mHasSingleAction = mActions != null
                && (mActions.singleAction != null && !mActions.singleAction
                        .equals(ActionHandler.SYSTEMUI_TASK_NO_ACTION));
        mHasLongAction = mActions != null && mActions.longPressAction != null
                && !mActions.longPressAction.equals(ActionHandler.SYSTEMUI_TASK_NO_ACTION);
        mHasDoubleAction = mActions != null && mActions.doubleTapAction != null
                && !mActions.doubleTapAction.equals(ActionHandler.SYSTEMUI_TASK_NO_ACTION);
        mHasBlankSingleAction = mHasSingleAction
                && mActions.singleAction.equals(ActionHandler.SYSTEMUI_TASK_NO_ACTION);

        mIsRecentsSingleAction = (mHasSingleAction && mActions.singleAction
                .equals(ActionHandler.SYSTEMUI_TASK_RECENTS));
        mIsRecentsLongAction = (mHasLongAction && mActions.longPressAction
                .equals(ActionHandler.SYSTEMUI_TASK_RECENTS));
        mIsRecentsDoubleTapAction = (mHasDoubleAction && mActions.doubleTapAction
                .equals(ActionHandler.SYSTEMUI_TASK_RECENTS));

        if (mIsRecentsSingleAction || mIsRecentsLongAction || mIsRecentsDoubleTapAction) {
            mIsRecentsAction = true;
        }

        setLongClickable(mHasLongAction);
        if (getId() == R.id.home && mHomeActionListener != null) {
            setOnTouchListener(mHasLongAction ? null : mHomeActionListener);
        }
    }

    private int getSingleTapTimeout() {
        return mHasDoubleAction ? mSingleTapTimeoutWithDT : mSingleTapTimeout;
    }

    public void setHomeActionListener(View.OnTouchListener homeListener) {
        if (this.getId() == R.id.home) {
            if (mHomeActionListener == null) {
                mHomeActionListener = homeListener;
            }
            if (mActions.longPressAction.equals(ActionHandler.SYSTEMUI_TASK_NO_ACTION)) {
                setOnTouchListener(homeListener);
            } else {
                setOnTouchListener(null);
            }
        }
    }

    public void setQuiescentAlpha(float alpha, boolean animate) {
        mAnimateToQuiescent.cancel();
        alpha = Math.min(Math.max(alpha, 0), 1);
        if (alpha == mQuiescentAlpha && alpha == mDrawingAlpha)
            return;
        mQuiescentAlpha = alpha;
        if (DEBUG)
            Log.d(TAG, "New quiescent alpha = " + mQuiescentAlpha);
        if (animate) {
            mAnimateToQuiescent = animateToQuiescent();
            mAnimateToQuiescent.start();
        } else {
            setDrawingAlpha(mQuiescentAlpha);
        }
    }

    private ObjectAnimator animateToQuiescent() {
        return ObjectAnimator.ofFloat(this, "drawingAlpha", mQuiescentAlpha);
    }

    public float getQuiescentAlpha() {
        return mQuiescentAlpha;
    }

    public float getDrawingAlpha() {
        return mDrawingAlpha;
    }

    public void setDrawingAlpha(float x) {
        setImageAlpha((int) (x * 255));
        mDrawingAlpha = x;
    }

    public boolean onTouchEvent(MotionEvent ev) {
        if (mHasBlankSingleAction) {
            Log.i(TAG, "Has blanking action");
            return true;
        }

        final int action = ev.getAction();
        int x, y;

        // A lot of stuff is about to happen. Lets get ready.
        mPm.cpuBoost(750000);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (mIsRecentsAction) {
                    ActionHandler.preloadRecentApps();
                }
                mDownTime = SystemClock.uptimeMillis();
                setPressed(true);
                if (mHasSingleAction) {
                    removeCallbacks(mSingleTap);
                }
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                long diff = mDownTime - mUpTime; // difference between last up
                                                 // and now
                if (mHasDoubleAction && diff <= mDoubleTapTimeout) {
                    doDoubleTap();
                } else {

                    if (mHasLongAction || KeyButtonView.this.getId() == R.id.recent_apps) {
                        removeCallbacks(mCheckLongPress);
                        postDelayed(mCheckLongPress, mLongPressTimeout);
                    }
                    if (mHasSingleAction) {
                        postDelayed(mSingleTap, getSingleTapTimeout());
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                x = (int) ev.getX();
                y = (int) ev.getY();
                setPressed(x >= -mTouchSlop
                        && x < getWidth() + mTouchSlop
                        && y >= -mTouchSlop
                        && y < getHeight() + mTouchSlop);
                break;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                if (mHasSingleAction) {
                    removeCallbacks(mSingleTap);
                }
                // hack to fix ripple getting stuck. exitHardware() starts an animation,
                // but sometimes does not finish it.
                // TODO: no-op now?
                // mRipple.exitSoftware();
                if (mHasLongAction || KeyButtonView.this.getId() == R.id.recent_apps) {
                    removeCallbacks(mCheckLongPress);
                }
                ActionHandler.cancelPreloadRecentApps();
                break;
            case MotionEvent.ACTION_UP:
                mUpTime = SystemClock.uptimeMillis();
                boolean playSound;

                if (mHasLongAction || KeyButtonView.this.getId() == R.id.recent_apps) {
                    removeCallbacks(mCheckLongPress);
                }
                playSound = isPressed();
                setPressed(false);

                if (playSound) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                }

                if (!mHasDoubleAction && !mHasLongAction && !mDoOverrideSingleTap) {
                    removeCallbacks(mSingleTap);
                    doSinglePress();
                }
                mDoOverrideSingleTap = false;
                break;
        }

        return true;
    }

    public void playSoundEffect(int soundConstant) {
        mAudioManager.playSoundEffect(soundConstant, ActivityManager.getCurrentUser());
    };

    // respect the public call so we don't have to butcher PhoneStatusBar
    public void sendEvent(int action, int flags) {}

    private void doSinglePress() {
        if (callOnClick()) {
            // cool
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        } else if (mIsRecentsSingleAction && mActionHandler.isSecureToFire(mActions.singleAction)) {
            ActionHandler.toggleRecentApps();
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
            return;
        }

        if (mActions != null) {
            if (mActions.singleAction != null && mActionHandler.isSecureToFire(mActions.singleAction)) {
                mActionHandler.performTask(mActions.singleAction);
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
            }
        }
    }

    private void doDoubleTap() {
        if (mHasDoubleAction) {
            removeCallbacks(mSingleTap);
            if (mActionHandler.isSecureToFire(mActions.doubleTapAction)) {
                if (mIsRecentsDoubleTapAction) {
                    ActionHandler.toggleRecentApps();
                } else {
                    mActionHandler.performTask(mActions.doubleTapAction);
                }
            }
        }
    }

    private void doLongPress() {
        if (KeyButtonView.this.getId() == R.id.recent_apps
                && mActionHandler.isLockTaskOn()) {
            mActionHandler.turnOffLockTask();
            mDoOverrideSingleTap = true;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
        } else {
            if (mHasLongAction) {
                removeCallbacks(mSingleTap);
                if (mActionHandler.isSecureToFire(mActions.longPressAction)) {
                    if (mIsRecentsLongAction) {
                        ActionHandler.toggleRecentApps();
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                    } else {
                        mActionHandler.performTask(mActions.longPressAction);
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                    }
                }
            }
        }
    }

    private Runnable mCheckLongPress = new Runnable() {
        public void run() {
            if (isPressed()) {
                removeCallbacks(mSingleTap);
                doLongPress();
            }
        }
    };

    private Runnable mSingleTap = new Runnable() {
        @Override
        public void run() {
            if (!isPressed()) {
                doSinglePress();
            }
        }
    };
}
