/*
 * Copyright (C) 2014 Balys Valentukevicius
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
 * Heavily modified for TeamEos NX implementation by Randall Rushing aka Bigrushdog
 * 
 */

package com.android.systemui.nx.eyecandy;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Property;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import com.android.systemui.R;

public class NxRipple implements View.OnTouchListener {
    private static final int DEFAULT_DURATION = 350;
    private static final int DEFAULT_FADE_DURATION = 75;
    private static final float DEFAULT_DIAMETER_DP = 10;
    private static final float DEFAULT_ALPHA = 0.4f;
    private static final int DEFAULT_COLOR = Color.WHITE;
    private static final int DEFAULT_BACKGROUND = 0x22ffffff;
    private static final boolean DEFAULT_HOVER = true;
    private static final boolean DEFAULT_DELAY_CLICK = true;
    private static final boolean DEFAULT_PERSISTENT = false;
    private static final int FADE_EXTRA_DELAY = 50;
    private static final long HOVER_DURATION = 2500;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect bounds = new Rect();

    private int rippleColor;
    private boolean rippleHover;
    private int rippleDiameter;
    private int rippleDuration;
    private int rippleAlpha;
    private boolean rippleDelayClick;
    private int rippleFadeDuration;
    private boolean ripplePersistent;
    private Drawable rippleBackground;
    private Point currentCoords = new Point();
    private Point previousCoords = new Point();
    private AnimatorSet rippleAnimator;
    private ObjectAnimator hoverAnimator;
    private float radius;

    private boolean eventCancelled;
    private boolean mDrawBackground = false;
    private View mHost;

    public NxRipple(View v) {
        mHost = v;
        init();
    }

    private void init() {
        final Resources res = mHost.getResources();

        rippleColor = res.getColor(R.color.status_bar_clock_color);
        rippleBackground = new ColorDrawable(adjustBgAlpha(rippleColor, 0.15f));
        rippleAlpha = (int) (255 * DEFAULT_ALPHA);

        rippleDiameter = (int) dpToPx(res, DEFAULT_DIAMETER_DP);
        rippleHover = DEFAULT_HOVER;
        rippleDuration = DEFAULT_DURATION;
        rippleDelayClick = DEFAULT_DELAY_CLICK;
        rippleFadeDuration = DEFAULT_FADE_DURATION;
        ripplePersistent = DEFAULT_PERSISTENT;

        paint.setColor(rippleColor);
        paint.setAlpha(rippleAlpha);
    }

    public void updateResources(Resources res) {
        rippleColor = res.getColor(R.color.status_bar_clock_color);
        rippleBackground = new ColorDrawable(adjustBgAlpha(rippleColor, 0.15f));
        rippleBackground.setBounds(bounds);
        paint.setColor(rippleColor);
        paint.setAlpha(rippleAlpha);
        mHost.invalidate();
    }

    public int adjustBgAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return Color.argb(alpha, red, green, blue);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        final int action = event.getAction();
        boolean isEventInBounds = bounds.contains((int) event.getX(), (int) event.getY());
        if (isEventInBounds) {
            previousCoords.set(currentCoords.x, currentCoords.y);
            currentCoords.set((int) event.getX(), (int) event.getY());
        }
        switch (action) {
            case MotionEvent.ACTION_UP:
                if (isEventInBounds) {
                    startRipple(null);
                } else if (!rippleHover) {
                    setRadius(0);
                }
                break;
            case MotionEvent.ACTION_DOWN:
                eventCancelled = false;
                mDrawBackground = true;
                if (rippleHover) {
                    startHover();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (rippleHover) {
                    startRipple(null);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (rippleHover) {
                    if (isEventInBounds && !eventCancelled) {
                        mHost.invalidate();
                    } else if (!isEventInBounds) {
                        startRipple(null);
                    }
                }
                if (!isEventInBounds) {
                    if (hoverAnimator != null) {
                        hoverAnimator.cancel();
                    }
                    eventCancelled = true;
                }
                break;
        }
        return false;
    }

    public void onSizeChanged(int w, int h, int oldw, int oldh) {
        bounds.set(0, 0, w, h);
        rippleBackground.setBounds(bounds);
    }

    public void onDraw(Canvas canvas) {
        if (mDrawBackground) {
            rippleBackground.draw(canvas);
        }
        canvas.drawCircle(currentCoords.x, currentCoords.y, radius, paint);
    }

    static int getSmallerDimen(int width, int height) {
        if (width <= 0 || height <= 0) {
            return 35;
        } else {
            return width > height ? height : width;
        }
    }

    static float dpToPx(Resources resources, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                resources.getDisplayMetrics());
    }

    private void startRipple(final Runnable animationEndRunnable) {
        if (eventCancelled)
            return;

        float endRadius = getEndRadius();

        cancelAnimations();

        rippleAnimator = new AnimatorSet();
        rippleAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!ripplePersistent) {
                    setRadius(0);
                    setRippleAlpha(rippleAlpha);
                }
                if (animationEndRunnable != null && rippleDelayClick) {
                    animationEndRunnable.run();
                }
                mDrawBackground = false;
                mHost.invalidate();
            }
        });

        ObjectAnimator ripple = ObjectAnimator.ofFloat(this, radiusProperty, radius, endRadius);
        ripple.setDuration(rippleDuration);
        ripple.setInterpolator(new DecelerateInterpolator());
        ObjectAnimator fade = ObjectAnimator.ofInt(this, circleAlphaProperty, rippleAlpha, 0);
        fade.setDuration(rippleFadeDuration);
        fade.setInterpolator(new AccelerateInterpolator());
        fade.setStartDelay(rippleDuration - rippleFadeDuration - FADE_EXTRA_DELAY);

        if (ripplePersistent) {
            rippleAnimator.play(ripple);
        } else if (getRadius() > endRadius) {
            fade.setStartDelay(0);
            rippleAnimator.play(fade);
        } else {
            rippleAnimator.playTogether(ripple, fade);
        }
        rippleAnimator.start();
    }

    private void startHover() {
        if (eventCancelled)
            return;

        if (hoverAnimator != null) {
            hoverAnimator.cancel();
        }
        final float radius = (float) (Math.sqrt(Math.pow(mHost.getWidth(), 2)
                + Math.pow(mHost.getHeight(), 2)) * 1.2f);
        hoverAnimator = ObjectAnimator.ofFloat(this, radiusProperty, rippleDiameter, radius)
                .setDuration(HOVER_DURATION);
        hoverAnimator.setInterpolator(new LinearInterpolator());
        hoverAnimator.start();
    }

    private void cancelAnimations() {
        if (rippleAnimator != null) {
            rippleAnimator.cancel();
            rippleAnimator.removeAllListeners();
        }

        if (hoverAnimator != null) {
            hoverAnimator.cancel();
        }
    }

    private float getEndRadius() {
        final int width = mHost.getWidth();
        final int height = mHost.getHeight();

        final int halfWidth = width / 2;
        final int halfHeight = height / 2;

        final float radiusX = halfWidth > currentCoords.x ? width - currentCoords.x
                : currentCoords.x;
        final float radiusY = halfHeight > currentCoords.y ? height - currentCoords.y
                : currentCoords.y;

        return (float) Math.sqrt(Math.pow(radiusX, 2) + Math.pow(radiusY, 2)) * 1.2f;
    }

    private Property<NxRipple, Float> radiusProperty = new Property<NxRipple, Float>(Float.class,
            "radius") {
        @Override
        public Float get(NxRipple object) {
            return object.getRadius();
        }

        @Override
        public void set(NxRipple object, Float value) {
            object.setRadius(value);
        }
    };

    private float getRadius() {
        return radius;
    }

    public void setRadius(float radius) {
        this.radius = radius;
        mHost.invalidate();
    }

    private Property<NxRipple, Integer> circleAlphaProperty = new Property<NxRipple, Integer>(
            Integer.class, "rippleAlpha") {
        @Override
        public Integer get(NxRipple object) {
            return object.getRippleAlpha();
        }

        @Override
        public void set(NxRipple object, Integer value) {
            object.setRippleAlpha(value);
        }
    };

    public int getRippleAlpha() {
        return paint.getAlpha();
    }

    public void setRippleAlpha(Integer rippleAlpha) {
        paint.setAlpha(rippleAlpha);
        mHost.invalidate();
    }

    public void setRippleColor(int rippleColor) {
        this.rippleColor = rippleColor;
        paint.setColor(rippleColor);
        paint.setAlpha(rippleAlpha);
        mHost.invalidate();
    }

    public void setRippleDiameter(int rippleDiameter) {
        this.rippleDiameter = rippleDiameter;
    }

    public void setRippleDuration(int rippleDuration) {
        this.rippleDuration = rippleDuration;
    }

    public void setRippleBackground(int color) {
        rippleBackground = new ColorDrawable(color);
        rippleBackground.setBounds(bounds);
        mHost.invalidate();
    }

    public void setRippleHover(boolean rippleHover) {
        this.rippleHover = rippleHover;
    }

    public void setRippleDelayClick(boolean rippleDelayClick) {
        this.rippleDelayClick = rippleDelayClick;
    }

    public void setRippleFadeDuration(int rippleFadeDuration) {
        this.rippleFadeDuration = rippleFadeDuration;
    }

    public void setRipplePersistent(boolean ripplePersistent) {
        this.ripplePersistent = ripplePersistent;
    }

    public void setDefaultRippleAlpha(int alpha) {
        this.rippleAlpha = alpha;
        paint.setAlpha(alpha);
        mHost.invalidate();
    }

}
