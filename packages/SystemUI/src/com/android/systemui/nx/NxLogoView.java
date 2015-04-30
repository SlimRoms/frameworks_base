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
 * NX indicator that soon will be more than eye candy
 *
 */

package com.android.systemui.nx;

import com.android.systemui.R;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.view.animation.ScaleAnimation;
import android.view.animation.Animation.AnimationListener;
import android.widget.ImageView;

public class NxLogoView extends ImageView {
    public static final String TAG = NxLogoView.class.getSimpleName();
    private static final int ALPHA_DURATION = 250;

    private boolean mIsAnimating;
    private boolean mSpinAnimationEnabled = true;
    private boolean mLogoEnabled = true;

    public NxLogoView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NxLogoView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setBackground(null);
        updateResources(context.getResources());
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // TEMP: pass all events to NX, for now
        return false;
    }

    public void updateResources(Resources res) {
        int color = res.getColor(R.color.status_bar_clock_color);
        Drawable logo = getDrawable();
        logo.setColorFilter(color, Mode.SRC_ATOP);
    }

    public boolean isAnimating() {
        return mIsAnimating;
    }

    public void updateVisibility(boolean isPulsing) {
        if (!mLogoEnabled || isPulsing) {
            animateAlpha(false, true);
        } else if (mLogoEnabled && !isPulsing) {
            animateAlpha(true, true);
        }
    }

    private void animateAlpha(boolean show, boolean animate) {
        animate().cancel();
        final float alpha = show ? 1.0f : 0.0f;
        if (!animate) {
            setAlpha(alpha);
        } else {
            final int duration = ALPHA_DURATION;
            animate()
                    .alpha(alpha)
                    .setDuration(duration)
                    .start();
        }
    }

    public void setSpinEnabled(boolean enabled) {
        if (enabled == mSpinAnimationEnabled) {
            return;
        }
        mSpinAnimationEnabled = enabled;
        if (!enabled) {
            animate().cancel();
        }
    }

    public void setLogoEnabled(boolean enabled) {
        if (enabled == mLogoEnabled) {
            return;
        }
        mLogoEnabled = enabled;
    }

    public void animateSpinner(boolean isPressed) {
        if (!mLogoEnabled || !mSpinAnimationEnabled) {
            return;
        }
        animate().cancel();
        final AnimationSet spinAnim = getSpinAnimation(isPressed);
        startAnimation(spinAnim);
    }

    private AnimationSet getSpinAnimation(boolean isPressed) {
        final float from = isPressed ? 1.0f : 0.0f;
        final float to = isPressed ? 0.0f : 1.0f;
        final float fromDeg = isPressed ? 0.0f : 360.0f;
        final float toDeg = isPressed ? 360.0f : 0.0f;

        Animation scale = new ScaleAnimation(from, to, from, to, Animation.RELATIVE_TO_SELF,
                0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        RotateAnimation rotate = new RotateAnimation(fromDeg, toDeg, Animation.RELATIVE_TO_SELF,
                0.5f, Animation.RELATIVE_TO_SELF, 0.5f);

        AnimationSet animSet = new AnimationSet(true);
        animSet.setInterpolator(new LinearInterpolator());
        animSet.setDuration(150);
        animSet.setFillAfter(true);
        animSet.addAnimation(scale);
        animSet.addAnimation(rotate);
        animSet.setAnimationListener(new AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                mIsAnimating = true;
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                mIsAnimating = false;
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
                // TODO Auto-generated method stub
            }

        });
        return animSet;
    }
}
