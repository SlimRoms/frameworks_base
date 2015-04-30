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
 * Fires actions based on detected motion. calculates long and short swipes
 * as well as double taps. User can set "long swipe thresholds" for custom
 * long swipe definition. 
 *
 */

package com.android.systemui.nx;

import com.android.internal.util.actions.ActionUtils;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import com.android.systemui.nx.BaseGestureDetector.OnGestureListener;

public class NxGestureHandler implements OnGestureListener {

    public interface Swipeable {
        public boolean onDoubleTapEnabled();
        public void onSingleLeftPress();
        public void onSingleRightPress();
        public void onDoubleLeftTap();
        public void onDoubleRightTap();
        public void onLongLeftPress();
        public void onLongRightPress();
        public void onShortLeftSwipe();
        public void onLongLeftSwipe();
        public void onShortRightSwipe();
        public void onLongRightSwipe();
    }

    // AOSP DT timeout feels a bit slow on nx
    private static final int DT_TIMEOUT = ViewConfiguration.getDoubleTapTimeout() - 100;

    // in-house double tap logic
    private Handler mHandler = new Handler();
    private boolean isDoubleTapPending;
    private boolean wasConsumed;

    // long swipe thresholds
    private float mLeftLandVal;
    private float mRightLandVal;
    private float mLeftPortVal;
    private float mRightPortVal;

    // vertical navbar (usually normal screen size)
    private float mUpVal;
    private float mDownVal;

    // pass motion events to listener
    private Swipeable mReceiver;
    // watch for user changes to swipe thresholds
    private GestureObserver mObserver;
    private Context mContext;

    // for width/height logic
    private View mHost;
    private boolean mVertical;

    private Runnable mDoubleTapLeftTimeout = new Runnable() {
        @Override
        public void run() {
            wasConsumed = false;
            isDoubleTapPending = false;
            mReceiver.onSingleLeftPress();
        }
    };

    private Runnable mDoubleTapRightTimeout = new Runnable() {
        @Override
        public void run() {
            wasConsumed = false;
            isDoubleTapPending = false;
            mReceiver.onSingleRightPress();
        }
    };

    public NxGestureHandler(Context context, Swipeable swiper, View host) {
        mContext = context;
        mReceiver = swiper;
        mHost = host;
        mObserver = new GestureObserver(mHandler);
        mObserver.register();
        updateSettings();
    }

    // special case: double tap for screen off we never capture up motion event
    // maybe use broadcast receiver instead on depending on host
    public void onScreenStateChanged(boolean screeOn) {
        wasConsumed = false;
    }

    public void setOnSwipeListener(Swipeable swiper) {
        if (swiper != null) {
            mReceiver = swiper;
        }
    }

    @Override
    public boolean onDown(MotionEvent e) {
        if (isDoubleTapPending) {
            boolean isRight = isRightSide(e.getX(), e.getY());
            isDoubleTapPending = false;
            wasConsumed = true;
            mHandler.removeCallbacks(mDoubleTapLeftTimeout);
            mHandler.removeCallbacks(mDoubleTapRightTimeout);
            if (isRight) {
                mReceiver.onDoubleRightTap();
            } else {
                mReceiver.onDoubleLeftTap();
            }
            return true;
        }
        return false;
    }

    @Override
    public void onShowPress(MotionEvent e) {
        // TODO Auto-generated method stub
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        boolean isRight = isRightSide(e.getX(), e.getY());
        if (mReceiver.onDoubleTapEnabled()) {
            if (wasConsumed) {
                wasConsumed = false;
                return true;
            }
            isDoubleTapPending = true;
            if (isRight) {
                mHandler.postDelayed(mDoubleTapRightTimeout, DT_TIMEOUT);
            } else {
                mHandler.postDelayed(mDoubleTapLeftTimeout, DT_TIMEOUT);
            }
        } else {
            if (isRight) {
                mReceiver.onSingleRightPress();
            } else {
                mReceiver.onSingleLeftPress();
            }
        }
        return true;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {
        boolean isRight = isRightSide(e.getX(), e.getY());
        if (isRight) {
            mReceiver.onLongRightPress();
        } else {
            mReceiver.onLongLeftPress();
        }
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
            float velocityY) {

        boolean isVertical = mVertical;
        boolean isLandscape = ActionUtils.isLandscape(mContext);

        final float deltaParallel = isVertical ? e2.getY() - e1.getY() : e2
                .getX() - e1.getX();

        boolean isLongSwipe = isLongSwipe(mHost.getWidth(), mHost.getHeight(),
                deltaParallel, isVertical, isLandscape);

        if (deltaParallel > 0) {
            if (isVertical) {
                if (isLongSwipe) {
                    mReceiver.onLongLeftSwipe();
                } else {
                    mReceiver.onShortLeftSwipe();
                }
            } else {
                if (isLongSwipe) {
                    mReceiver.onLongRightSwipe();
                } else {
                    mReceiver.onShortRightSwipe();
                }
            }
        } else {
            if (isVertical) {
                if (isLongSwipe) {
                    mReceiver.onLongRightSwipe();
                } else {
                    mReceiver.onShortRightSwipe();
                }
            } else {
                if (isLongSwipe) {
                    mReceiver.onLongLeftSwipe();
                } else {
                    mReceiver.onShortLeftSwipe();
                }
            }
        }
        return true;
    }

    private int getScreenSize() {
        return Resources.getSystem().getConfiguration().screenLayout &
                Configuration.SCREENLAYOUT_SIZE_MASK;
    }

    // expose to NxBarView for config changes
    public void setIsVertical(boolean isVertical) {
        mVertical = isVertical;
    }

    public void unregister() {
        mObserver.unregister();
    }

    private boolean isRightSide(float x, float y) {
        float length = mVertical ? mHost.getHeight() : mHost.getWidth();
        float pos = mVertical ? y : x;
        length /= 2;
        return mVertical ? pos < length : pos > length;
    }

    private boolean isLongSwipe(float width, float height, float distance,
            boolean isVertical, boolean isLandscape) {
        float size;
        float longPressThreshold;

        // determine correct bar dimensions to calculate against
        if (isLandscape) {
            if (isVertical) {
                size = height;
            } else {
                size = width;
            }
        } else {
            size = width;
        }
        // determine right or left
        // greater than zero is either right or up
        if (distance > 0) {
            if (isLandscape) {
                // must be landscape for vertical bar
                if (isVertical) {
                    // landscape with vertical bar
                    longPressThreshold = mUpVal;
                } else {
                    // landscape horizontal bar
                    longPressThreshold = mRightLandVal;
                }
            } else {
                // portrait: can't have vertical navbar
                longPressThreshold = mRightPortVal;
            }
        } else {
            // left or down
            if (isLandscape) {
                // must be landscape for vertical bar
                if (isVertical) {
                    // landscape with vertical bar
                    longPressThreshold = mDownVal;
                } else {
                    // landscape horizontal bar
                    longPressThreshold = mLeftLandVal;
                }
            } else {
                // portrait: can't have vertical navbar
                longPressThreshold = mLeftPortVal;
            }
        }
        return Math.abs(distance) > (size * longPressThreshold);
    }

    private void updateSettings() {
        // get default swipe thresholds based on screensize
        float leftDefH;
        float rightDefH;
        float leftDefV;
        float rightDefV;

        // vertical bar, bar can move (normal screen)
        float upDef = 0.40f;
        float downDef = 0.40f;

        int screenSize = getScreenSize();

        if (Configuration.SCREENLAYOUT_SIZE_NORMAL == screenSize) {
            leftDefH = 0.40f;
            rightDefH = 0.40f;
            leftDefV = 0.35f;
            rightDefV = 0.35f;
        } else if (Configuration.SCREENLAYOUT_SIZE_LARGE == screenSize) {
            leftDefH = 0.30f;
            rightDefH = 0.30f;
            leftDefV = 0.40f;
            rightDefV = 0.40f;
        } else if (Configuration.SCREENLAYOUT_SIZE_XLARGE == screenSize) {
            leftDefH = 0.25f;
            rightDefH = 0.25f;
            leftDefV = 0.30f;
            rightDefV = 0.30f;
        } else {
            leftDefH = 0.40f;
            rightDefH = 0.40f;
            leftDefV = 0.40f;
            rightDefV = 0.40f;
        }

        mLeftLandVal = Settings.System.getFloatForUser(
                mContext.getContentResolver(), Settings.System.NX_LONGSWIPE_THRESHOLD_LEFT_LAND,
                leftDefH, UserHandle.USER_CURRENT);

        mRightLandVal = Settings.System.getFloatForUser(
                mContext.getContentResolver(), Settings.System.NX_LONGSWIPE_THRESHOLD_RIGHT_LAND,
                rightDefH, UserHandle.USER_CURRENT);

        mLeftPortVal = Settings.System.getFloatForUser(
                mContext.getContentResolver(), Settings.System.NX_LONGSWIPE_THRESHOLD_LEFT_PORT,
                leftDefV, UserHandle.USER_CURRENT);

        mRightPortVal = Settings.System.getFloatForUser(
                mContext.getContentResolver(), Settings.System.NX_LONGSWIPE_THRESHOLD_RIGHT_PORT,
                rightDefV, UserHandle.USER_CURRENT);

        mUpVal = Settings.System.getFloatForUser(
                mContext.getContentResolver(), Settings.System.NX_LONGSWIPE_THRESHOLD_UP_LAND,
                upDef, UserHandle.USER_CURRENT);

        mDownVal = Settings.System.getFloatForUser(
                mContext.getContentResolver(), Settings.System.NX_LONGSWIPE_THRESHOLD_DOWN_LAND,
                downDef, UserHandle.USER_CURRENT);
    }

    private class GestureObserver extends ContentObserver {

        public GestureObserver(Handler handler) {
            super(handler);
        }

        void register() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NX_LONGSWIPE_THRESHOLD_LEFT_LAND), false,
                    GestureObserver.this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NX_LONGSWIPE_THRESHOLD_RIGHT_LAND), false,
                    GestureObserver.this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NX_LONGSWIPE_THRESHOLD_LEFT_PORT), false,
                    GestureObserver.this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NX_LONGSWIPE_THRESHOLD_RIGHT_PORT), false,
                    GestureObserver.this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NX_LONGSWIPE_THRESHOLD_UP_LAND), false,
                    GestureObserver.this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NX_LONGSWIPE_THRESHOLD_DOWN_LAND), false,
                    GestureObserver.this, UserHandle.USER_ALL);
        }

        void unregister() {
            mContext.getContentResolver().unregisterContentObserver(
                    GestureObserver.this);
        }

        public void onChange(boolean selfChange, Uri uri) {
                updateSettings();
        }
    }
}
