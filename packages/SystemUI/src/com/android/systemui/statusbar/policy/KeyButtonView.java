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

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.PorterDuff.Mode;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;

import com.android.internal.statusbar.IStatusBarService;

import com.android.systemui.R;

import java.util.ArrayList;

import org.slim.action.ActionConstants;
import org.slim.action.Action;

import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK;

public class KeyButtonView extends ImageView {

    private int mContentDescriptionRes;
    private long mDownTime;
    private int mCode;
    String mClickAction;
    String mLongpressAction;
    String mDoubleTapAction;
    private int mTouchSlop;
    boolean mSupportsLongpress = false;
    boolean mIsLongpressed = false;
    boolean mDoubleTapPending = false;
    boolean mDoubleTapConsumed = false;
    private AudioManager mAudioManager;
    private boolean mGestureAborted;
    private KeyButtonRipple mRipple;
    private LongClickCallback mCallback;
    private GestureDetector mGestureDetector;

    private IStatusBarService mStatusBar;

    private final Runnable mCheckLongPress = new Runnable() {
        public void run() {
            mIsLongpressed = true;
            if (isPressed()) {
                // Log.d("KeyButtonView", "longpressed: " + this);
                if (isLongClickable()) {
                    // Just an old-fashioned ImageView
                    performLongClick();
                } else {
                    sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.FLAG_LONG_PRESS);
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                }
                performLongClick();
                setHapticFeedbackEnabled(true);
            }
        }
    };

    private final Runnable mDoubleTapTimeout = new Runnable() {
        @Override
        public void run() {
            mDoubleTapPending = false;
            performClick();
        }
    };

    public KeyButtonView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyButtonView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.KeyButtonView,
                defStyle, 0);

        mCode = a.getInteger(R.styleable.KeyButtonView_keyCode, 0);

        mSupportsLongpress = a.getBoolean(R.styleable.KeyButtonView_keyRepeat, true);

        TypedValue value = new TypedValue();
        if (a.getValue(R.styleable.KeyButtonView_android_contentDescription, value)) {
            mContentDescriptionRes = value.resourceId;
        }

        a.recycle();

        mStatusBar = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        setClickable(true);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        setBackground(mRipple = new KeyButtonRipple(context, this));

        mGestureDetector = new GestureDetector(
                context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                return true;
            }
        });
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mContentDescriptionRes != 0) {
            setContentDescription(mContext.getString(mContentDescriptionRes));
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (mCode != 0) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_CLICK, null));
            if (mSupportsLongpress || isLongClickable()) {
                info.addAction(
                        new AccessibilityNodeInfo.AccessibilityAction(ACTION_LONG_CLICK, null));
            }
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != View.VISIBLE) {
            jumpDrawablesToCurrentState();
        }
    }

    public void setCode(int code) {
        mCode = code;
    }

    public void setClickAction(String action) {
        mClickAction = action;
        setOnClickListener(mClickListener);
    }

    public void setLongpressAction(String action) {
        mLongpressAction = action;
        if (!action.equals(ActionConstants.ACTION_NULL)) {
            mSupportsLongpress = true;
            setOnLongClickListener(mLongPressListener);
        }
    }

    public void setDoubleTapAction(String action) {
        mDoubleTapAction = action;
    }

    @Override
    public boolean performAccessibilityActionInternal(int action, Bundle arguments) {
        if (action == ACTION_CLICK && mCode != 0) {
            sendEvent(KeyEvent.ACTION_DOWN, 0, SystemClock.uptimeMillis());
            sendEvent(KeyEvent.ACTION_UP, 0);
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
            playSoundEffect(SoundEffectConstants.CLICK);
            return true;
        } else if (action == ACTION_LONG_CLICK && mCode != 0) {
            sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.FLAG_LONG_PRESS);
            sendEvent(KeyEvent.ACTION_UP, 0);
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
            return true;
        }
        return super.performAccessibilityActionInternal(action, arguments);
    }

    public void setRippleColor(int color) {
        mRipple.setColor(color);
    }

    public boolean onTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();
        int x, y;
        if (action == MotionEvent.ACTION_DOWN) {
            mGestureAborted = false;
        }
        if (mGestureAborted) {
            return false;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDownTime = SystemClock.uptimeMillis();
                mIsLongpressed = false;
                setPressed(true);
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (mClickAction.equals(ActionConstants.ACTION_RECENTS)) {
                    try {
                        mStatusBar.preloadRecentApps();
                    } catch (RemoteException e) {}
                }
                if (mCode != 0) {
                    sendEvent(KeyEvent.ACTION_DOWN, 0, mDownTime);
                }
                if (mDoubleTapPending) {
                    mDoubleTapPending = false;
                    removeCallbacks(mDoubleTapTimeout);
                    doubleTap();
                    mDoubleTapConsumed = true;
                } else {
                    removeCallbacks(mCheckLongPress);
                    postDelayed(mCheckLongPress, ViewConfiguration.getLongPressTimeout());
                }
                break;
            case MotionEvent.ACTION_MOVE:
                x = (int)ev.getX();
                y = (int)ev.getY();
                setPressed(x >= -mTouchSlop
                        && x < getWidth() + mTouchSlop
                        && y >= -mTouchSlop
                        && y < getHeight() + mTouchSlop);
                break;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                if (mClickAction.equals(ActionConstants.ACTION_RECENTS)) {
                    try {
                        mStatusBar.cancelPreloadRecentApps();
                    } catch (RemoteException e) {}
                }
                // hack to fix ripple getting stuck. exitHardware() starts an animation,
                // but sometimes does not finish it.
                mRipple.exitSoftware();
                if (mCode != 0) {
                    sendEvent(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED);
                }
                removeCallbacks(mCheckLongPress);
                break;
            case MotionEvent.ACTION_UP:
                final boolean doIt = isPressed();
                setPressed(false);
                if (!doIt && mClickAction.equals(ActionConstants.ACTION_RECENTS)) {
                    try {
                        mStatusBar.cancelPreloadRecentApps();
                    } catch (RemoteException e) {}
                }
                if (!mIsLongpressed) {
                    if (hasDoubleTapAction()) {
                        if (mDoubleTapConsumed) {
                            mDoubleTapConsumed = false;
                        } else {
                            mDoubleTapPending = true;
                            postDelayed(mDoubleTapTimeout,
                                    ViewConfiguration.getDoubleTapTimeout() - 100);
                        }
                    }
                    if (mCode != 0) {
                        if (doIt) {
                            sendEvent(KeyEvent.ACTION_UP, 0);
                        } else {
                            sendEvent(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED);
                        }
                    }
                    if (doIt && !hasDoubleTapAction()) {
                        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
                        performClick();
                    }
                }
                removeCallbacks(mCheckLongPress);
                break;
        }

        return true;
    }

    private boolean hasDoubleTapAction() {
        return mDoubleTapAction != null &&
            mDoubleTapAction != ActionConstants.ACTION_NULL;
    }

    public void playSoundEffect(int soundConstant) {
        mAudioManager.playSoundEffect(soundConstant, ActivityManager.getCurrentUser());
    };

    private OnClickListener mClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            Action.processAction(mContext, mClickAction, false);
            return;
        }
    };

    public void setLongClickCallback(LongClickCallback c) {
        mCallback = c;
        setLongClickable(true);
        setOnLongClickListener(mLongPressListener);
    }

    private OnLongClickListener mLongPressListener = new OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            boolean b = true;
            if (mCallback != null) {
                if (!mCallback.onLongClick(v)) {
                    b = false;
                }
            }
            if (b) Action.processAction(mContext, mLongpressAction, true);
            return true;
        }
    };

    public void sendEvent(int action, int flags) {
        sendEvent(action, flags, SystemClock.uptimeMillis());
    }

    void sendEvent(int action, int flags, long when) {
        final int repeatCount = (flags & KeyEvent.FLAG_LONG_PRESS) != 0 ? 1 : 0;
        final KeyEvent ev = new KeyEvent(mDownTime, when, action, mCode, repeatCount,
                0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                flags | KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
        InputManager.getInstance().injectInputEvent(ev,
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    public void abortCurrentGesture() {
        setPressed(false);
        mGestureAborted = true;
    }

    private void doubleTap() {
        Action.processAction(mContext, mDoubleTapAction, true);
    }

    public interface LongClickCallback {
        public boolean onLongClick(View v);
    }
}
