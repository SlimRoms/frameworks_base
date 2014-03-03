/*
 * Copyright (C) 2014 SlimRoms Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.systemui.slimrecent;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.util.Log;
import android.view.animation.DecelerateInterpolator;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.android.cards.view.CardListView;
import com.android.cards.view.CardView;

import com.android.systemui.R;

public class RecentController implements RecentPanelView.OnExitListener,
        RecentPanelView.OnTasksLoadedListener {

    private static final String TAG = "SlimRecentsController";
    private static final boolean DEBUG = false;

    // Duration for slide in and out fading animation.
    private static final int CONTAINER_SLIDE_IN_OUT_DURATION = 300;

    private Context mContext;
    private WindowManager mWindowManager;

    private boolean mIsShowing;
    private boolean mIsAnimating;
    private boolean mIsToggled;

    // The different views we need.
    private ViewGroup mParentView;
    private ViewGroup mRecentContainer;
    private LinearLayout mRecentContent;
    private ImageView mEmptyRecentView;

    // Main panel view.
    private RecentPanelView mRecentPanelView;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.v(TAG, "onReceive: " + intent);
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                || Intent.ACTION_SCREEN_OFF.equals(action)) {
                // Screen goes off or system dialogs should close.
                // Get rid of our recents screen
                hideRecents();
            }
        }
    };

    public RecentController(Context context) {
        mContext = context;

        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);

        /**
         * Add intent actions to listen on it.
         * Screen off to get rid of recents,
         * same if close system dialogs is requested.
         */
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        mParentView = new FrameLayout(mContext);

        // Inflate our recents layout
        LayoutInflater inflater =
                (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mRecentContainer =
                (RelativeLayout) inflater.inflate(R.layout.slim_recent, null);

        // Get contents for rebuilding and gesture detector.
        mRecentContent =
                (LinearLayout) mRecentContainer.findViewById(R.id.recent_content);

        LinearLayout recentWarningContent =
                (LinearLayout) mRecentContainer.findViewById(R.id.recent_warning_content);

        CardListView cardListView =
                (CardListView) mRecentContainer.findViewById(R.id.recent_list);

        mEmptyRecentView =
                (ImageView) mRecentContainer.findViewById(R.id.empty_recent);

        // Prepare gesture detector.
        final ScaleGestureDetector recentListGestureDetector =
                new ScaleGestureDetector(mContext,
                        new RecentListOnScaleGestureListener(recentWarningContent, cardListView));

        // Prepare recents panel view and set the listeners
        cardListView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                recentListGestureDetector.onTouchEvent(event);
                return false;
            }
        });
        mRecentPanelView = new RecentPanelView(mContext, cardListView, mEmptyRecentView);
        mRecentPanelView.setOnExitListener(this);
        mRecentPanelView.setOnTasksLoadedListener(this);

        // Add finally the views and listen for outside touches.
        mParentView.setFocusableInTouchMode(true);
        mParentView.addView(mRecentContainer);
        mParentView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    // Touch outside the recents window....hide recents window.
                    return hideRecents();
                }
                return false;
            }
        });
        // Listen for back key events to close recents screen.
        mParentView.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK
                    && event.getAction() == KeyEvent.ACTION_UP
                    && !event.isCanceled()) {
                    // Back key was pressed....hide recents window.
                    return hideRecents();
                }
                return false;
            }
        });

    }

    /**
     * External call from theme engines to apply
     * new styles.
     */
    public void rebuildRecentsScreen() {
        // Set new backgrounds
        if (mRecentContent != null && mEmptyRecentView != null) {
            mRecentContent.setBackgroundResource(0);
            mRecentContent.setBackgroundResource(R.drawable.recent_bg_dropshadow);
            mEmptyRecentView.setImageResource(0);
            mEmptyRecentView.setImageResource(R.drawable.ic_empty_recent);
        }
        // Rebuild complete adapter and lists to force style updates.
        mRecentPanelView.buildCardListAndAdapter();
    }

    /**
     * External call. Toggle recents panel.
     */
    public void toggleRecents(Display display, int layoutDirection, View statusBarView) {
        if (DEBUG) Log.d(TAG, "toggle recents panel");

        if (!mIsAnimating) {
            if (!isShowing() && !mIsAnimating) {
                mIsToggled = true;
                if (mRecentPanelView.isTasksLoaded()) {
                    if (DEBUG) Log.d(TAG, "tasks loaded....showRecents()");
                    showRecents();
                }
            } else {
                hideRecents();
            }
        }
    }

    /**
     * External call. Preload recent tasks.
     */
    public void preloadRecentTasksList() {
        if (DEBUG) Log.d(TAG, "preloading recents");
        if (mRecentPanelView != null) {
            mRecentPanelView.setCancelledByUser(false);
            mRecentPanelView.loadTasks();
        }
    }

    /**
     * External call. Cancel preload recent tasks.
     */
    public void cancelPreloadingRecentTasksList() {
        if (DEBUG) Log.d(TAG, "cancel preloading recents");
        if (mRecentPanelView != null) {
            mRecentPanelView.setCancelledByUser(true);
        }
        hideRecents();
    }

    public void closeRecents() {
        if (DEBUG) Log.d(TAG, "closing recents panel");
        hideRecents();
    }

    /**
     * Get LayoutParams we need for the recents panel.
     *
     * @return LayoutParams
     */
    private WindowManager.LayoutParams generateLayoutParameter() {
        int width = mContext.getResources().getDimensionPixelSize(R.dimen.recent_width);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        // This title is for debugging only. See: dumpsys window
        params.setTitle("RecentControlPanel");
        params.gravity = Gravity.CENTER_VERTICAL | Gravity.RIGHT;
        return params;
    }

    private boolean isShowing() {
        return mIsShowing;
    }

    private boolean hideRecents() {
        if (isShowing() && !mIsAnimating) {
            mIsToggled = false;
            getOutAnimation().start();
            return true;
        }
        return false;
    }

    private void showRecents() {
        mIsShowing = true;
        mWindowManager.addView(mParentView, generateLayoutParameter());
        getInAnimation().start();
    }

    @Override
    public void onExit() {
        hideRecents();
    }

    @Override
    public void onTasksLoaded() {
        if (mIsToggled && !isShowing()) {
            if (DEBUG) Log.d(TAG, "onTasksLoaded....showRecents()");
            showRecents();
        }
    }

    /**
     * Prepare recents panel out animation.
     * @return animation
     */
    private AnimatorSet getOutAnimation() {
        mRecentContent.clearAnimation();
        float width = mContext.getResources().getDimensionPixelSize(R.dimen.recent_width);
        AnimatorSet animationSet = new AnimatorSet();
        animationSet.playTogether(
            // Animate out of the screen and fade out.
            ObjectAnimator.ofFloat(mRecentContent, "translationX",  0.0f, width),
            ObjectAnimator.ofFloat(mRecentContent, "alpha", 1.0f, 0.0f)
        );
        animationSet.setDuration(CONTAINER_SLIDE_IN_OUT_DURATION);
        animationSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (DEBUG) Log.d(TAG, "out animation started");
                mIsAnimating = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (DEBUG) Log.d(TAG, "out animation finished");
                mIsShowing = false;
                mIsAnimating = false;
                mWindowManager.removeView(mParentView);
            }
        });
        animationSet.setInterpolator(new DecelerateInterpolator());
        return animationSet;
    }

    /**
     * Prepare recents panel in animation.
     * @return animation
     */
    private AnimatorSet getInAnimation() {
        mRecentContent.clearAnimation();
        float width = mContext.getResources().getDimensionPixelSize(R.dimen.recent_width);
        AnimatorSet animationSet = new AnimatorSet();
        animationSet.playTogether(
            // Animate into the screen and fade in.
            ObjectAnimator.ofFloat(mRecentContent, "translationX", width, 0.0f),
            ObjectAnimator.ofFloat(mRecentContent, "alpha", 0.0f, 1.0f)
        );
        animationSet.setDuration(CONTAINER_SLIDE_IN_OUT_DURATION);
        animationSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (DEBUG) Log.d(TAG, "in animation started");
                mIsAnimating = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (DEBUG) Log.d(TAG, "in animation finished");
                mIsAnimating = false;
                mRecentPanelView.notifyDataSetChanged();
            }
        });
        animationSet.setInterpolator(new DecelerateInterpolator());
        return animationSet;
    }

    /**
     * Extended SimpleOnScaleGestureListener to take
     * care of a pinch to zoom out gesture. This class
     * takes as well care on a bunch of animations which are needed
     * to control the final action.
     */
    private class RecentListOnScaleGestureListener extends SimpleOnScaleGestureListener {

        // Constants for scaling max/min values.
        private final static float MAX_SCALING_FACTOR       = 1.0f;
        private final static float MIN_SCALING_FACTOR       = 0.5f;
        private final static float MIN_ALPHA_SCALING_FACTOR = 0.55f;

        private final static int ANIMATION_FADE_IN_DURATION  = 400;
        private final static int ANIMATION_FADE_OUT_DURATION = 300;

        private float mScalingFactor = MAX_SCALING_FACTOR;
        private boolean mActionDetected;

        // Views we need and are passed trough the constructor.
        private LinearLayout mRecentWarningContent;
        private CardListView mCardListView;

        RecentListOnScaleGestureListener(
                LinearLayout recentWarningContent, CardListView cardListView) {
            mRecentWarningContent = recentWarningContent;
            mCardListView = cardListView;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            // Get gesture scaling factor and calculate the values we need.
            mScalingFactor *= detector.getScaleFactor();
            mScalingFactor = Math.max(MIN_SCALING_FACTOR,
                    Math.min(mScalingFactor, MAX_SCALING_FACTOR));
            float alphaValue = Math.max(MIN_ALPHA_SCALING_FACTOR,
                    Math.min(mScalingFactor, MAX_SCALING_FACTOR));

            // Reset detection value.
            mActionDetected = false;

            // Set alpha value for content.
            mRecentContent.setAlpha(alphaValue);

            // Check if we are under MIN_ALPHA_SCALING_FACTOR and show
            // warning view.
            if (mScalingFactor < MIN_ALPHA_SCALING_FACTOR) {
                mActionDetected = true;
                mRecentWarningContent.setVisibility(View.VISIBLE);
            } else {
                mRecentWarningContent.setVisibility(View.GONE);
            }
            return true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            super.onScaleEnd(detector);
            // Reset to default scaling factor to prepare for next gesture.
            mScalingFactor = MAX_SCALING_FACTOR;

            final float currentAlpha = mRecentContent.getAlpha();

            // Gesture was detected and activated. Prepare and play the animations.
            if (mActionDetected) {
                mRecentPanelView.removeAllApplications();

                // Setup animation for warning content - fade out.
                ValueAnimator animation1 = ValueAnimator.ofFloat(1.0f, 0.0f);
                animation1.setDuration(ANIMATION_FADE_OUT_DURATION);
                animation1.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mRecentWarningContent.setAlpha((Float) animation.getAnimatedValue());
                    }
                });

                // Setup animation for list view - fade out.
                ValueAnimator animation2 = ValueAnimator.ofFloat(1.0f, 0.0f);
                animation2.setDuration(ANIMATION_FADE_OUT_DURATION);
                animation2.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mCardListView.setAlpha((Float) animation.getAnimatedValue());
                    }
                });

                // Setup animation for base content - fade in.
                ValueAnimator animation3 = ValueAnimator.ofFloat(currentAlpha, 1.0f);
                animation3.setDuration(ANIMATION_FADE_IN_DURATION);
                animation3.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mRecentContent.setAlpha((Float) animation.getAnimatedValue());
                    }
                });

                // Setup animation for empty recent image - fade in.
                mEmptyRecentView.setAlpha(0.0f);
                mEmptyRecentView.setVisibility(View.VISIBLE);
                ValueAnimator animation4 = ValueAnimator.ofFloat(0.0f, 1.0f);
                animation4.setDuration(ANIMATION_FADE_IN_DURATION);
                animation4.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mEmptyRecentView.setAlpha((Float) animation.getAnimatedValue());
                    }
                });

                // Start all ValueAnimator animations
                // and listen onAnimationEnd to prepare the views for the next call.
                AnimatorSet animationSet = new AnimatorSet();
                animationSet.playTogether(animation1, animation2, animation3, animation4);
                animationSet.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        // Animation is finished. Prepare warning content for next call.
                        mRecentWarningContent.setVisibility(View.GONE);
                        mRecentWarningContent.setAlpha(1.0f);
                        // Prepare listview for next recent call.
                        mCardListView.setVisibility(View.GONE);
                        mCardListView.setAlpha(1.0f);
                        // Finally hide our recents screen.
                        hideRecents();
                    }
                });
                animationSet.start();

            } else if (currentAlpha < 1.0f) {
                // No gesture action was detected. But we may have a lower alpha
                // value for the content. Animate back to full opacitiy.
                ValueAnimator animation = ValueAnimator.ofFloat(currentAlpha, 1.0f);
                animation.setDuration(100);
                animation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mRecentContent.setAlpha((Float) animation.getAnimatedValue());
                    }
                });
                animation.start();
            }
        }
   }

}

