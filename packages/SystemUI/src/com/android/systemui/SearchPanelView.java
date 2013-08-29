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

import android.animation.LayoutTransition;
import android.app.ActivityOptions;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.EventLog;
import android.util.Slog;
import android.util.Log;
import android.util.TypedValue;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;

import com.android.systemui.EventLogTags;
import com.android.systemui.R;
import com.android.systemui.recent.StatusBarTouchProxy;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.tablet.StatusBarPanel;
import com.android.systemui.statusbar.tablet.TabletStatusBar;
import com.android.internal.util.slim.ButtonConfig;
import com.android.internal.util.slim.ButtonsHelper;
import com.android.internal.util.slim.ButtonsConstants;
import com.android.internal.util.slim.SlimActions;
import com.android.internal.widget.multiwaveview.GlowPadView;
import com.android.internal.widget.multiwaveview.GlowPadView.OnTriggerListener;
import com.android.internal.widget.multiwaveview.TargetDrawable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SearchPanelView extends FrameLayout implements
        StatusBarPanel, ActivityOptions.OnAnimationStartedListener {
    private static final int SEARCH_PANEL_HOLD_DURATION = 0;
    static final String TAG = "SearchPanelView";
    static final boolean DEBUG = TabletStatusBar.DEBUG || PhoneStatusBar.DEBUG || false;
    public static final boolean DEBUG_GESTURES = true;
    private static final String ASSIST_ICON_METADATA_NAME =
            "com.android.systemui.action_assist_icon";
    private final Context mContext;
    private BaseStatusBar mBar;
    private StatusBarTouchProxy mStatusBarTouchProxy;
    private SettingsObserver mObserver;

    private boolean mShowing;
    private View mSearchTargetsContainer;
    private GlowPadView mGlowPadView;
    private IWindowManager mWm;

    private PackageManager mPackageManager;
    private Resources mResources;
    private ContentResolver mContentResolver;
    private boolean mAttached = false;
    private int startPosOffset;
    private ArrayList<ButtonConfig> mButtonsConfig;
    private boolean mLongPress;
    private boolean mSearchPanelLock;
    private int mTarget;

    //need to make an intent list and an intent counter
    String[] intent;
    ArrayList<String> intentList = new ArrayList<String>();
    ArrayList<String> longList = new ArrayList<String>();
    String mEmpty = "**none**";

    public SearchPanelView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SearchPanelView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mWm = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
        mPackageManager = mContext.getPackageManager();
        mResources = mContext.getResources();

        mContentResolver = mContext.getContentResolver();
        mObserver = new SettingsObserver(new Handler());
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
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                    SlimActions.processAction(mContext, longList.get(mTarget));
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
                if (longList.get(target) == null || longList.get(target).equals("") || longList.get(target).equals("none")) {
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
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
                SlimActions.processAction(mContext, intentList.get(target));
                mHandler.removeCallbacks(SetLongPress);
            }
        }

        public void onFinishFinalAnimation() {
        }
    }
    final GlowPadTriggerListener mGlowPadViewListener = new GlowPadTriggerListener();

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
        mSearchTargetsContainer = findViewById(R.id.search_panel_container);
        mStatusBarTouchProxy = (StatusBarTouchProxy) findViewById(R.id.status_bar_touch_proxy);
        // TODO: fetch views
        mGlowPadView = (GlowPadView) findViewById(R.id.glow_pad_view);
        mGlowPadView.setOnTriggerListener(mGlowPadViewListener);
    }

    private void setDrawables() {
        mLongPress = false;
        mSearchPanelLock = false;

        // Custom Targets
        ArrayList<TargetDrawable> storedDraw = new ArrayList<TargetDrawable>();

        int endPosOffset;
        int middleBlanks = 0;

        boolean navbarCanMove = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.NAVIGATION_BAR_CAN_MOVE, 1, UserHandle.USER_CURRENT) == 1;
        boolean screenSizeTablet = screenLayout() == Configuration.SCREENLAYOUT_SIZE_LARGE
                                || screenLayout() == Configuration.SCREENLAYOUT_SIZE_XLARGE;

        if (screenSizeTablet || isScreenPortrait()
               || (!screenSizeTablet && !isScreenPortrait() && !navbarCanMove)) {
            startPosOffset = 1;
            endPosOffset = (mButtonsConfig.size()) + 1;
        } else {
            //lastly the standard landscape with navbar on right
            startPosOffset = (Math.min(1, mButtonsConfig.size() / 2)) + 2;
            endPosOffset = startPosOffset - 1;
        }

        intentList.clear();
        longList.clear();

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
            intentList.add(mEmpty);
            longList.add(mEmpty);
        }

        // Add User Targets
        for (int i = middleStart - 1; i >= 0; i--) {
            buttonConfig = mButtonsConfig.get(i);
            intentList.add(buttonConfig.getClickAction());
            longList.add(buttonConfig.getLongpressAction());
            storedDraw.add(getTargetDrawable(buttonConfig.getClickAction(), buttonConfig.getIcon()));
        }

        // Add middle Place Holder Targets
        for (int j = 0; j < middleBlanks; j++) {
            storedDraw.add(getTargetDrawable("", null));
            intentList.add(mEmpty);
            longList.add(mEmpty);
        }

        // Add End Place Holder Targets
        for (int i = 0; i < endPosOffset; i++) {
            storedDraw.add(getTargetDrawable("", null));
            intentList.add(mEmpty);
            longList.add(mEmpty);
        }

        mGlowPadView.setTargetResources(storedDraw);
    }

    private TargetDrawable getTargetDrawable(String action, String customIconUri) {
        TargetDrawable noneDrawable = new TargetDrawable(mResources, mResources.getDrawable(R.drawable.ic_action_none));

        if (customIconUri != null && !customIconUri.equals(ButtonsConstants.ICON_EMPTY)
                || customIconUri != null && customIconUri.startsWith(ButtonsConstants.SYSTEM_ICON_IDENTIFIER)) {
            // it's an icon the user chose from the gallery here
            // or a custom system icon
            File iconFile = new File(Uri.parse(customIconUri).getPath());
                try {
                    Drawable customIcon;
                    if (iconFile.exists()) {
                        customIcon = resize(new BitmapDrawable(getResources(), iconFile.getAbsolutePath()));
                    } else {
                        customIcon = resize(mResources.getDrawable(mResources.getIdentifier(
                                    customIconUri.substring(ButtonsConstants.SYSTEM_ICON_IDENTIFIER.length()),
                                    "drawable", "android")));
                    }
                    Drawable iconBg = resize(mResources.getDrawable(R.drawable.ic_navbar_blank));
                    Drawable iconBgActivated = resize(mResources.getDrawable(R.drawable.ic_navbar_blank_activated));
                    int margin = (int)(iconBg.getIntrinsicHeight() / 3);
                    LayerDrawable icon = new LayerDrawable (new Drawable[] {iconBg, customIcon});
                    icon.setLayerInset(1, margin, margin, margin, margin);
                    LayerDrawable iconActivated = new LayerDrawable (new Drawable[] {iconBgActivated, customIcon});
                    iconActivated.setLayerInset(1, margin, margin, margin, margin);
                    StateListDrawable selector = new StateListDrawable();
                    selector.addState(new int[] {android.R.attr.state_enabled, -android.R.attr.state_active, -android.R.attr.state_focused}, icon);
                    selector.addState(new int[] {android.R.attr.state_enabled, android.R.attr.state_active, -android.R.attr.state_focused}, iconActivated);
                    selector.addState(new int[] {android.R.attr.state_enabled, -android.R.attr.state_active, android.R.attr.state_focused}, iconActivated);
                    return new TargetDrawable(mResources, selector);
                } catch (Exception e) {
                    return noneDrawable;
                }
        }

        if (action.equals("")) {
            TargetDrawable blankDrawable = new TargetDrawable(mResources, mResources.getDrawable(R.drawable.ic_navbar_blank));
            blankDrawable.setEnabled(false);
            return blankDrawable;
        }
        if (action == null || action.equals(ButtonsConstants.ACTION_NULL))
            return noneDrawable;
        if (action.equals(ButtonsConstants.ACTION_SCREENSHOT))
            return new TargetDrawable(mResources, mResources.getDrawable(R.drawable.ic_action_screenshot));
        if (action.equals(ButtonsConstants.ACTION_IME))
            return new TargetDrawable(mResources, mResources.getDrawable(R.drawable.ic_action_ime_switcher));
        if (action.equals(ButtonsConstants.ACTION_VIB))
            return new TargetDrawable(mResources, mResources.getDrawable(R.drawable.ic_action_vib));
        if (action.equals(ButtonsConstants.ACTION_SILENT))
            return new TargetDrawable(mResources, mResources.getDrawable(R.drawable.ic_action_silent));
        if (action.equals(ButtonsConstants.ACTION_VIB_SILENT))
            return new TargetDrawable(mResources, mResources.getDrawable(R.drawable.ic_action_ring_vib_silent));
        if (action.equals(ButtonsConstants.ACTION_KILL))
            return new TargetDrawable(mResources, mResources.getDrawable(R.drawable.ic_action_killtask));
        if (action.equals(ButtonsConstants.ACTION_WIDGETS))
            return new TargetDrawable(mResources, mResources.getDrawable(R.drawable.ic_action_widgets));
        if (action.equals(ButtonsConstants.ACTION_LAST_APP))
            return new TargetDrawable(mResources, mResources.getDrawable(R.drawable.ic_action_lastapp));
        if (action.equals(ButtonsConstants.ACTION_POWER))
            return new TargetDrawable(mResources, mResources.getDrawable(R.drawable.ic_action_power));
        if (action.equals(ButtonsConstants.ACTION_QS))
            return new TargetDrawable(mResources, mResources.getDrawable(R.drawable.ic_action_qs));
        if (action.equals(ButtonsConstants.ACTION_NOTIFICATIONS))
            return new TargetDrawable(mResources, mResources.getDrawable(R.drawable.ic_action_notifications));
        if (action.equals(ButtonsConstants.ACTION_EXPANDED_DESKTOP))
            return new TargetDrawable(mResources, mResources.getDrawable(R.drawable.ic_action_expanded_desktop));
        if (action.equals(ButtonsConstants.ACTION_ASSIST))
            return new TargetDrawable(mResources, com.android.internal.R.drawable.ic_action_assist_generic);
        try {
            Intent in = Intent.parseUri(action, 0);
            ActivityInfo aInfo = in.resolveActivityInfo(mPackageManager, PackageManager.GET_ACTIVITIES);
            Drawable activityIcon = resize(aInfo.loadIcon(mPackageManager));
            Drawable iconBg = resize(mResources.getDrawable(R.drawable.ic_navbar_blank));
            Drawable iconBgActivated = resize(mResources.getDrawable(R.drawable.ic_navbar_blank_activated));
            int margin = (int)(iconBg.getIntrinsicHeight() / 3);
            LayerDrawable icon = new LayerDrawable (new Drawable[] {iconBg, activityIcon});
            icon.setLayerInset(1, margin, margin, margin, margin);
            LayerDrawable iconActivated = new LayerDrawable (new Drawable[] {iconBgActivated, activityIcon});
            iconActivated.setLayerInset(1, margin, margin, margin, margin);
            StateListDrawable selector = new StateListDrawable();
            selector.addState(new int[] {android.R.attr.state_enabled, -android.R.attr.state_active, -android.R.attr.state_focused}, icon);
            selector.addState(new int[] {android.R.attr.state_enabled, android.R.attr.state_active, -android.R.attr.state_focused}, iconActivated);
            selector.addState(new int[] {android.R.attr.state_enabled, -android.R.attr.state_active, android.R.attr.state_focused}, iconActivated);
            return new TargetDrawable(mResources, selector);
        } catch (Exception e) {
            return noneDrawable;
        }
    }

    private Drawable resize(Drawable image) {
        int size = 50;
        int px = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, size, getResources()
                .getDisplayMetrics());

        Bitmap d = ((BitmapDrawable) image).getBitmap();
        Bitmap bitmapOrig = Bitmap.createScaledBitmap(d, px, px, false);
        return new BitmapDrawable(mContext.getResources(), bitmapOrig);
    }

    private void maybeSwapSearchIcon() {
        Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(mContext, false, UserHandle.USER_CURRENT);
        if (intent != null) {
            ComponentName component = intent.getComponent();
            if (component == null || !mGlowPadView.replaceTargetDrawablesIfPresent(component,
                    ASSIST_ICON_METADATA_NAME,
                    com.android.internal.R.drawable.ic_action_assist_generic)) {
                if (DEBUG) Slog.v(TAG, "Couldn't grab icon for component " + component);
            }
        }
    }

    private boolean pointInside(int x, int y, View v) {
        final int l = v.getLeft();
        final int r = v.getRight();
        final int t = v.getTop();
        final int b = v.getBottom();
        return x >= l && x < r && y >= t && y < b;
    }

    public boolean isInContentArea(int x, int y) {
        if (pointInside(x, y, mSearchTargetsContainer)) {
            return true;
        } else if (mStatusBarTouchProxy != null &&
                pointInside(x, y, mStatusBarTouchProxy)) {
            return true;
        } else {
            return false;
        }
    }

    private final OnPreDrawListener mPreDrawListener = new ViewTreeObserver.OnPreDrawListener() {
        public boolean onPreDraw() {
            getViewTreeObserver().removeOnPreDrawListener(this);
            mGlowPadView.resumeAnimations();
            return false;
        }
    };

    private void vibrate() {
        Context context = getContext();
        if (Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1, UserHandle.USER_CURRENT) != 0) {
            Resources res = context.getResources();
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            vibrator.vibrate(res.getInteger(R.integer.config_search_panel_view_vibration_duration));
        }
    }

    public void show(final boolean show, boolean animate) {
        if (!show) {
            final LayoutTransition transitioner = animate ? createLayoutTransitioner() : null;
            ((ViewGroup) mSearchTargetsContainer).setLayoutTransition(transitioner);
        }
        mShowing = show;
        if (show) {
            maybeSwapSearchIcon();
            if (getVisibility() != View.VISIBLE) {
                setVisibility(View.VISIBLE);
                // Don't start the animation until we've created the layer, which is done
                // right before we are drawn
                mGlowPadView.suspendAnimations();
                mGlowPadView.ping();
                getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
                vibrate();
            }
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();
        } else {
            setVisibility(View.INVISIBLE);
        }
    }

    public void hide(boolean animate) {
        if (mBar != null) {
            // This will indirectly cause show(false, ...) to get called
            mBar.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
        } else {
            setVisibility(View.INVISIBLE);
        }
    }

    /**
     * We need to be aligned at the bottom.  LinearLayout can't do this, so instead,
     * let LinearLayout do all the hard work, and then shift everything down to the bottom.
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // setPanelHeight(mSearchTargetsContainer.getHeight());
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

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mAttached) {
            mAttached = true;
            // add intent actions to listen on it
            // apps available to check if apps on external sdcard
            // are available and reconstruct the button icons
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
            filter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
            mContext.registerReceiver(mBroadcastReceiver, filter);

            // start observing settings
            mObserver.observe();
            updateSettings();
            setDrawables();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mAttached = false;
            // unregister receiver and observer
            mContext.unregisterReceiver(mBroadcastReceiver);
            mObserver.unobserve();
        }
    }

    /**
     * Whether the panel is showing, or, if it's animating, whether it will be
     * when the animation is done.
     */
    public boolean isShowing() {
        return mShowing;
    }

    public void setBar(BaseStatusBar bar) {
        mBar = bar;
    }

    public void setStatusBarView(final View statusBarView) {
        if (mStatusBarTouchProxy != null) {
            mStatusBarTouchProxy.setStatusBar(statusBarView);
//            mGlowPadView.setOnTouchListener(new OnTouchListener() {
//                public boolean onTouch(View v, MotionEvent event) {
//                    return statusBarView.onTouchEvent(event);
//                }
//            });
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (DEBUG_GESTURES) {
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                EventLog.writeEvent(EventLogTags.SYSUI_SEARCHPANEL_TOUCH,
                        event.getActionMasked(), (int) event.getX(), (int) event.getY());
            }
        }
        return super.onTouchEvent(event);
    }

    private LayoutTransition createLayoutTransitioner() {
        LayoutTransition transitioner = new LayoutTransition();
        transitioner.setDuration(200);
        transitioner.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0);
        transitioner.setAnimator(LayoutTransition.DISAPPEARING, null);
        return transitioner;
    }

    public int screenLayout() {
        final int screenSize = Resources.getSystem().getConfiguration().screenLayout &
                Configuration.SCREENLAYOUT_SIZE_MASK;
        return screenSize;
    }

    public boolean isScreenPortrait() {
        return mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)
                        || Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)) {
                setDrawables();
            }
        }
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SYSTEMUI_NAVRING_CONFIG), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NAVIGATION_BAR_CAN_MOVE), false, this,
                    UserHandle.USER_ALL);
        }

        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
            setDrawables();
        }
    }

    public void updateSettings() {
        mButtonsConfig = ButtonsHelper.getNavRingConfig(mContext);
    }

}
