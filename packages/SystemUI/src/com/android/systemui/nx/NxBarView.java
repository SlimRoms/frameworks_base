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
 * Gesture based navigation implementation and action executor
 *
 */

package com.android.systemui.nx;

import com.android.internal.util.actions.ActionUtils;

import com.android.systemui.R;
import com.android.systemui.nx.eyecandy.NxMediaController;
import com.android.systemui.nx.eyecandy.NxRipple;
import com.android.systemui.nx.eyecandy.NxSurface;
import com.android.systemui.statusbar.BaseNavigationBar;
import com.android.systemui.statusbar.phone.BarTransitions;
import com.android.systemui.nx.BaseGestureDetector;

import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

public class NxBarView extends BaseNavigationBar implements NxSurface {
    final static String TAG = NxBarView.class.getSimpleName();

    private NxActionHandler mActionHandler;
    private NxGestureHandler mGestureHandler;
    private NxGestureDetector mGestureDetector;
    private final NxBarTransitions mBarTransitions;
    private NxBarObserver mObserver;
    private boolean mRippleEnabled;
    private NxMediaController mMC;
    private PowerManager mPm;
    private NxRipple mRipple;

    private final class NxGestureDetector extends BaseGestureDetector {
        final int LP_TIMEOUT = ViewConfiguration.getLongPressTimeout();
        // no more than default timeout
        final int LP_TIMEOUT_MAX = LP_TIMEOUT;
        // no less than 25ms longer than single tap timeout
        final int LP_TIMEOUT_MIN = 25;
        private int mLongPressTimeout = LP_TIMEOUT;

        public NxGestureDetector(Context context, OnGestureListener listener) {
            super(context, listener);
            // TODO Auto-generated constructor stub
        }

        @Override
        protected int getLongPressTimeout() {
            return mLongPressTimeout;
        }

        void setLongPressTimeout(int timeoutFactor) {
            if (timeoutFactor > LP_TIMEOUT_MAX) {
                timeoutFactor = LP_TIMEOUT_MAX;
            } else if (timeoutFactor < LP_TIMEOUT_MIN) {
                timeoutFactor = LP_TIMEOUT_MIN;
            }
            mLongPressTimeout = timeoutFactor;
        }
    }

    private class NxBarObserver extends ContentObserver {

        public NxBarObserver(Handler handler) {
            super(handler);
        }

        void register() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NX_LOGO_VISIBLE), false,
                    NxBarObserver.this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NX_LOGO_ANIMATES), false,
                    NxBarObserver.this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NX_PULSE_ENABLED), false,
                    NxBarObserver.this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NX_LONGPRESS_TIMEOUT), false,
                    NxBarObserver.this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NX_RIPPLE_ENABLED), false,
                    NxBarObserver.this, UserHandle.USER_ALL);
        }

        void unregister() {
            mContext.getContentResolver().unregisterContentObserver(
                    NxBarObserver.this);
        }

        public void onChange(boolean selfChange, Uri uri) {
            updateLogoEnabled();
            updateLogoAnimates();
            updatePulseEnabled();
            int lpTimeout = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.NX_LONGPRESS_TIMEOUT, mGestureDetector.LP_TIMEOUT_MAX, UserHandle.USER_CURRENT);
            mGestureDetector.setLongPressTimeout(lpTimeout);
            mRippleEnabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.NX_RIPPLE_ENABLED, 1, UserHandle.USER_CURRENT) == 1;
        }
    }

    private final OnTouchListener mNxTouchListener = new OnTouchListener() {

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            final int action = event.getAction();
            if (mUserAutoHideListener != null) {
                mUserAutoHideListener.onTouch(NxBarView.this, event);
            }
            if (action == MotionEvent.ACTION_DOWN) {
                mPm.cpuBoost(1000 * 1000);
                if (!getNxLogo().isAnimating()) {
                    getNxLogo().animateSpinner(true);
                }
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                getNxLogo().animateSpinner(false);
            }
            if (mRippleEnabled) {
                mRipple.onTouch(NxBarView.this, event);
            }
            return mGestureDetector.onTouchEvent(event);
        }
    };

    public NxBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mBarTransitions = new NxBarTransitions(this);
        mDelegateHelper.setForceDisabled(true);
        mActionHandler = new NxActionHandler(context, this);
        mGestureHandler = new NxGestureHandler(context, mActionHandler, this);
        mGestureDetector = new NxGestureDetector(context, mGestureHandler);
        this.setOnTouchListener(mNxTouchListener);
        mObserver = new NxBarObserver(new Handler());
        mObserver.register();
        int lpTimeout = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.NX_LONGPRESS_TIMEOUT, mGestureDetector.LP_TIMEOUT_MAX, UserHandle.USER_CURRENT);
        mRippleEnabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.NX_RIPPLE_ENABLED, 1, UserHandle.USER_CURRENT) == 1;
        mGestureDetector.setLongPressTimeout(lpTimeout);
        mMC = new NxMediaController(context);
        mMC.onSetNxSurface(this);
        mPm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mRipple = new NxRipple(this);
    }

    @Override
    public BarTransitions getBarTransitions() {
        return mBarTransitions;
    }

    public View getNxContainer() {
        return mCurrentView.findViewById(R.id.nav_buttons);
    }

    public NxLogoView getNxLogo() {
        return (NxLogoView) mCurrentView.findViewById(R.id.nx_console);
    }

    @Override
    protected void onKeyguardShowing(boolean showing) {
        mActionHandler.setKeyguardShowing(showing);
        mMC.setKeyguardShowing(showing);
        setDisabledFlags(mDisabledFlags, true /* force */);
        invalidate();
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        updateLogoEnabled();
        updateLogoAnimates();
        updatePulseEnabled();
    }

    @Override
    public void setLeftInLandscape(boolean leftInLandscape) {
        super.setLeftInLandscape(leftInLandscape);
        mMC.setLeftInLandscape(leftInLandscape);
    }

    private void updateLogoAnimates() {
        boolean logoAnimates = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.NX_LOGO_ANIMATES, 1, UserHandle.USER_CURRENT) == 1;
        for (NxLogoView v : ActionUtils.getAllChildren(NxBarView.this, NxLogoView.class)) {
            v.setSpinEnabled(logoAnimates);
        }
    }

    private void updateLogoEnabled() {
        boolean logoEnabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.NX_LOGO_VISIBLE, 1, UserHandle.USER_CURRENT) == 1;
        for (NxLogoView v : ActionUtils.getAllChildren(NxBarView.this, NxLogoView.class)) {
            v.setLogoEnabled(logoEnabled);
        }
        setDisabledFlags(mDisabledFlags, true);
    }

    private void updatePulseEnabled() {
        boolean doPulse = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.NX_PULSE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
        mMC.setPulseEnabled(doPulse);
    }

    @Override
    protected void onUpdateRotatedView(ViewGroup container, Resources res) {
        // maybe do something with nx logo
    }

    @Override
    protected void onUpdateResources(Resources res) {
        mRipple.updateResources(res);
        for (NxLogoView v : ActionUtils.getAllChildren(NxBarView.this, NxLogoView.class)) {
            v.updateResources(res);
        }
    }

    public void setDisabledFlags(int disabledFlags, boolean force) {
        super.setDisabledFlags(disabledFlags, force);
        mGestureHandler.onScreenStateChanged(mScreenOn);
        getNxLogo().updateVisibility(mMC.shouldDrawPulse());
    }

    @Override
    public void reorient() {
        super.reorient();
        mBarTransitions.init(mVertical);
        mGestureHandler.setIsVertical(mVertical);
        setDisabledFlags(mDisabledFlags, true /* force */);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mMC.onSizeChanged();
        mRipple.onSizeChanged(w, h, oldw, oldh);
    }

    @Override
    public void setNavigationIconHints(int hints) {
        // maybe do something with the IME switcher
    }

    @Override
    public Rect onGetSurfaceDimens() {
        Rect rect = new Rect();
        rect.set(0, 0, getWidth(), getHeight());
        return rect;
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (mMC.shouldDrawPulse()) {
            mMC.onDrawNx(canvas);
        }
        if (mRippleEnabled) {
            mRipple.onDraw(canvas);
        }
    }

    @Override
    public void updateBar() {
        setDisabledFlags(mDisabledFlags, true /* force */);
    }

    @Override
    protected void onDispose() {
        mObserver.unregister();
        mActionHandler.unregister();
        mGestureHandler.unregister();
        if (mMC.isPulseEnabled()) {
            mMC.setPulseEnabled(false);
        }        
    }
}
