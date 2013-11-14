/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui;

import android.app.ActivityOptions;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.internal.util.slim.ButtonConfig;
import com.android.internal.util.slim.ButtonsConstants;
import com.android.internal.util.slim.ButtonsHelper;
import com.android.internal.util.slim.DeviceUtils;
import com.android.internal.util.slim.SlimActions;
import com.android.internal.widget.multiwaveview.GlowPadView;
import com.android.internal.widget.multiwaveview.GlowPadView.OnTriggerListener;
import com.android.internal.widget.multiwaveview.TargetDrawable;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarPanel;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

public class SearchPanelView extends FrameLayout implements StatusBarPanel {

    private static final String TAG = "SearchPanelView";
    private static final String ASSIST_ICON_METADATA_NAME =
            "com.android.systemui.action_assist_icon";

    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build();

    private final Context mContext;
    private BaseStatusBar mBar;

    private SearchPanelCircleView mCircle;
    private ImageView mLogo;
    private View mScrim;

    private int mThreshold;
    private boolean mHorizontal;

    private boolean mLaunching;
    private boolean mDragging;
    private boolean mDraggedFarEnough;
    private float mStartTouch;
    private float mStartDrag;
    private boolean mLaunchPending;
    private Resources mResources;

    private ArrayList<ButtonConfig> mButtonsConfig;
    ArrayList<String> mIntentList = new ArrayList<String>();
    ArrayList<String> mLongList = new ArrayList<String>();
    private boolean mLongPress;
    private boolean mSearchPanelLock;
    private int mTarget;

    public SearchPanelView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SearchPanelView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mThreshold = context.getResources().getDimensionPixelSize(R.dimen.search_panel_threshold);
        mResources = mContext.getResources();
    }

    private class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
            }
        }
    }

    private H mHandler = new H();

    class GlowPadTriggerListener implements GlowPadView.OnTriggerListener {
        boolean mWaitingForLaunch;

       final Runnable SetLongPress = new Runnable () {
            public void run() {
                if (!mSearchPanelLock) {
                    mLongPress = true;
                    mBar.hideSearchPanel();
                    if (!SlimActions.isActionKeyEvent(mLongList.get(mTarget))) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                    SlimActions.processAction(mContext, mLongList.get(mTarget), true);
                    mSearchPanelLock = true;
                 }
            }
        };

        public void onGrabbed(View v, int handle) {
            mSearchPanelLock = false;
        }

        public void onReleased(View v, int handle) {
        }

        public void onTargetChange(View v, final int target) {
            if (target == -1) {
                mHandler.removeCallbacks(SetLongPress);
                mLongPress = false;
            } else {
                if (mLongList.get(target) == null
                    || mLongList.get(target).equals("")
                    || mLongList.get(target).equals("none")) {
                //pretend like nothing happened
                } else {
                    mTarget = target;
                    mHandler.postDelayed(SetLongPress, ViewConfiguration.getLongPressTimeout());
                }
            }
        }

        public void onGrabbedStateChange(View v, int handle) {
            if (!mWaitingForLaunch && OnTriggerListener.NO_HANDLE == handle) {
                mBar.hideSearchPanel();
                mHandler.removeCallbacks(SetLongPress);
                mLongPress = false;
            }
        }

        public void onTrigger(View v, final int target) {
            final int resId = mGlowPadView.getResourceIdForTarget(target);
            mTarget = target;
            if (!mLongPress) {
                if (!SlimActions.isActionKeyEvent(mIntentList.get(target))) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                if (!mIntentList.get(target).equals(ButtonsConstants.ACTION_MENU)) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                }
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
                SlimActions.processAction(mContext, mIntentList.get(target), false);
                mHandler.removeCallbacks(SetLongPress);
            }
        }

        public void onFinishFinalAnimation() {
        }
    }
    final GlowPadTriggerListener mGlowPadViewListener = new GlowPadTriggerListener();

    private void startAssistActivity() {
        if (!mBar.isDeviceProvisioned()) return;

        // Close Recent Apps if needed
        mBar.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_SEARCH_PANEL);

        final Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(mContext, true, UserHandle.USER_CURRENT);
        if (intent == null) return;

        try {
            final ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext,
                    R.anim.search_launch_enter, R.anim.search_launch_exit);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    mContext.startActivityAsUser(intent, opts.toBundle(),
                            new UserHandle(UserHandle.USER_CURRENT));
                }
            });
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Activity not found for " + intent.getAction());
        }
    }

    @Override
    public void onAnimationStarted() {
        postDelayed(new Runnable() {
            public void run() {
                mGlowPadViewListener.mWaitingForLaunch = false;
                mBar.hideSearchPanel();
            }
        }, SEARCH_PANEL_HOLD_DURATION);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mCircle = (SearchPanelCircleView) findViewById(R.id.search_panel_circle);
        mLogo = (ImageView) findViewById(R.id.search_logo);
        mScrim = findViewById(R.id.search_panel_scrim);
        mSearchTargetsContainer = findViewById(R.id.search_panel_container);
        // TODO: fetch views
        mGlowPadView = (GlowPadView) findViewById(R.id.glow_pad_view);
        mGlowPadView.setOnTriggerListener(mGlowPadViewListener);
        updateSettings();
    }

    private void maybeSwapSearchIcon() {
        Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(mContext, false, UserHandle.USER_CURRENT);
        if (intent != null) {
            ComponentName component = intent.getComponent();
            replaceDrawable(mLogo, component, ASSIST_ICON_METADATA_NAME);
        } else {
            mLogo.setImageDrawable(null);
        }
    }

    public void replaceDrawable(ImageView v, ComponentName component, String name) {
        if (component != null) {
            try {
                PackageManager packageManager = mContext.getPackageManager();
                // Look for the search icon specified in the activity meta-data
                Bundle metaData = packageManager.getActivityInfo(
                        component, PackageManager.GET_META_DATA).metaData;
                if (metaData != null) {
                    int iconResId = metaData.getInt(name);
                    if (iconResId != 0) {
                        Resources res = packageManager.getResourcesForActivity(component);
                        v.setImageDrawable(res.getDrawable(iconResId));
                        return;
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Failed to swap drawable; "
                        + component.flattenToShortString() + " not found", e);
            } catch (Resources.NotFoundException nfe) {
                Log.w(TAG, "Failed to swap drawable from "
                        + component.flattenToShortString(), nfe);
            }
        }
        v.setImageDrawable(null);
    }

    @Override
    public boolean isInContentArea(int x, int y) {
        return true;
    }

    private void vibrate() {
        Context context = getContext();
        if (Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1, UserHandle.USER_CURRENT) != 0) {
            Resources res = context.getResources();
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            vibrator.vibrate(res.getInteger(R.integer.config_search_panel_view_vibration_duration),
                    VIBRATION_ATTRIBUTES);
        }
    }

    public void show(final boolean show, boolean animate) {
        if (show) {
            maybeSwapSearchIcon();
            if (getVisibility() != View.VISIBLE) {
                setVisibility(View.VISIBLE);
                vibrate();
                if (animate) {
                    startEnterAnimation();
                } else {
                    mScrim.setAlpha(1f);
                }
            }
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();
        } else {
            if (animate) {
                startAbortAnimation();
            } else {
                setVisibility(View.INVISIBLE);
            }
        }
    }

    private void startEnterAnimation() {
        mCircle.startEnterAnimation();
        mScrim.setAlpha(0f);
        mScrim.animate()
                .alpha(1f)
                .setDuration(300)
                .setStartDelay(50)
                .setInterpolator(PhoneStatusBar.ALPHA_IN)
                .start();

    }

    private void startAbortAnimation() {
        mCircle.startAbortAnimation(new Runnable() {
                    @Override
                    public void run() {
                        mCircle.setAnimatingOut(false);
                        setVisibility(View.INVISIBLE);
                    }
                });
        mCircle.setAnimatingOut(true);
        mScrim.animate()
                .alpha(0f)
                .setDuration(300)
                .setStartDelay(0)
                .setInterpolator(PhoneStatusBar.ALPHA_OUT);
    }

    public void hide(boolean animate) {
        if (mBar != null) {
            // This will indirectly cause show(false, ...) to get called
            mBar.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
        } else {
            if (animate) {
                startAbortAnimation();
            } else {
                setVisibility(View.INVISIBLE);
            }
        }
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        // Ignore hover events outside of this panel bounds since such events
        // generate spurious accessibility events with the panel content when
        // tapping outside of it, thus confusing the user.
        final int x = (int) event.getX();
        final int y = (int) event.getY();
        if (x >= 0 && x < getWidth() && y >= 0 && y < getHeight()) {
            return super.dispatchHoverEvent(event);
        }
        return true;
    }

    /**
     * Whether the panel is showing, or, if it's animating, whether it will be
     * when the animation is done.
     */
    public boolean isShowing() {
        return getVisibility() == View.VISIBLE && !mCircle.isAnimatingOut();
    }

    public void setBar(BaseStatusBar bar) {
        mBar = bar;
    }

    public boolean isAssistantAvailable() {
        return ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(mContext, false, UserHandle.USER_CURRENT) != null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mLaunching || mLaunchPending) {
            return false;
        }
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mStartTouch = mHorizontal ? event.getX() : event.getY();
                mDragging = false;
                mDraggedFarEnough = false;
                mCircle.reset();
                break;
            case MotionEvent.ACTION_MOVE:
                float currentTouch = mHorizontal ? event.getX() : event.getY();
                if (getVisibility() == View.VISIBLE && !mDragging &&
                        (!mCircle.isAnimationRunning(true /* enterAnimation */)
                                || Math.abs(mStartTouch - currentTouch) > mThreshold)) {
                    mStartDrag = currentTouch;
                    mDragging = true;
                }
                if (mDragging) {
                    float offset = Math.max(mStartDrag - currentTouch, 0.0f);
                    mCircle.setDragDistance(offset);
                    mDraggedFarEnough = Math.abs(mStartTouch - currentTouch) > mThreshold;
                    mCircle.setDraggedFarEnough(mDraggedFarEnough);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDraggedFarEnough) {
                    if (mCircle.isAnimationRunning(true  /* enterAnimation */)) {
                        mLaunchPending = true;
                        mCircle.setAnimatingOut(true);
                        mCircle.performOnAnimationFinished(new Runnable() {
                            @Override
                            public void run() {
                                startExitAnimation();
                            }
                        });
                    } else {
                        startExitAnimation();
                    }
                } else {
                    startAbortAnimation();
                }
                break;
        }
        return true;
    }

    private void startExitAnimation() {
        mLaunchPending = false;
        if (mLaunching || getVisibility() != View.VISIBLE) {
            return;
        }
        mLaunching = true;
        startAssistActivity();
        vibrate();
        mCircle.setAnimatingOut(true);
        mCircle.startExitAnimation(new Runnable() {
                    @Override
                    public void run() {
                        mLaunching = false;
                        mCircle.setAnimatingOut(false);
                        setVisibility(View.INVISIBLE);
                    }
                });
        mScrim.animate()
                .alpha(0f)
                .setDuration(300)
                .setStartDelay(0)
                .setInterpolator(PhoneStatusBar.ALPHA_OUT);
    }

    public void setHorizontal(boolean horizontal) {
        mHorizontal = horizontal;
        mCircle.setHorizontal(horizontal);
    }

    public void updateSettings() {
        mButtonsConfig = ButtonsHelper.getNavRingConfig(mContext);
        setDrawables();
    }

    private boolean isScreenPortrait() {
        return mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    private void setDrawables() {
        mLongPress = false;
        mSearchPanelLock = false;

        // Custom Targets
        ArrayList<TargetDrawable> storedDraw = new ArrayList<TargetDrawable>();

        int endPosOffset;
        int startPosOffset;
        int middleBlanks = 0;

        if (isScreenPortrait()
            || Settings.System.getIntForUser(mContext.getContentResolver(),
                   Settings.System.NAVIGATION_BAR_CAN_MOVE,
                   DeviceUtils.isPhone(mContext) ? 1 : 0, UserHandle.USER_CURRENT) != 1) {
            startPosOffset = 1;
            endPosOffset = (mButtonsConfig.size()) + 1;
        } else {
            //lastly the standard landscape with navbar on right
            startPosOffset = (Math.min(1, mButtonsConfig.size() / 2)) + 2;
            endPosOffset = startPosOffset - 1;
        }

        mIntentList.clear();
        mLongList.clear();

        int middleStart = mButtonsConfig.size();
        int tqty = middleStart;
        int middleFinish = 0;
        ButtonConfig buttonConfig;

        if (middleBlanks > 0) {
            middleStart = (tqty/2) + (tqty%2);
            middleFinish = (tqty/2);
        }

        // Add Initial Place Holder Targets
        for (int i = 0; i < startPosOffset; i++) {
            storedDraw.add(getTargetDrawable("", null));
            mIntentList.add(ButtonsConstants.ACTION_NULL);
            mLongList.add(ButtonsConstants.ACTION_NULL);
        }

        // Add User Targets
        for (int i = middleStart - 1; i >= 0; i--) {
            buttonConfig = mButtonsConfig.get(i);
            mIntentList.add(buttonConfig.getClickAction());
            mLongList.add(buttonConfig.getLongpressAction());
            storedDraw.add(getTargetDrawable(buttonConfig.getClickAction(), buttonConfig.getIcon()));
        }

        // Add middle Place Holder Targets
        for (int j = 0; j < middleBlanks; j++) {
            storedDraw.add(getTargetDrawable("", null));
            mIntentList.add(ButtonsConstants.ACTION_NULL);
            mLongList.add(ButtonsConstants.ACTION_NULL);
        }

        // Add End Place Holder Targets
        for (int i = 0; i < endPosOffset; i++) {
            storedDraw.add(getTargetDrawable("", null));
            mIntentList.add(ButtonsConstants.ACTION_NULL);
            mLongList.add(ButtonsConstants.ACTION_NULL);
        }

        mGlowPadView.setTargetResources(storedDraw);
    }

    private TargetDrawable getTargetDrawable(String action, String customIconUri) {
        TargetDrawable noneDrawable = new TargetDrawable(
            mResources, mResources.getDrawable(R.drawable.ic_action_none));

        if (customIconUri != null && !customIconUri.equals(ButtonsConstants.ICON_EMPTY)
                || customIconUri != null
                && customIconUri.startsWith(ButtonsConstants.SYSTEM_ICON_IDENTIFIER)) {
            // it's an icon the user chose from the gallery here
            // or a custom system icon
            File iconFile = new File(Uri.parse(customIconUri).getPath());
                try {
                    Drawable customIcon;
                    if (iconFile.exists()) {
                        customIcon = resize(
                            new BitmapDrawable(getResources(), iconFile.getAbsolutePath()));
                    } else {
                        customIcon = resize(mResources.getDrawable(mResources.getIdentifier(
                                    customIconUri.substring(
                                    ButtonsConstants.SYSTEM_ICON_IDENTIFIER.length()),
                                    "drawable", "android")));
                    }
                    return new TargetDrawable(mResources, setStateListDrawable(customIcon));
                } catch (Exception e) {
                    return noneDrawable;
                }
        }

        if (action.equals("")) {
            TargetDrawable blankDrawable = new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_navbar_placeholder));
            blankDrawable.setEnabled(false);
            return blankDrawable;
        }
        if (action == null || action.equals(ButtonsConstants.ACTION_NULL))
            return noneDrawable;
        if (action.equals(ButtonsConstants.ACTION_SCREENSHOT))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_screenshot));
        if (action.equals(ButtonsConstants.ACTION_IME))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_ime_switcher));
        if (action.equals(ButtonsConstants.ACTION_VIB))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_vib));
        if (action.equals(ButtonsConstants.ACTION_SILENT))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_silent));
        if (action.equals(ButtonsConstants.ACTION_VIB_SILENT))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_ring_vib_silent));
        if (action.equals(ButtonsConstants.ACTION_KILL))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_killtask));
        if (action.equals(ButtonsConstants.ACTION_LAST_APP))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_lastapp));
        if (action.equals(ButtonsConstants.ACTION_POWER))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_power));
        if (action.equals(ButtonsConstants.ACTION_POWER_MENU))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_power_menu));
        if (action.equals(ButtonsConstants.ACTION_QS))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_qs));
        if (action.equals(ButtonsConstants.ACTION_NOTIFICATIONS))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_notifications));
        if (action.equals(ButtonsConstants.ACTION_TORCH))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_torch));
        if (action.equals(ButtonsConstants.ACTION_ASSIST))
            return new TargetDrawable(
                mResources, com.android.internal.R.drawable.ic_action_assist_generic);
        try {
            Intent in = Intent.parseUri(action, 0);
            PackageManager pm = mContext.getPackageManager();
            ActivityInfo aInfo = in.resolveActivityInfo(pm, PackageManager.GET_ACTIVITIES);
            return new TargetDrawable(mResources,
                setStateListDrawable(resize(aInfo.loadIcon(pm))));
        } catch (Exception e) {
            return noneDrawable;
        }
    }

    private StateListDrawable setStateListDrawable(Drawable activityIcon) {
        if (activityIcon == null) {
            return null;
        }
        Drawable iconBg = resize(mResources.getDrawable(R.drawable.ic_navbar_blank));
        Drawable iconBgActivated =
            resize(mResources.getDrawable(R.drawable.ic_navbar_blank_activated));
        int margin = (int)(iconBg.getIntrinsicHeight() / 3);
        LayerDrawable icon = new LayerDrawable (new Drawable[] {iconBg, activityIcon});
        icon.setLayerInset(1, margin, margin, margin, margin);
        LayerDrawable iconActivated =
            new LayerDrawable (new Drawable[] {iconBgActivated, activityIcon});
        iconActivated.setLayerInset(1, margin, margin, margin, margin);
        StateListDrawable selector = new StateListDrawable();
        selector.addState(new int[] {android.R.attr.state_enabled,
            -android.R.attr.state_active, -android.R.attr.state_focused}, icon);
        selector.addState(new int[] {android.R.attr.state_enabled,
            android.R.attr.state_active, -android.R.attr.state_focused}, iconActivated);
        selector.addState(new int[] {android.R.attr.state_enabled,
            -android.R.attr.state_active, android.R.attr.state_focused}, iconActivated);
        return selector;
    }

    private Drawable resize(Drawable image) {
        int size = 50;
        int px = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, size, getResources()
                .getDisplayMetrics());

        Bitmap d = ((BitmapDrawable) image).getBitmap();
        Bitmap bitmapOrig = Bitmap.createScaledBitmap(d, px, px, false);
        return new BitmapDrawable(mContext.getResources(), bitmapOrig);
    }

}
