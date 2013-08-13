/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.service.notification.StatusBarNotification;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.CustomTheme;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.service.notification.StatusBarNotification;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewPropertyAnimator;
import android.view.ViewStub;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.slim.ButtonConfig;
import com.android.internal.util.slim.ButtonsConstants;
import com.android.internal.util.slim.ButtonsHelper;
import com.android.systemui.EventLogTags;
import com.android.systemui.R;
import com.android.systemui.statusbar.AppSidebar;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.NotificationData.Entry;
import com.android.systemui.statusbar.SignalClusterView;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.phone.ShortcutsWidget;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.DateView;
import com.android.systemui.statusbar.policy.IntruderAlertView;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NotificationRowLayout;
import com.android.systemui.statusbar.policy.OnSizeChangedListener;
import com.android.systemui.statusbar.policy.Prefs;
import com.android.systemui.statusbar.powerwidget.PowerWidget;

public class PhoneStatusBar extends BaseStatusBar {
    static final String TAG = "PhoneStatusBar";
    public static final boolean DEBUG = BaseStatusBar.DEBUG;
    public static final boolean SPEW = DEBUG;
    public static final boolean DEBUG_GESTURES = false;
    public static final boolean DUMPTRUCK = true; // extra dumpsys info

    public static final boolean DEBUG_CLINGS = false;

    public static final boolean ENABLE_NOTIFICATION_PANEL_CLING = false;

    public static final boolean SETTINGS_DRAG_SHORTCUT = true;

    // additional instrumentation for testing purposes; intended to be left on during development
    public static final boolean CHATTY = DEBUG;

    public static final String ACTION_STATUSBAR_START
            = "com.android.internal.policy.statusbar.START";

    private static final int MSG_OPEN_NOTIFICATION_PANEL = 1000;
    private static final int MSG_CLOSE_PANELS = 1001;
    private static final int MSG_OPEN_SETTINGS_PANEL = 1002;
    private static final int MSG_STATUSBAR_BRIGHTNESS = 1003;
    private static final int MSG_OPEN_QS_PANEL = 1004;
    private static final int MSG_FLIP_TO_NOTIFICATION_PANEL = 1005;
    private static final int MSG_FLIP_TO_QS_PANEL = 1006;
    // 1020-1030 reserved for BaseStatusBar

    // will likely move to a resource or other tunable param at some point
    private static final int INTRUDER_ALERT_DECAY_MS = 0; // disabled, was 10000;

    private static final int NOTIFICATION_PRIORITY_MULTIPLIER = 10; // see NotificationManagerService
    private static final int HIDE_ICONS_BELOW_SCORE = Notification.PRIORITY_LOW * NOTIFICATION_PRIORITY_MULTIPLIER;

    private static final float BRIGHTNESS_CONTROL_PADDING = 0.15f;
    private static final int BRIGHTNESS_CONTROL_LONG_PRESS_TIMEOUT = 750; // ms
    private static final int BRIGHTNESS_CONTROL_LINGER_THRESHOLD = 20;

    // fling gesture tuning parameters, scaled to display density
    private float mSelfExpandVelocityPx; // classic value: 2000px/s
    private float mSelfCollapseVelocityPx; // classic value: 2000px/s (will be negated to collapse "up")
    private float mFlingExpandMinVelocityPx; // classic value: 200px/s
    private float mFlingCollapseMinVelocityPx; // classic value: 200px/s
    private float mCollapseMinDisplayFraction; // classic value: 0.08 (25px/min(320px,480px) on G1)
    private float mExpandMinDisplayFraction; // classic value: 0.5 (drag open halfway to expand)
    private float mFlingGestureMaxXVelocityPx; // classic value: 150px/s

    private float mExpandAccelPx; // classic value: 2000px/s/s
    private float mCollapseAccelPx; // classic value: 2000px/s/s (will be negated to collapse "up")

    private float mFlingGestureMaxOutputVelocityPx; // how fast can it really go? (should be a little
                                                    // faster than mSelfCollapseVelocityPx)

    private final String NOTIF_WALLPAPER_IMAGE_PATH = "/data/data/com.android.settings/files/notification_wallpaper.jpg";

    PhoneStatusBarPolicy mIconPolicy;

    // These are no longer handled by the policy, because we need custom strategies for them
    BluetoothController mBluetoothController;
    BatteryController mBatteryController;
    LocationController mLocationController;
    NetworkController mNetworkController;

    int mNaturalBarHeight = -1;
    int mIconSize = -1;
    int mIconHPadding = -1;
    Display mDisplay;
    Point mCurrentDisplaySize = new Point();
    int mCurrUiInvertedMode;

    IDreamManager mDreamManager;

    StatusBarWindowView mStatusBarWindow;
    PhoneStatusBarView mStatusBarView;

    int mPixelFormat;
    Object mQueueLock = new Object();

    // viewgroup containing the normal contents of the statusbar
    LinearLayout mStatusBarContents;

    // right-hand icons
    LinearLayout mSystemIconArea;

    // left-hand icons
    LinearLayout mStatusIcons;
    LinearLayout mCenterClockLayout;
    // the icons themselves
    IconMerger mNotificationIcons;
    // [+>
    View mMoreIcon;

    // expanded notifications
    NotificationPanelView mNotificationPanel; // the sliding/resizing panel within the notification window
    ScrollView mScrollView;
    View mExpandedContents;
    int mNotificationPanelGravity;
    int mNotificationPanelMarginBottomPx, mNotificationPanelMarginPx;
    float mNotificationPanelMinHeightFrac;
    boolean mNotificationPanelIsFullScreenWidth;
    TextView mNotificationPanelDebugText;
    private int mNotificationsSizeOldState = 0;

    // settings
    QuickSettingsController mQS;
    boolean mHasSettingsPanel, mHideSettingsPanel, mHasFlipSettings;
    SettingsPanelView mSettingsPanel;
    View mFlipSettingsView;
    QuickSettingsContainerView mSettingsContainer;
    int mSettingsPanelGravity;
    private TilesChangedObserver mTilesChangedObserver;

    // top bar
    View mNotificationPanelHeader;
    View mDateTimeView;
    View mClearButton;
    ImageView mSettingsButton, mQuickSettingsButton, mNotificationButton;

    // carrier/wifi label
    private TextView mCarrierLabel;
    private TextView mWifiLabel;
    private View mWifiView;
    private View mCarrierAndWifiView;
    private boolean mCarrierAndWifiViewVisible = false;
    private int mCarrierAndWifiViewHeight;
    private TextView mEmergencyCallLabel;
    private int mNotificationHeaderHeight;

    private boolean mShowCarrierInPanel = false;

    // clock
    private boolean mShowClock;

    // drag bar
    CloseDragHandle mCloseView;
    private int mCloseViewHeight;

    // position
    int[] mPositionTmp = new int[2];
    boolean mExpandedVisible;
    private boolean mNotificationPanelIsOpen = false;
    private boolean mQSPanelIsOpen = false;

    // the date view
    DateView mDateView;

    // for immersive activities
    private IntruderAlertView mIntruderAlertView;

    // on-screen navigation buttons
    private NavigationBarView mNavigationBarView = null;

    // the tracker view
    int mTrackingPosition; // the position of the top of the tracking view.

    // the power widget
    PowerWidget mPowerWidget;

    // Notification Shortcuts
    ShortcutsWidget mNotificationShortcutsLayout;
    HorizontalScrollView mNotificationShortcutsScrollView;
    private boolean mNotificationShortcutsVisible;
    private boolean mNotificationShortcutsIsActive;
    private boolean mNotificationHideCarrier;
    FrameLayout.LayoutParams lpScrollView;
    FrameLayout.LayoutParams lpCarrierLabel;
    int mShortcutsDrawerMargin;
    int mShortcutsSpacingHeight;

    // ticker
    private Ticker mTicker;
    private View mTickerView;
    private boolean mTicking;

    // Tracking finger for opening/closing.
    int mEdgeBorder; // corresponds to R.dimen.status_bar_edge_ignore
    boolean mTracking;
    VelocityTracker mVelocityTracker;

    // help screen
    private boolean mClingShown;
    private ViewGroup mCling;
    private boolean mSuppressStatusBarDrags; // while a cling is up, briefly deaden the bar to give things time to settle

    int[] mAbsPos = new int[2];

    private Animator mLightsOutAnimation;
    private Animator mLightsOnAnimation;

    // last theme that was applied in order to detect theme change (as opposed
    // to some other configuration change).
    CustomTheme mCurrentTheme;
    private boolean mRecreating = false;

    private boolean mBrightnessControl = true;
    private float mScreenWidth;
    private int mMinBrightness;
    int mLinger;
    int mInitialTouchX;
    int mInitialTouchY;

    // for disabling the status bar
    int mDisabled = 0;

    // navbar recreate and longpress home handling
    boolean mDisableHomeLongpress;
    String mOldNavBarConfig = "";

    // tracking calls to View.setSystemUiVisibility()
    int mSystemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE;

    DisplayMetrics mDisplayMetrics = new DisplayMetrics();

    // XXX: gesture research
    private final GestureRecorder mGestureRec = DEBUG_GESTURES
        ? new GestureRecorder("/sdcard/statusbar_gestures.dat")
        : null;

    // brightness slider
    private int mIsBrightNessMode = 0;
    private int mIsStatusBarBrightNess;
    private boolean mIsAutoBrightNess;
    private Float mPropFactor;

    private int mNavigationIconHints = 0;
    private final Animator.AnimatorListener mMakeIconsInvisible = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            // double-check to avoid races
            if (mStatusBarContents.getAlpha() == 0) {
                if (DEBUG) Slog.d(TAG, "makeIconsInvisible");
                mStatusBarContents.setVisibility(View.INVISIBLE);
            }
        }
    };

    Runnable mLongPressBrightnessChange = new Runnable() {
        @Override
        public void run() {
            mStatusBarView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            adjustBrightness(mInitialTouchX);
            mLinger = BRIGHTNESS_CONTROL_LINGER_THRESHOLD + 1;
        }
    };

    private final Runnable mNotifyClearAll = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) {
                Slog.v(TAG, "Notifying status bar of notification clear");
            }
            try {
                mPile.setViewRemoval(true);
                mBarService.onClearAllNotifications();
            } catch (RemoteException ex) { }
        }
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_BRIGHTNESS_CONTROL), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS_MODE), false, this, mCurrentUserId);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.EXPANDED_VIEW_WIDGET), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_SHORTCUTS_CONFIG), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_HIDE_CARRIER), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.AUTO_HIDE_STATUSBAR), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_DISABLE_STATUSBAR_INFO), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_CONTROLS), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.EXPANDED_DESKTOP_STATE), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NOTIFICATION_SETTINGS_BUTTON), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.APP_SIDEBAR_POSITION), false, this);
            update();
        }

        @Override
        public void onChange(boolean selfChange) {
            update();
        }

        public void update() {
            ContentResolver resolver = mContext.getContentResolver();
            boolean autoBrightness = Settings.System.getIntForUser(
                    resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0, mCurrentUserId) ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
            mBrightnessControl = !autoBrightness && Settings.System.getInt(
                    resolver, Settings.System.STATUS_BAR_BRIGHTNESS_CONTROL, 0) == 1;

            String notificationShortcutsIsActive = Settings.System.getStringForUser(resolver,
                    Settings.System.NOTIFICATION_SHORTCUTS_CONFIG, UserHandle.USER_CURRENT);
            mNotificationShortcutsIsActive = !(notificationShortcutsIsActive == null
                    || notificationShortcutsIsActive.isEmpty());

            mNotificationHideCarrier = Settings.System.getIntForUser(resolver,
                    Settings.System.NOTIFICATION_HIDE_CARRIER, 0, UserHandle.USER_CURRENT) != 0;
            boolean notificationSettingsBtn = Settings.System.getInt(
                    resolver, Settings.System.NOTIFICATION_SETTINGS_BUTTON, 0) == 1;
            if (mHasSettingsPanel) {
                mSettingsButton.setVisibility(notificationSettingsBtn ? View.VISIBLE : View.GONE);
            } else {
                mSettingsButton.setVisibility(View.GONE);
            }
            if (mCarrierLabel != null) {
                toggleCarrierAndWifiLabelVisibility();
            }
            if (mNotificationData != null) {
                updateStatusBarVisibility();
            }
            showClock(true);
            int sidebarPosition = Settings.System.getInt(
                    resolver, Settings.System.APP_SIDEBAR_POSITION, AppSidebar.SIDEBAR_POSITION_LEFT);
            if (sidebarPosition != mSidebarPosition) {
                mSidebarPosition = sidebarPosition;
                mWindowManager.updateViewLayout(mAppSidebar, getAppSidebarLayoutParams(sidebarPosition));
            }
        }
    }

    // ensure quick settings is disabled until the current user makes it through the setup wizard
    private boolean mUserSetup = false;
    private final ContentObserver mUserSetupObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            final boolean userSetup = 0 != Settings.Secure.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.USER_SETUP_COMPLETE,
                    0 /*default */,
                    mCurrentUserId);
            if (MULTIUSER_DEBUG) Slog.d(TAG, String.format("User setup changed: " +
                    "selfChange=%s userSetup=%s mUserSetup=%s",
                    selfChange, userSetup, mUserSetup));

            if (mQuickSettingsButton != null && mHasFlipSettings) {
                mQuickSettingsButton.setVisibility(userSetup ? View.VISIBLE : View.INVISIBLE);
            }
            if (mSettingsPanel != null) {
                mSettingsPanel.setEnabled(userSetup);
            }
            if (userSetup != mUserSetup) {
                mUserSetup = userSetup;
                if (!mUserSetup && mStatusBarView != null)
                    animateCollapseQuickSettings();
            }
        }
    };

    @Override
    public void start() {
        mDisplay = ((WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();

        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));

        mCurrUiInvertedMode = mContext.getResources().getConfiguration().uiInvertedMode;

        CustomTheme currentTheme = mContext.getResources().getConfiguration().customTheme;
        if (currentTheme != null) {
            mCurrentTheme = (CustomTheme)currentTheme.clone();
        }

        super.start(); // calls createAndAddWindows()

        addNavigationBar();

        if (ENABLE_INTRUDERS) addIntruderView();

        SettingsObserver observer = new SettingsObserver(mHandler);
        observer.observe();

        // Lastly, call to the icon policy to install/update all the icons.
        mIconPolicy = new PhoneStatusBarPolicy(mContext);
    }

    private int calculateCarrierLabelBottomMargin() {
        return mNotificationShortcutsIsActive ? mShortcutsSpacingHeight : 0;
    }

    private void updateCarrierMargin(boolean forceHide) {
        lpScrollView.bottomMargin = mNotificationShortcutsIsActive ? mShortcutsDrawerMargin : 0;
        mScrollView.setLayoutParams(lpScrollView);

        if (!mShowCarrierInPanel) return;
        if (forceHide) {
            lpCarrierLabel.bottomMargin = mNotificationShortcutsIsActive ? mShortcutsSpacingHeight : 0;
        } else {
            lpCarrierLabel.bottomMargin = mNotificationShortcutsIsActive ? mShortcutsSpacingHeight : mCloseViewHeight;
        }
        mCarrierAndWifiView.setLayoutParams(lpCarrierLabel);
    }

    private void toggleCarrierAndWifiLabelVisibility() {
        mShowCarrierInPanel = !mNotificationHideCarrier;
        updateCarrierMargin(mNotificationHideCarrier);
        mCarrierAndWifiView.setVisibility(mShowCarrierInPanel ? View.VISIBLE : View.INVISIBLE);
    }

    // ================================================================================
    // Constructing the view
    // ================================================================================
    protected PhoneStatusBarView makeStatusBarView() {
        final Context context = mContext;

        Resources res = context.getResources();

        mScreenWidth = (float) context.getResources().getDisplayMetrics().widthPixels;
        mMinBrightness = context.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessDim);

        updateDisplaySize(); // populates mDisplayMetrics
        loadDimens();

        mIconSize = res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_icon_size);

        mStatusBarWindow = (StatusBarWindowView) View.inflate(context,
                R.layout.super_status_bar, null);
        mStatusBarWindow.mService = this;
        mStatusBarWindow.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    if (mExpandedVisible) {
                        animateCollapsePanels();
                    }
                }
                return mStatusBarWindow.onTouchEvent(event);
            }});

        mStatusBarView = (PhoneStatusBarView) mStatusBarWindow.findViewById(R.id.status_bar);
        mStatusBarView.setBar(this);

        PanelHolder holder = (PanelHolder) mStatusBarWindow.findViewById(R.id.panel_holder);
        mStatusBarView.setPanelHolder(holder);

        mNotificationPanel = (NotificationPanelView) mStatusBarWindow.findViewById(R.id.notification_panel);
        mNotificationPanel.setStatusBar(this);
        mNotificationPanelIsFullScreenWidth =
            (mNotificationPanel.getLayoutParams().width == ViewGroup.LayoutParams.MATCH_PARENT);

        // make the header non-responsive to clicks
        mNotificationPanel.findViewById(R.id.header).setOnTouchListener(
                new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        return true; // e eats everything
                    }
                });

        if (!ActivityManager.isHighEndGfx()) {
            mStatusBarWindow.setBackground(null);
            mNotificationPanel.setBackground(new FastColorDrawable(context.getResources().getColor(
                    R.color.notification_panel_solid_background)));
        }
        if (ENABLE_INTRUDERS) {
            mIntruderAlertView = (IntruderAlertView) View.inflate(context, R.layout.intruder_alert, null);
            mIntruderAlertView.setVisibility(View.GONE);
            mIntruderAlertView.setBar(this);
        }
        if (MULTIUSER_DEBUG) {
            mNotificationPanelDebugText = (TextView) mNotificationPanel.findViewById(R.id.header_debug_info);
            mNotificationPanelDebugText.setVisibility(View.VISIBLE);
        }

        updateShowSearchHoldoff();

        if (mNavigationBarView == null) {
            mNavigationBarView =
                (NavigationBarView) View.inflate(context, R.layout.navigation_bar, null);

            mNavigationBarView.setDisabledFlags(mDisabled);
            mNavigationBarView.setBar(this);
            addNavigationBarCallback(mNavigationBarView);
        }

        if (mRecreating) {
            removeSidebarView();
        }
        addSidebarView();

        // figure out which pixel-format to use for the status bar.
        mPixelFormat = PixelFormat.OPAQUE;

        mSystemIconArea = (LinearLayout) mStatusBarView.findViewById(R.id.system_icon_area);
        mStatusIcons = (LinearLayout)mStatusBarView.findViewById(R.id.statusIcons);
        mNotificationIcons = (IconMerger)mStatusBarView.findViewById(R.id.notificationIcons);
        mMoreIcon = mStatusBarView.findViewById(R.id.moreIcon);
        mNotificationIcons.setOverflowIndicator(mMoreIcon);
        mStatusBarContents = (LinearLayout)mStatusBarView.findViewById(R.id.status_bar_contents);
        mCenterClockLayout = (LinearLayout)mStatusBarView.findViewById(R.id.center_clock_layout);
        mTickerView = mStatusBarView.findViewById(R.id.ticker);

        /* Destroy the old widget before recreating the expanded dialog
           to make sure there are no context issues */
        if (mRecreating)
            mPowerWidget.destroyWidget();

        mPile = (NotificationRowLayout)mStatusBarWindow.findViewById(R.id.latestItems);
        mPile.setLayoutTransitionsEnabled(false);
        mPile.setLongPressListener(getNotificationLongClicker());
        mExpandedContents = mPile; // was: expanded.findViewById(R.id.notificationLinearLayout);

        mNotificationPanelHeader = mStatusBarWindow.findViewById(R.id.header);

        mClearButton = mStatusBarWindow.findViewById(R.id.clear_all_button);
        mClearButton.setOnClickListener(mClearButtonListener);
        mClearButton.setAlpha(0f);
        mClearButton.setVisibility(View.INVISIBLE);
        mClearButton.setEnabled(false);
        mDateView = (DateView)mStatusBarWindow.findViewById(R.id.date);

        if (mStatusBarView.hasFullWidthNotifications()) {
            mHideSettingsPanel = Settings.System.getInt(mContext.getContentResolver(),
                                    Settings.System.QS_DISABLE_PANEL, 0) == 1;
            mHasSettingsPanel = res.getBoolean(R.bool.config_hasSettingsPanel) && !mHideSettingsPanel;
        } else {
            mHideSettingsPanel = false;
            mHasSettingsPanel = res.getBoolean(R.bool.config_hasSettingsPanel);
        }
        mHasFlipSettings = res.getBoolean(R.bool.config_hasFlipSettingsPanel);

        mDateTimeView = mNotificationPanelHeader.findViewById(R.id.datetime);
        if (mDateTimeView != null) {
            mDateTimeView.setOnClickListener(mClockClickListener);
            mDateTimeView.setEnabled(true);
        }

        mSettingsButton = (ImageView) mStatusBarWindow.findViewById(R.id.settings_button);
        mSettingsButton.setOnClickListener(mSettingsButtonListener);

        boolean notificationSettingsBtn = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.NOTIFICATION_SETTINGS_BUTTON, 0) == 1;

        mQuickSettingsButton = (ImageView) mStatusBarWindow.findViewById(R.id.quicksettings_button);
        if (mQuickSettingsButton != null) {
            mQuickSettingsButton.setOnClickListener(mQuickSettingsButtonListener);
            if (mHasSettingsPanel) {
                if (mStatusBarView.hasFullWidthNotifications()) {
                    // the settings panel is hiding behind this button
                    mQuickSettingsButton.setImageResource(R.drawable.ic_notify_quicksettings);
                    mQuickSettingsButton.setVisibility(View.VISIBLE);
                } else {
                    // there is a settings panel, but it's on the other side of the (large) screen
                    final View buttonHolder = mStatusBarWindow.findViewById(
                            R.id.settings_button_holder);
                    if (buttonHolder != null) {
                        buttonHolder.setVisibility(View.GONE);
                    }
                }
                mSettingsButton.setVisibility(notificationSettingsBtn ? View.VISIBLE : View.GONE);
            } else {
                // no settings panel, go straight to settings
                mQuickSettingsButton.setVisibility(View.VISIBLE);
                mQuickSettingsButton.setImageResource(R.drawable.ic_notify_settings);
                mSettingsButton.setVisibility(View.GONE);
            }

        }
        if (mHasFlipSettings) {
            mNotificationButton = (ImageView) mStatusBarWindow.findViewById(R.id.notification_button);
            if (mNotificationButton != null) {
                mNotificationButton.setOnClickListener(mNotificationButtonListener);
                mNotificationButton.setOnLongClickListener(mNotificationLongClickListener);
            }
        }

        mScrollView = (ScrollView)mStatusBarWindow.findViewById(R.id.scroll);
        mScrollView.setVerticalScrollBarEnabled(false); // less drawing during pulldowns
        mQuickSettingsButton.setOnLongClickListener(mSettingsLongClickListener);
        if (!mNotificationPanelIsFullScreenWidth) {
            mScrollView.setSystemUiVisibility(
                    View.STATUS_BAR_DISABLE_NOTIFICATION_TICKER |
                    View.STATUS_BAR_DISABLE_NOTIFICATION_ICONS |
                    View.STATUS_BAR_DISABLE_CLOCK);
        }

        // Load the Power widget views and set the listeners
        mPowerWidget = (PowerWidget)mStatusBarWindow.findViewById(R.id.exp_power_stat);
        mPowerWidget.setBar(mStatusBarView);
        mPowerWidget.setGlobalButtonOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if(Settings.System.getInt(mContext.getContentResolver(),
                                Settings.System.EXPANDED_HIDE_ONCHANGE, 0) == 1) {
                            animateCollapsePanels();
                        }
                    }
                });
        mPowerWidget.setGlobalButtonOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                animateCollapsePanels();
                return true;
            }
        });

        mTicker = new MyTicker(context, mStatusBarView);

        TickerView tickerView = (TickerView)mStatusBarView.findViewById(R.id.tickerText);
        tickerView.mTicker = mTicker;

        mEdgeBorder = res.getDimensionPixelSize(R.dimen.status_bar_edge_ignore);

        // set the inital view visibility
        setAreThereNotifications();

        // Other icons
        mLocationController = new LocationController(mContext); // will post a notification
        mBatteryController = new BatteryController(mContext);
        mBatteryController.addIconView((ImageView)mStatusBarView.findViewById(R.id.battery));
        mBatteryController.addLabelView((TextView)mStatusBarView.findViewById(R.id.battery_text));
        mNetworkController = new NetworkController(mContext);
        mBluetoothController = new BluetoothController(mContext);
        final SignalClusterView signalCluster =
                (SignalClusterView)mStatusBarView.findViewById(R.id.signal_cluster);


        mNetworkController.addSignalCluster(signalCluster);
        signalCluster.setNetworkController(mNetworkController);

        final boolean isAPhone = mNetworkController.hasVoiceCallingFeature();
        if (isAPhone) {
            mEmergencyCallLabel =
                    (TextView) mStatusBarWindow.findViewById(R.id.emergency_calls_only);
            if (mEmergencyCallLabel != null) {
                mNetworkController.addEmergencyLabelView(mEmergencyCallLabel);
                mEmergencyCallLabel.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { }});
                mEmergencyCallLabel.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int left, int top, int right, int bottom,
                            int oldLeft, int oldTop, int oldRight, int oldBottom) {
                        updateCarrierAndWifiLabelVisibility(false);
                    }});
            }
        }

        mNotificationHideCarrier = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.NOTIFICATION_HIDE_CARRIER, 0, UserHandle.USER_CURRENT) != 0;

        mCarrierAndWifiView = mStatusBarWindow.findViewById(R.id.carrier_wifi);
        mWifiView = mStatusBarWindow.findViewById(R.id.wifi_view);

        mCarrierLabel = (TextView)mStatusBarWindow.findViewById(R.id.carrier_label);
        mShowCarrierInPanel = (mCarrierLabel != null);

        if (DEBUG) Slog.v(TAG, "carrierlabel=" + mCarrierLabel + " show=" + mShowCarrierInPanel);
        if (mShowCarrierInPanel) {
            lpCarrierLabel = (FrameLayout.LayoutParams) mCarrierAndWifiView.getLayoutParams();
            mCarrierLabel.setVisibility((mCarrierAndWifiViewVisible && !mNotificationHideCarrier) ? View.VISIBLE : View.INVISIBLE);
            if (mNotificationHideCarrier)
                mShowCarrierInPanel = false;
            // for mobile devices, we always show mobile connection info here (SPN/PLMN)
            // for other devices, we show whatever network is connected
            if (mNetworkController.hasMobileDataFeature()) {
                mNetworkController.addMobileLabelView(mCarrierLabel);
            } else {
                mNetworkController.addCombinedLabelView(mCarrierLabel);
            }
        }

        mWifiLabel = (TextView)mStatusBarWindow.findViewById(R.id.wifi_text);

        if (mWifiLabel != null) {
            mNetworkController.addWifiLabelView(mWifiLabel);

            mWifiLabel.addTextChangedListener(new TextWatcher() {

                public void afterTextChanged(Editable s) {
                }
                public void beforeTextChanged(CharSequence s, int start, int count,
                        int after) {
                }
                public void onTextChanged(CharSequence s, int start, int before,
                        int count) {
                     if (Settings.System.getInt(mContext.getContentResolver(),
                            Settings.System.NOTIFICATION_SHOW_WIFI_SSID, 0) == 1 &&
                            count > 0) {
                        mWifiView.setVisibility(View.VISIBLE);
                    }
                    else
                    {
                        mWifiView.setVisibility(View.GONE);
                    }
                }

            });

            // set up the dynamic hide/show of the labels
            mPile.setOnSizeChangedListener(new OnSizeChangedListener() {
                @Override
                public void onSizeChanged(View view, int w, int h, int oldw, int oldh) {
                    updateCarrierAndWifiLabelVisibility(false);
                }
            });
        }

        // Set notification background
        setNotificationWallpaperHelper();

        // Quick Settings (where available, some restrictions apply)
        if (mHasSettingsPanel) {
            // first, figure out where quick settings should be inflated
            final View settings_stub;
            if (mHasFlipSettings) {
                // a version of quick settings that flips around behind the notifications
                settings_stub = mStatusBarWindow.findViewById(R.id.flip_settings_stub);
                if (settings_stub != null) {
                    mFlipSettingsView = ((ViewStub)settings_stub).inflate();
                    mFlipSettingsView.setVisibility(View.GONE);
                    mFlipSettingsView.setVerticalScrollBarEnabled(false);
                }
            } else {
                // full quick settings panel
                settings_stub = mStatusBarWindow.findViewById(R.id.quick_settings_stub);
                if (settings_stub != null) {
                    mSettingsPanel = (SettingsPanelView) ((ViewStub)settings_stub).inflate();
                } else {
                    mSettingsPanel = (SettingsPanelView) mStatusBarWindow.findViewById(R.id.settings_panel);
                }

                if (mSettingsPanel != null) {
                    if (!ActivityManager.isHighEndGfx()) {
                        mSettingsPanel.setBackground(new FastColorDrawable(context.getResources().getColor(
                                R.color.notification_panel_solid_background)));
                    }
                }
            }

            if (mQS != null) {
                mQS.shutdown();
                mQS = null;
            }

            // wherever you find it, Quick Settings needs a container to survive
            mSettingsContainer = (QuickSettingsContainerView)
                    mStatusBarWindow.findViewById(R.id.quick_settings_container);
            if (mSettingsContainer != null) {
                mQS = new QuickSettingsController(mContext, mSettingsContainer, this);
                if (!mNotificationPanelIsFullScreenWidth) {
                    mSettingsContainer.setSystemUiVisibility(
                            View.STATUS_BAR_DISABLE_NOTIFICATION_TICKER
                            | View.STATUS_BAR_DISABLE_SYSTEM_INFO);
                }
                if (mSettingsPanel != null) {
                    mSettingsPanel.setQuickSettings(mQS);
                }
                mQS.setService(this);
                mQS.setBar(mStatusBarView);
                mQS.setupQuickSettings();
            }
        }

        // Start observing for changes on QuickSettings (needed here for enable/hide qs)
        if (mTilesChangedObserver == null) {
            mTilesChangedObserver = new TilesChangedObserver(mHandler);
            mTilesChangedObserver.startObserving();
        }

        mClingShown = ! (DEBUG_CLINGS
            || !Prefs.read(mContext).getBoolean(Prefs.SHOWN_QUICK_SETTINGS_HELP, false));

        if (!ENABLE_NOTIFICATION_PANEL_CLING || ActivityManager.isRunningInTestHarness()) {
            mClingShown = true;
        }

        // Notification Shortcuts
        mNotificationShortcutsLayout = (ShortcutsWidget)mStatusBarWindow.findViewById(R.id.custom_notificiation_shortcuts);
        mNotificationShortcutsLayout.setGlobalButtonOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                customButtonVibrate();
                animateCollapsePanels();
            }
        });
        mNotificationShortcutsLayout.setGlobalButtonOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                animateCollapsePanels();
                return true;
            }
        });
        mNotificationShortcutsScrollView = (HorizontalScrollView) mStatusBarWindow.findViewById(R.id.custom_notification_scrollview);

        String notificationShortcutsIsActive = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.NOTIFICATION_SHORTCUTS_CONFIG, UserHandle.USER_CURRENT);
        mNotificationShortcutsIsActive = !(notificationShortcutsIsActive == null
                || notificationShortcutsIsActive.isEmpty());

        lpScrollView = (FrameLayout.LayoutParams) mScrollView.getLayoutParams();

        mShortcutsDrawerMargin = res.getDimensionPixelSize(R.dimen.notification_shortcuts_drawer_margin);
        mShortcutsSpacingHeight = res.getDimensionPixelSize(R.dimen.notification_shortcuts_spacing_height);
        mCloseViewHeight = res.getDimensionPixelSize(R.dimen.close_handle_height);
        updateCarrierMargin(false);

//        final ImageView wimaxRSSI =
//                (ImageView)sb.findViewById(R.id.wimax_signal);
//        if (wimaxRSSI != null) {
//            mNetworkController.addWimaxIconView(wimaxRSSI);
//        }

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        context.registerReceiver(mBroadcastReceiver, filter);

        // listen for USER_SETUP_COMPLETE setting (per-user)
        resetUserSetupObserver();

        mPowerWidget.setupWidget();
        if (mPowerWidget.powerWidgetEnabled())
             mPowerWidget.setVisibility(View.VISIBLE);

        mNotificationShortcutsLayout.setupShortcuts();

        mVelocityTracker = VelocityTracker.obtain();

        mIsAutoBrightNess = checkAutoBrightNess();

        updatePropFactorValue();

        return mStatusBarView;
    }

    @Override
    protected View getStatusBarView() {
        return mStatusBarView;
    }

    @Override
    protected WindowManager.LayoutParams getRecentsLayoutParams(LayoutParams layoutParams) {
        boolean opaque = false;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                layoutParams.width,
                layoutParams.height,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                (opaque ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT));
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        } else {
            lp.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.dimAmount = 0.75f;
        }
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.setTitle("RecentsPanel");
        lp.windowAnimations = com.android.internal.R.style.Animation_RecentApplications;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        return lp;
    }

    @Override
    protected WindowManager.LayoutParams getSearchLayoutParams(LayoutParams layoutParams) {
        boolean opaque = false;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                (opaque ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT));
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.setTitle("SearchPanel");
        // TODO: Define custom animation for Search panel
        lp.windowAnimations = com.android.internal.R.style.Animation_RecentApplications;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        return lp;
    }

    void onBarViewDetached() {
     // WindowManagerImpl.getDefault().removeView(mStatusBarWindow);
    }

    @Override
    public void toggleStatusBar(boolean enable) {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.TOGGLE_NOTIFICATION_AND_QS_SHADE,
                enable ? 1 : 0);
    }

    @Override
    public void toggleNotificationShade() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.TOGGLE_NOTIFICATION_AND_QS_SHADE,
                (mExpandedVisible && !mQSPanelIsOpen) ? 0 : 1);

        int msg = (mExpandedVisible)
                ? ((mQSPanelIsOpen) ? MSG_FLIP_TO_NOTIFICATION_PANEL : MSG_CLOSE_PANELS) : MSG_OPEN_NOTIFICATION_PANEL;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void toggleQSShade() {
        int msg = 0;
        if (mHasFlipSettings) {
            if (Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.QS_DISABLE_PANEL, 0) == 0) {
                Settings.System.putInt(mContext.getContentResolver(),
                        Settings.System.TOGGLE_NOTIFICATION_AND_QS_SHADE,
                        (mExpandedVisible && !mNotificationPanelIsOpen) ? 0 : 1);

                msg = (mExpandedVisible)
                    ? ((mNotificationPanelIsOpen) ? MSG_FLIP_TO_QS_PANEL
                    : MSG_CLOSE_PANELS) : MSG_OPEN_QS_PANEL;
            }
        } else {
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.TOGGLE_NOTIFICATION_AND_QS_SHADE,
                    (mExpandedVisible) ? 0 : 1);
            msg = (mExpandedVisible)
                ? MSG_CLOSE_PANELS : MSG_OPEN_QS_PANEL;
        }
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    protected void updateSearchPanel() {
        super.updateSearchPanel();
        mSearchPanelView.setStatusBarView(mNavigationBarView);
        mNavigationBarView.setDelegateView(mSearchPanelView);
    }

    @Override
    public void showSearchPanel() {
        super.showSearchPanel();
        mHandler.removeCallbacks(mShowSearchPanel);

        // we want to freeze the sysui state wherever it is
        mSearchPanelView.setSystemUiVisibility(mSystemUiVisibility);

        WindowManager.LayoutParams lp =
            (android.view.WindowManager.LayoutParams) mNavigationBarView.getLayoutParams();
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        mWindowManager.updateViewLayout(mNavigationBarView, lp);
    }

    @Override
    public void hideSearchPanel() {
        super.hideSearchPanel();
        WindowManager.LayoutParams lp =
            (android.view.WindowManager.LayoutParams) mNavigationBarView.getLayoutParams();
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        mWindowManager.updateViewLayout(mNavigationBarView, lp);
    }

    protected int getStatusBarGravity() {
        return Gravity.TOP | Gravity.FILL_HORIZONTAL;
    }

    public int getStatusBarHeight() {
        if (mNaturalBarHeight < 0) {
            final Resources res = mContext.getResources();
            mNaturalBarHeight =
                    res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        }
        return mNaturalBarHeight;
    }

    private View.OnClickListener mRecentsClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            awakenDreams();
            toggleRecentApps();
        }
    };

    private int mShowSearchHoldoff = 0;
    private final Runnable mShowSearchPanel = new Runnable() {
        @Override
        public void run() {
            showSearchPanel();
            awakenDreams();
        }
    };

    View.OnTouchListener mHomeSearchActionListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch(event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (!shouldDisableNavbarGestures() && !mDisableHomeLongpress) {
                    mHandler.removeCallbacks(mShowSearchPanel);
                    mHandler.postDelayed(mShowSearchPanel, mShowSearchHoldoff);
                }
            break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacks(mShowSearchPanel);
                awakenDreams();
            break;
        }
        return false;
        }
    };

    private void awakenDreams() {
        if (mDreamManager != null) {
            try {
                mDreamManager.awaken();
            } catch (RemoteException e) {
                // fine, stay asleep then
            }
        }
    }

    private void prepareNavigationBarView() {
        mNavigationBarView.reorient();
        if (mNavigationBarView.getHomeButton() != null) {
            mNavigationBarView.getHomeButton().setOnTouchListener(mHomeSearchActionListener);
        }
        mNavigationBarView.getSearchLight().setOnTouchListener(mHomeSearchActionListener);
        updateSearchPanel();
    }

    // For small-screen devices (read: phones) that lack hardware navigation buttons
    private void addNavigationBar() {
        if (DEBUG) Slog.v(TAG, "addNavigationBar: about to add " + mNavigationBarView);
        if (mNavigationBarView == null) return;

        prepareNavigationBarView();

        mWindowManager.addView(mNavigationBarView, getNavigationBarLayoutParams());
    }

    private void repositionNavigationBar() {
        if (mNavigationBarView == null) return;

        CustomTheme newTheme = mContext.getResources().getConfiguration().customTheme;
        if (newTheme != null &&
                (mCurrentTheme == null || !mCurrentTheme.equals(newTheme))) {
            // Nevermind, this will be re-created
            return;
        }
        prepareNavigationBarView();

        mWindowManager.updateViewLayout(mNavigationBarView, getNavigationBarLayoutParams());
    }

    private void notifyNavigationBarScreenOn(boolean screenOn) {
        if (mNavigationBarView == null) return;
        mNavigationBarView.notifyScreenOn(screenOn);
    }

    private WindowManager.LayoutParams getNavigationBarLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                    0
                    | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        // this will allow the navbar to run in an overlay on devices that support this
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }

        lp.setTitle("NavigationBar");
        lp.windowAnimations = 0;
        return lp;
    }

    private void addIntruderView() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL, // above the status bar!
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
        //lp.y += height * 1.5; // FIXME
        lp.setTitle("IntruderAlert");
        lp.packageName = mContext.getPackageName();
        lp.windowAnimations = R.style.Animation_StatusBar_IntruderAlert;

        mWindowManager.addView(mIntruderAlertView, lp);
    }

    public void refreshAllStatusBarIcons() {
        refreshAllIconsForLayout(mStatusIcons);
        refreshAllIconsForLayout(mNotificationIcons);
    }

    private void refreshAllIconsForLayout(LinearLayout ll) {
        final int count = ll.getChildCount();
        for (int n = 0; n < count; n++) {
            View child = ll.getChildAt(n);
            if (child instanceof StatusBarIconView) {
                ((StatusBarIconView) child).updateDrawable();
            }
        }
    }

    @Override
    public void addIcon(String slot, int index, int viewIndex, StatusBarIcon icon) {
        if (SPEW) Slog.d(TAG, "addIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex
                + " icon=" + icon);
        StatusBarIconView view = new StatusBarIconView(mContext, slot, null);
        view.set(icon);
        mStatusIcons.addView(view, viewIndex, new LinearLayout.LayoutParams(mIconSize, mIconSize));
        mPowerWidget.updateAllButtons();
    }

    @Override
    public void updateIcon(String slot, int index, int viewIndex,
            StatusBarIcon old, StatusBarIcon icon) {
        if (SPEW) Slog.d(TAG, "updateIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex
                + " old=" + old + " icon=" + icon);
        StatusBarIconView view = (StatusBarIconView)mStatusIcons.getChildAt(viewIndex);
        view.set(icon);
    }

    @Override
    public void removeIcon(String slot, int index, int viewIndex) {
        if (SPEW) Slog.d(TAG, "removeIcon slot=" + slot + " index=" + index + " viewIndex=" + viewIndex);
        mStatusIcons.removeViewAt(viewIndex);
    }

    @Override
    public void addNotification(IBinder key, StatusBarNotification notification) {
        if (DEBUG) Slog.d(TAG, "addNotification score=" + notification.getScore());
        StatusBarIconView iconView = addNotificationViews(key, notification);
        if (iconView == null) return;

        boolean immersive = false;
        try {
            immersive = ActivityManagerNative.getDefault().isTopActivityImmersive();
            if (DEBUG) {
                Slog.d(TAG, "Top activity is " + (immersive?"immersive":"not immersive"));
            }
        } catch (RemoteException ex) {
        }

        /*
         * DISABLED due to missing API
        if (ENABLE_INTRUDERS && (
                   // TODO(dsandler): Only if the screen is on
                notification.notification.intruderView != null)) {
            Slog.d(TAG, "Presenting high-priority notification");
            // special new transient ticker mode
            // 1. Populate mIntruderAlertView

            if (notification.notification.intruderView == null) {
                Slog.e(TAG, notification.notification.toString() + " wanted to intrude but intruderView was null");
                return;
            }

            // bind the click event to the content area
            PendingIntent contentIntent = notification.notification.contentIntent;
            final View.OnClickListener listener = (contentIntent != null)
                    ? new NotificationClicker(contentIntent,
                            notification.pkg, notification.tag, notification.id)
                    : null;

            mIntruderAlertView.applyIntruderContent(notification.notification.intruderView, listener);

            mCurrentlyIntrudingNotification = notification;

            // 2. Animate mIntruderAlertView in
            mHandler.sendEmptyMessage(MSG_SHOW_INTRUDER);

            // 3. Set alarm to age the notification off (TODO)
            mHandler.removeMessages(MSG_HIDE_INTRUDER);
            if (INTRUDER_ALERT_DECAY_MS > 0) {
                mHandler.sendEmptyMessageDelayed(MSG_HIDE_INTRUDER, INTRUDER_ALERT_DECAY_MS);
            }
        } else
         */

        if (notification.getNotification().fullScreenIntent != null) {
            // Stop screensaver if the notification has a full-screen intent.
            // (like an incoming phone call)
            awakenDreams();

            // not immersive & a full-screen alert should be shown
            if (DEBUG) Slog.d(TAG, "Notification has fullScreenIntent; sending fullScreenIntent");
            try {
                notification.getNotification().fullScreenIntent.send();
            } catch (PendingIntent.CanceledException e) {
            }
        } else if (!mRecreating) {
            // usual case: status bar visible & not immersive

            // show the ticker if there isn't an intruder too
            if (mCurrentlyIntrudingNotification == null) {
                tick(null, notification, true);
            }
        }

        // Recalculate the position of the sliding windows and the titles.
        setAreThereNotifications();
        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
    }

    @Override
    public void removeNotification(IBinder key) {
        if (mQuickSettingsButton == null || mNotificationButton == null) {
            // Tablet
            updateNotificationShortcutsVisibility(false, true);
            if (mNotificationShortcutsIsActive) {
                mNotificationPanel.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        updateNotificationShortcutsVisibility(true);
                    }
                }, 750);
            }
        }
        StatusBarNotification old = removeNotificationViews(key);
        if (SPEW) Slog.d(TAG, "removeNotification key=" + key + " old=" + old);

        if (old != null) {
            // Cancel the ticker if it's still running
            mTicker.removeEntry(old);

            // Recalculate the position of the sliding windows and the titles.
            updateExpandedViewPos(EXPANDED_LEAVE_ALONE);

            if (ENABLE_INTRUDERS && old == mCurrentlyIntrudingNotification) {
                mHandler.sendEmptyMessage(MSG_HIDE_INTRUDER);
            }
        }

        setAreThereNotifications();
    }

    private void updateStatusBarVisibility() {
        switch (Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.AUTO_HIDE_STATUSBAR, 0)) {
            //autohide if no non-permanent notifications
            case 1:
                Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.HIDE_STATUSBAR,
                    hasClearableNotifications() ? 0 : 1);
                break;
            //autohide if no notifications
            case 2:
                Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.HIDE_STATUSBAR,
                    hasVisibleNotifications() ? 0 : 1);
                break;
            case 0:
            default:
                Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.HIDE_STATUSBAR, 0);
                break;
        }
    }

    @Override
    protected void refreshLayout(int layoutDirection) {
        if (mNavigationBarView != null) {
            mNavigationBarView.setLayoutDirection(layoutDirection);
        }

        if (mClearButton != null && mClearButton instanceof ImageView) {
            // Force asset reloading
            ((ImageView)mClearButton).setImageDrawable(null);
            ((ImageView)mClearButton).setImageResource(R.drawable.ic_notify_clear);
        }

        if (mSettingsButton != null) {
            // Force asset reloading
            mSettingsButton.setImageDrawable(null);
            mSettingsButton.setImageResource(R.drawable.ic_notify_quicksettings);
        }

        if (mNotificationButton != null) {
            // Force asset reloading
            mNotificationButton.setImageDrawable(null);
            mNotificationButton.setImageResource(R.drawable.ic_notifications);
        }

        refreshAllStatusBarIcons();
    }

    private void updateShowSearchHoldoff() {
        mShowSearchHoldoff = mContext.getResources().getInteger(
            R.integer.config_show_search_delay);
    }

    private void loadNotificationShade() {
        if (mPile == null) return;

        int N = mNotificationData.size();

        ArrayList<View> toShow = new ArrayList<View>();

        final boolean provisioned = isDeviceProvisioned();
        // If the device hasn't been through Setup, we only show system notifications
        for (int i=0; i<N; i++) {
            Entry ent = mNotificationData.get(N-i-1);
            if (!(provisioned || showNotificationEvenIfUnprovisioned(ent.notification))) continue;
            if (!notificationIsForCurrentUser(ent.notification)) continue;
            toShow.add(ent.row);
        }

        ArrayList<View> toRemove = new ArrayList<View>();
        for (int i=0; i<mPile.getChildCount(); i++) {
            View child = mPile.getChildAt(i);
            if (!toShow.contains(child)) {
                toRemove.add(child);
            }
        }

        for (View remove : toRemove) {
            mPile.removeView(remove);
        }

        //set alpha for notification pile before it is added
        setNotificationAlphaHelper();
        for (int i=0; i<toShow.size(); i++) {
            View v = toShow.get(i);
            if (v.getParent() == null) {
                mPile.addView(v, i);
            }
        }

        if (mQuickSettingsButton != null) {
            mQuickSettingsButton.setEnabled(isDeviceProvisioned());
        }
    }

    @Override
    protected void updateNotificationIcons() {
        if (mNotificationIcons == null) return;

        loadNotificationShade();

        final LinearLayout.LayoutParams params
            = new LinearLayout.LayoutParams(mIconSize + 2*mIconHPadding, mNaturalBarHeight);

        int N = mNotificationData.size();

        if (DEBUG) {
            Slog.d(TAG, "refreshing icons: " + N + " notifications, mNotificationIcons=" + mNotificationIcons);
        }

        ArrayList<View> toShow = new ArrayList<View>();

        final boolean provisioned = isDeviceProvisioned();
        // If the device hasn't been through Setup, we only show system notifications
        for (int i=0; i<N; i++) {
            Entry ent = mNotificationData.get(N-i-1);
            if (!((provisioned && ent.notification.getScore() >= HIDE_ICONS_BELOW_SCORE)
                    || showNotificationEvenIfUnprovisioned(ent.notification))) continue;
            if (!notificationIsForCurrentUser(ent.notification)) continue;
            toShow.add(ent.icon);
        }

        ArrayList<View> toRemove = new ArrayList<View>();
        for (int i=0; i<mNotificationIcons.getChildCount(); i++) {
            View child = mNotificationIcons.getChildAt(i);
            if (!toShow.contains(child)) {
                toRemove.add(child);
            }
        }

        for (View remove : toRemove) {
            mNotificationIcons.removeView(remove);
        }

        for (int i=0; i<toShow.size(); i++) {
            View v = toShow.get(i);
            if (v.getParent() == null) {
                mNotificationIcons.addView(v, i, params);
            }
        }
    }

    protected void updateCarrierAndWifiLabelVisibility(boolean force) {
        if (!mShowCarrierInPanel || mCarrierAndWifiView == null) return;

        if (DEBUG) {
            Slog.d(TAG, String.format("pileh=%d scrollh=%d carrierh=%d",
                    mPile.getHeight(), mScrollView.getHeight(), mCarrierAndWifiViewHeight));
        }

        final boolean emergencyCallsShownElsewhere = mEmergencyCallLabel != null;
        final boolean makeVisible =
            !(emergencyCallsShownElsewhere && mNetworkController.isEmergencyOnly())
            && mPile.getHeight() < (mNotificationPanel.getHeight() - mCarrierAndWifiViewHeight - mNotificationHeaderHeight - calculateCarrierLabelBottomMargin())
            && mScrollView.getVisibility() == View.VISIBLE;

        if (force || mCarrierAndWifiViewVisible != makeVisible) {
            mCarrierAndWifiViewVisible = makeVisible;
            if (DEBUG) {
                Slog.d(TAG, "making carrier label " + (makeVisible?"visible":"invisible"));
            }
            mCarrierAndWifiView.animate().cancel();
            if (makeVisible) {
                mCarrierAndWifiView.setVisibility(View.VISIBLE);
            }
             mCarrierAndWifiView.animate()
                .alpha(makeVisible ? 1f : 0f)
                .setDuration(150)
                .setListener(makeVisible ? null : new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (!mCarrierAndWifiViewVisible) { // race
                            mCarrierAndWifiView.setVisibility(View.INVISIBLE);
                            mCarrierAndWifiView.setAlpha(0f);
                        }
                    }
                })
                .start();
        }
    }

    boolean hasClearableNotifications() {
        if (mNotificationData != null) {
            return mNotificationData.size() > 0 && mNotificationData.hasClearableItems();
        }
        return false;
    }

    boolean hasVisibleNotifications() {
        if (mNotificationData != null) {
            return mNotificationData.size() > 0 && mNotificationData.hasVisibleItems();
        }
        return false;
    }

    protected void updateNotificationShortcutsVisibility(boolean vis, boolean instant) {
        if ((!mNotificationShortcutsIsActive && mNotificationShortcutsVisible == vis) ||
                mStatusBarWindow.findViewById(R.id.custom_notification_scrollview) == null) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "NSCUT: mNotificationShortcutsVisible=" + mNotificationShortcutsVisible + " vis=" + vis + " instant=" + instant);
        }

        if (instant) {
            mNotificationShortcutsScrollView.setVisibility(vis ? View.VISIBLE : View.INVISIBLE);
            mNotificationShortcutsScrollView.setAlpha(vis ? 1f : 0f);
            mNotificationShortcutsVisible = vis;
            return;
        }

        if (mNotificationShortcutsVisible != vis) {
            mNotificationShortcutsVisible = vis;
            if (vis) {
                mNotificationShortcutsScrollView.setVisibility(View.VISIBLE);
            }
            mNotificationShortcutsScrollView.animate()
                .alpha(vis ? 1f : 0f)
                .setDuration(150)
                .setListener(vis ? null : new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (!mNotificationShortcutsVisible) { // race
                            mNotificationShortcutsScrollView.setVisibility(View.INVISIBLE);
                            mNotificationShortcutsScrollView.setAlpha(0f);
                        }
                    }
                })
                .start();
        }
    }

    protected void updateNotificationShortcutsVisibility(boolean vis) {
        updateNotificationShortcutsVisibility(vis, false);
    }

    @Override
    protected void setAreThereNotifications() {
        final boolean any = mNotificationData.size() > 0;

        final boolean clearable = hasClearableNotifications();

        if (DEBUG) {
            Slog.d(TAG, "setAreThereNotifications: N=" + mNotificationData.size()
                    + " any=" + any + " clearable=" + clearable);
        }

        if (mHasFlipSettings
                && mFlipSettingsView != null
                && mFlipSettingsView.getVisibility() == View.VISIBLE
                && mScrollView.getVisibility() != View.VISIBLE) {
            // the flip settings panel is unequivocally showing; we should not be shown
            mClearButton.setVisibility(View.INVISIBLE);
        } else if (mClearButton.isShown()) {
            if (clearable != (mClearButton.getAlpha() == 1.0f)) {
                ObjectAnimator clearAnimation = ObjectAnimator.ofFloat(
                        mClearButton, "alpha", clearable ? 1.0f : 0.0f).setDuration(250);
                clearAnimation.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (mClearButton.getAlpha() <= 0.0f) {
                            mClearButton.setVisibility(View.INVISIBLE);
                        }
                    }

                    @Override
                    public void onAnimationStart(Animator animation) {
                        if (mClearButton.getAlpha() <= 0.0f) {
                            mClearButton.setVisibility(View.VISIBLE);
                        }
                    }
                });
                clearAnimation.start();
            }
        } else {
            mClearButton.setAlpha(clearable ? 1.0f : 0.0f);
            mClearButton.setVisibility(clearable ? View.VISIBLE : View.INVISIBLE);
        }
        mClearButton.setEnabled(clearable);

        final View nlo = mStatusBarView.findViewById(R.id.notification_lights_out);
        final boolean showDot = (any && !areLightsOn());
        if (showDot != (nlo.getAlpha() == 1.0f)) {
            if (showDot) {
                nlo.setAlpha(0f);
                nlo.setVisibility(View.VISIBLE);
            }
            nlo.animate()
                .alpha(showDot?1:0)
                .setDuration(showDot?750:250)
                .setInterpolator(new AccelerateInterpolator(2.0f))
                .setListener(showDot ? null : new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator _a) {
                        nlo.setVisibility(View.GONE);
                    }
                })
                .start();
        }

        if (mNotificationData.size() != mNotificationsSizeOldState) {
            mNotificationsSizeOldState = mNotificationData.size();
            updateStatusBarVisibility();
        }

        updateCarrierAndWifiLabelVisibility(false);
    }

    public void showClock(boolean show) {
        if (mStatusBarView == null) return;
        ContentResolver resolver = mContext.getContentResolver();
        boolean disableStatusBarInfo = Settings.System.getInt(resolver,
                Settings.System.PIE_DISABLE_STATUSBAR_INFO, 0) == 1;
        if (disableStatusBarInfo) {
            // call only the settings if statusbar info is really hidden
            int pieMode = Settings.System.getInt(resolver,
                    Settings.System.PIE_CONTROLS, 0);
            boolean expandedDesktopState = Settings.System.getInt(resolver,
                    Settings.System.EXPANDED_DESKTOP_STATE, 0) == 1;

            if (pieMode == 2
                || pieMode == 1 && expandedDesktopState) {
                show = false;
            }
        }
        View clock = mStatusBarView.findViewById(R.id.clock);
        View cclock = mStatusBarView.findViewById(R.id.center_clock);
        mShowClock = (Settings.System.getInt(resolver,
                Settings.System.STATUS_BAR_CLOCK, 1) == 1);
        boolean rightClock = (Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUSBAR_CLOCK_STYLE, 0) == 0);
        boolean centerClock = (Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUSBAR_CLOCK_STYLE, 0) == 1);
        if (rightClock && clock != null) {
            clock.setVisibility(show ? (mShowClock ? View.VISIBLE : View.GONE) : View.GONE);
        }
        if (centerClock && cclock != null) {
            cclock.setVisibility(show ? (mShowClock ? View.VISIBLE : View.GONE) : View.GONE);
        }
    }

    /**
     * State is one or more of the DISABLE constants from StatusBarManager.
     */
    @Override
    public void disable(int state) {
        final int old = mDisabled;
        final int diff = state ^ old;
        mDisabled = state;

        if (DEBUG) {
            Slog.d(TAG, String.format("disable: 0x%08x -> 0x%08x (diff: 0x%08x)",
                old, state, diff));
        }

        StringBuilder flagdbg = new StringBuilder();
        flagdbg.append("disable: < ");
        flagdbg.append(((state & StatusBarManager.DISABLE_EXPAND) != 0) ? "EXPAND" : "expand");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_EXPAND) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) ? "ICONS" : "icons");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0) ? "ALERTS" : "alerts");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) ? "TICKER" : "ticker");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_SYSTEM_INFO) != 0) ? "SYSTEM_INFO" : "system_info");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_SYSTEM_INFO) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_BACK) != 0) ? "BACK" : "back");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_BACK) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_HOME) != 0) ? "HOME" : "home");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_HOME) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_RECENT) != 0) ? "RECENT" : "recent");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_RECENT) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_CLOCK) != 0) ? "CLOCK" : "clock");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_CLOCK) != 0) ? "* " : " ");
        flagdbg.append(((state & StatusBarManager.DISABLE_SEARCH) != 0) ? "SEARCH" : "search");
        flagdbg.append(((diff  & StatusBarManager.DISABLE_SEARCH) != 0) ? "* " : " ");
        flagdbg.append(">");
        Slog.d(TAG, flagdbg.toString());

        if ((diff & StatusBarManager.DISABLE_SYSTEM_INFO) != 0) {
            mSystemIconArea.animate().cancel();
            if ((state & StatusBarManager.DISABLE_SYSTEM_INFO) != 0) {
                mSystemIconArea.animate()
                    .alpha(0f)
                    .translationY(mNaturalBarHeight*0.5f)
                    .setDuration(175)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .setListener(mMakeIconsInvisible)
                    .start();
            } else {
                mSystemIconArea.setVisibility(View.VISIBLE);
                mSystemIconArea.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setStartDelay(0)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .setDuration(175)
                    .start();
            }
        }

        if ((diff & StatusBarManager.DISABLE_CLOCK) != 0) {
            boolean show = (state & StatusBarManager.DISABLE_CLOCK) == 0;
            showClock(show);
        }
        if ((diff & StatusBarManager.DISABLE_EXPAND) != 0) {
            if ((state & StatusBarManager.DISABLE_EXPAND) != 0) {
                animateCollapsePanels();
            }
        }

        if ((diff & (StatusBarManager.DISABLE_HOME
                        | StatusBarManager.DISABLE_RECENT
                        | StatusBarManager.DISABLE_BACK
                        | StatusBarManager.DISABLE_SEARCH)) != 0) {

            // all navigation bar listeners will take care of these
            propagateDisabledFlags(state);

            if ((state & StatusBarManager.DISABLE_RECENT) != 0) {
                // close recents if it's visible
                mHandler.removeMessages(MSG_CLOSE_RECENTS_PANEL);
                mHandler.sendEmptyMessage(MSG_CLOSE_RECENTS_PANEL);
            }
        }

        if ((diff & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
            if ((state & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
                if (mTicking) {
                    haltTicker();
                }

                mNotificationIcons.animate()
                    .alpha(0f)
                    .translationY(mNaturalBarHeight*0.5f)
                    .setDuration(175)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .setListener(mMakeIconsInvisible)
                    .start();
            } else {
                mNotificationIcons.setVisibility(View.VISIBLE);
                mNotificationIcons.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setStartDelay(0)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .setDuration(175)
                    .start();
            }
        } else if ((diff & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
            if (mTicking && (state & StatusBarManager.DISABLE_NOTIFICATION_TICKER) != 0) {
                haltTicker();
            }
        }
        mStatusBarView.updateBackgroundAlpha();
    }

    @Override
    protected BaseStatusBar.H createHandler() {
        return new PhoneStatusBar.H();
    }

    /**
     * All changes to the status bar and notifications funnel through here and are batched.
     */
    private class H extends BaseStatusBar.H {
        @Override
        public void handleMessage(Message m) {
            super.handleMessage(m);
            switch (m.what) {
                case MSG_OPEN_NOTIFICATION_PANEL:
                    animateExpandNotificationsPanel();
                    break;
                case MSG_OPEN_SETTINGS_PANEL:
                    animateExpandSettingsPanel(true);
                    break;
                case MSG_OPEN_QS_PANEL:
                    animateExpandSettingsPanel(false);
                    break;
                case MSG_FLIP_TO_NOTIFICATION_PANEL:
                    flipToNotifications();
                    break;
                case MSG_FLIP_TO_QS_PANEL:
                    flipToSettings();
                    break;
                case MSG_CLOSE_PANELS:
                    animateCollapsePanels();
                    break;
                case MSG_SHOW_INTRUDER:
                    setIntruderAlertVisibility(true);
                    break;
                case MSG_HIDE_INTRUDER:
                    setIntruderAlertVisibility(false);
                    mCurrentlyIntrudingNotification = null;
                    break;
                case MSG_STATUSBAR_BRIGHTNESS:
                    if (mIsStatusBarBrightNess == 1) {
                        mIsBrightNessMode = 1;
                        // don't collapse the statusbar to see %
                        // updateExpandedViewPos(0);
                        // performCollapse();
                    }
                    break;
            }
        }
    }

    public Handler getHandler() {
        return mHandler;
    }

    View.OnFocusChangeListener mFocusChangeListener = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            // Because 'v' is a ViewGroup, all its children will be (un)selected
            // too, which allows marqueeing to work.
            v.setSelected(hasFocus);
        }
    };

    void makeExpandedVisible(boolean revealAfterDraw) {
        if (SPEW) Slog.d(TAG, "Make expanded visible: expanded visible=" + mExpandedVisible);
        if (mExpandedVisible) {
            return;
        }

        mExpandedVisible = true;
        mPile.setLayoutTransitionsEnabled(true);
        if (mNavigationBarView != null)
            mNavigationBarView.setSlippery(true);

        updateCarrierAndWifiLabelVisibility(true);

        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);

        // Expand the window to encompass the full screen in anticipation of the drag.
        // This is only possible to do atomically because the status bar is at the top of the screen!
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) mStatusBarContainer.getLayoutParams();
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        mWindowManager.updateViewLayout(mStatusBarContainer, lp);

        // Updating the window layout will force an expensive traversal/redraw.
        // Kick off the reveal animation after this is complete to avoid animation latency.
        if (revealAfterDraw) {
//            mHandler.post(mStartRevealAnimation);
        }

        visibilityChanged(true);
    }

    public void animateCollapsePanels() {
        mNotificationPanelIsOpen = false;
        mQSPanelIsOpen = false;
        animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
    }

    @Override
    public void animateCollapsePanels(int flags) {
        if (SPEW) {
            Slog.d(TAG, "animateCollapse():"
                    + " mExpandedVisible=" + mExpandedVisible
                    + " flags=" + flags);
        }

        if ((flags & CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL) == 0) {
            mHandler.removeMessages(MSG_CLOSE_RECENTS_PANEL);
            mHandler.sendEmptyMessage(MSG_CLOSE_RECENTS_PANEL);
        }

        if ((flags & CommandQueue.FLAG_EXCLUDE_SEARCH_PANEL) == 0) {
            mHandler.removeMessages(MSG_CLOSE_SEARCH_PANEL);
            mHandler.sendEmptyMessage(MSG_CLOSE_SEARCH_PANEL);
        }

        mStatusBarWindow.cancelExpandHelper();
        mStatusBarView.collapseAllPanels(true);
    }

    public ViewPropertyAnimator setVisibilityWhenDone(
            final ViewPropertyAnimator a, final View v, final int vis) {
        a.setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                v.setVisibility(vis);
                a.setListener(null); // oneshot
            }
        });
        return a;
    }

    public Animator setVisibilityWhenDone(
            final Animator a, final View v, final int vis) {
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                v.setVisibility(vis);
            }
        });
        return a;
    }

    public Animator setVisibilityWhenDone(
            final Animator a, final View v, final int vis, final View v2, final int vis2) {
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                v.setVisibility(vis);
                v2.setVisibility(vis2);
            }
        });
        return a;
    }

    public Animator interpolator(TimeInterpolator ti, Animator a) {
        a.setInterpolator(ti);
        return a;
    }

    public Animator startDelay(int d, Animator a) {
        a.setStartDelay(d);
        return a;
    }
    
    public Animator start(Animator a) {
        a.start();
        return a;
    }

    final TimeInterpolator mAccelerateInterpolator = new AccelerateInterpolator();
    final TimeInterpolator mDecelerateInterpolator = new DecelerateInterpolator();
    final int FLIP_DURATION_OUT = 125;
    final int FLIP_DURATION_IN = 225;
    final int FLIP_DURATION = (FLIP_DURATION_IN + FLIP_DURATION_OUT);

    Animator mScrollViewAnim, mFlipSettingsViewAnim, mPowerWidgetAnim, mNotificationButtonAnim,
        mQuickSettingsButtonAnim, mClearButtonAnim;

    @Override
    public void animateExpandNotificationsPanel() {
        if (SPEW) Slog.d(TAG, "animateExpand: mExpandedVisible=" + mExpandedVisible);
        if ((mDisabled & StatusBarManager.DISABLE_EXPAND) != 0) {
            return ;
        }

        mNotificationPanel.expand();
        mNotificationPanelIsOpen = true;
        mQSPanelIsOpen = false;

        if (mHasFlipSettings && mScrollView.getVisibility() != View.VISIBLE) {
            flipToNotifications();
        }

        if (false) postStartTracing();
    }

    public void flipToNotifications() {
        mNotificationPanelIsOpen = true;
        mQSPanelIsOpen = false;
        if (mFlipSettingsViewAnim != null) mFlipSettingsViewAnim.cancel();
        if (mPowerWidgetAnim != null) mPowerWidgetAnim.cancel();
        if (mScrollViewAnim != null) mScrollViewAnim.cancel();
        if (mQuickSettingsButtonAnim != null) mQuickSettingsButtonAnim.cancel();
        if (mNotificationButtonAnim != null) mNotificationButtonAnim.cancel();
        if (mClearButtonAnim != null) mClearButtonAnim.cancel();

        final boolean halfWayDone = mScrollView.getVisibility() == View.VISIBLE;
        final int zeroOutDelays = halfWayDone ? 0 : 1;

        if (!halfWayDone) {
            mScrollView.setScaleX(0f);
            mFlipSettingsView.setScaleX(1f);
        }

        mScrollView.setVisibility(View.VISIBLE);
        mScrollViewAnim = start(
            startDelay(FLIP_DURATION_OUT * zeroOutDelays,
                interpolator(mDecelerateInterpolator,
                    ObjectAnimator.ofFloat(mScrollView, View.SCALE_X, 1f)
                        .setDuration(FLIP_DURATION_IN)
                    )));
        mPowerWidgetAnim = start(
            startDelay(FLIP_DURATION_OUT * zeroOutDelays,
                interpolator(mDecelerateInterpolator,
                    ObjectAnimator.ofFloat(mPowerWidget, View.SCALE_X, 1f)
                        .setDuration(FLIP_DURATION_IN)
                    )));
        if (mPowerWidget.powerWidgetEnabled()) {
            mFlipSettingsViewAnim = start(
                setVisibilityWhenDone(
                    interpolator(mAccelerateInterpolator,
                            ObjectAnimator.ofFloat(mFlipSettingsView, View.SCALE_X, 0f)
                            )
                        .setDuration(FLIP_DURATION_OUT),
                    mFlipSettingsView, View.INVISIBLE,
                    mPowerWidget, View.VISIBLE));
        } else {
            mFlipSettingsViewAnim = start(
                setVisibilityWhenDone(
                    interpolator(mAccelerateInterpolator,
                            ObjectAnimator.ofFloat(mFlipSettingsView, View.SCALE_X, 0f)
                            )
                        .setDuration(FLIP_DURATION_OUT),
                    mFlipSettingsView, View.INVISIBLE));
        }

        mNotificationButtonAnim = start(
            setVisibilityWhenDone(
                ObjectAnimator.ofFloat(mNotificationButton, View.ALPHA, 0f)
                    .setDuration(FLIP_DURATION),
                mNotificationButton, View.INVISIBLE));
        mQuickSettingsButton.setVisibility(View.VISIBLE);
        mQuickSettingsButtonAnim = start(
            ObjectAnimator.ofFloat(mQuickSettingsButton, View.ALPHA, 1f)
                .setDuration(FLIP_DURATION));
        mClearButton.setVisibility(View.VISIBLE);
        mClearButton.setAlpha(0f);
        setAreThereNotifications(); // this will show/hide the button as necessary
        mNotificationPanel.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateCarrierAndWifiLabelVisibility(false);
            }
        }, FLIP_DURATION - 150);

        if (mNotificationShortcutsIsActive)
            updateNotificationShortcutsVisibility(true);

    }

    @Override
    public void animateExpandSettingsPanel(boolean flip) {
        if (SPEW) Slog.d(TAG, "animateExpand: mExpandedVisible=" + mExpandedVisible);
        if ((mDisabled & StatusBarManager.DISABLE_EXPAND) != 0) {
            return;
        }

        // Settings are not available in setup
        if (!mUserSetup) return;

        if (mHasFlipSettings) {
            mNotificationPanel.expand();
            if (mFlipSettingsView.getVisibility() != View.VISIBLE) {
                if (flip) {
                    flipToSettings();
                } else {
                    switchToSettings();
                }
            }
            mNotificationPanelIsOpen = false;
            mQSPanelIsOpen = true;
        } else if (mSettingsPanel != null) {
            mSettingsPanel.expand();
        }

        if (false) postStartTracing();
    }

    public void switchToSettings() {
        // Settings are not available in setup
        if (!mUserSetup) return;

        mFlipSettingsView.setScaleX(1f);
        mFlipSettingsView.setVisibility(View.VISIBLE);
        mQuickSettingsButton.setVisibility(View.GONE);
        mScrollView.setVisibility(View.GONE);
        mScrollView.setScaleX(0f);
        mPowerWidget.setVisibility(View.GONE);
        mNotificationButton.setVisibility(View.VISIBLE);
        mNotificationButton.setAlpha(1f);
        mClearButton.setVisibility(View.GONE);
    }

    public boolean isShowingSettings() {
        if (mFlipSettingsView != null) {
            return mHasFlipSettings && mFlipSettingsView.getVisibility() == View.VISIBLE;
        }
        return false;
    }

    public void completePartialFlip() {
        if (mHasFlipSettings) {
            if (mFlipSettingsView.getVisibility() == View.VISIBLE) {
                flipToSettings();
            } else {
                flipToNotifications();
            }
        }
    }

    public void partialFlip(float percent) {
        if (mFlipSettingsViewAnim != null) mFlipSettingsViewAnim.cancel();
        if (mPowerWidgetAnim != null) mPowerWidgetAnim.cancel();
        if (mScrollViewAnim != null) mScrollViewAnim.cancel();
        if (mQuickSettingsButtonAnim != null) mQuickSettingsButtonAnim.cancel();
        if (mNotificationButtonAnim != null) mNotificationButtonAnim.cancel();
        if (mClearButtonAnim != null) mClearButtonAnim.cancel();

        percent = Math.min(Math.max(percent, -1f), 1f);
        if (percent < 0f) { // notifications side
            mFlipSettingsView.setScaleX(0f);
            mFlipSettingsView.setVisibility(View.GONE);
            mQuickSettingsButton.setVisibility(View.VISIBLE);
            mQuickSettingsButton.setAlpha(-percent);
            mScrollView.setVisibility(View.VISIBLE);
            mScrollView.setScaleX(-percent);
            mNotificationButton.setVisibility(View.GONE);
            updateCarrierAndWifiLabelVisibility(true);

            if (mPowerWidget.powerWidgetEnabled()) {
                 mPowerWidget.setVisibility(View.VISIBLE);
                 mPowerWidget.setScaleX(-percent);
            } else {
                 mPowerWidget.setVisibility(View.GONE);
            }

        } else { // settings side
            mFlipSettingsView.setScaleX(percent);
            mFlipSettingsView.setVisibility(View.VISIBLE);
            mQuickSettingsButton.setVisibility(View.GONE);
            mScrollView.setVisibility(View.GONE);
            mScrollView.setScaleX(0f);
            mPowerWidget.setVisibility(View.GONE);
            mNotificationButton.setVisibility(View.VISIBLE);
            mNotificationButton.setAlpha(percent);
            updateCarrierAndWifiLabelVisibility(false);
        }
        mClearButton.setVisibility(View.GONE);
    }

    public void flipToSettings() {
        // Settings are not available in setup
        if (!mUserSetup) return;

        if (mHasFlipSettings) {
            mNotificationPanelIsOpen = false;
            mQSPanelIsOpen = true;
            if (mFlipSettingsViewAnim != null) mFlipSettingsViewAnim.cancel();
            if (mPowerWidgetAnim != null) mPowerWidgetAnim.cancel();
            if (mScrollViewAnim != null) mScrollViewAnim.cancel();
            if (mQuickSettingsButtonAnim != null) mQuickSettingsButtonAnim.cancel();
            if (mNotificationButtonAnim != null) mNotificationButtonAnim.cancel();
            if (mClearButtonAnim != null) mClearButtonAnim.cancel();

            final boolean halfWayDone = mFlipSettingsView.getVisibility() == View.VISIBLE;
            final int zeroOutDelays = halfWayDone ? 0 : 1;

            if (!halfWayDone) {
                mFlipSettingsView.setScaleX(0f);
                mScrollView.setScaleX(1f);
            }

            mFlipSettingsView.setVisibility(View.VISIBLE);
            mFlipSettingsViewAnim = start(
                startDelay(FLIP_DURATION_OUT * zeroOutDelays,
                    interpolator(mDecelerateInterpolator,
                        ObjectAnimator.ofFloat(mFlipSettingsView, View.SCALE_X, 1f)
                            .setDuration(FLIP_DURATION_IN)
                        )));
            mPowerWidgetAnim = start(
                setVisibilityWhenDone(
                    interpolator(mAccelerateInterpolator,
                            ObjectAnimator.ofFloat(mPowerWidget, View.SCALE_X, 0f)
                            )
                        .setDuration(FLIP_DURATION_OUT),
                        mPowerWidget, View.GONE,
                        mFlipSettingsView, View.VISIBLE));
            mScrollViewAnim = start(
                setVisibilityWhenDone(
                    interpolator(mAccelerateInterpolator,
                            ObjectAnimator.ofFloat(mScrollView, View.SCALE_X, 0f)
                            )
                        .setDuration(FLIP_DURATION_OUT),
                         mScrollView, View.INVISIBLE));
            mQuickSettingsButtonAnim = start(
                setVisibilityWhenDone(
                    ObjectAnimator.ofFloat(mQuickSettingsButton, View.ALPHA, 0f)
                        .setDuration(FLIP_DURATION),
                        mScrollView, View.INVISIBLE));
            mNotificationButton.setVisibility(View.VISIBLE);
            mNotificationButtonAnim = start(
                ObjectAnimator.ofFloat(mNotificationButton, View.ALPHA, 1f)
                    .setDuration(FLIP_DURATION));
            mClearButtonAnim = start(
                setVisibilityWhenDone(
                    ObjectAnimator.ofFloat(mClearButton, View.ALPHA, 0f)
                    .setDuration(FLIP_DURATION),
                    mClearButton, View.INVISIBLE));
            mNotificationPanel.postDelayed(new Runnable() {
                @Override
                public void run() {
                    updateCarrierAndWifiLabelVisibility(false);
                }
            }, FLIP_DURATION - 150);

            if (mNotificationShortcutsIsActive) {
                mNotificationPanel.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        updateNotificationShortcutsVisibility(false);
                    }
                }, FLIP_DURATION - 150);
            }
        }
    }

    public void flipPanels() {
        if (mHasFlipSettings) {
            if (mFlipSettingsView.getVisibility() != View.VISIBLE) {
                flipToSettings();
            } else {
                flipToNotifications();
            }
        }
    }

    public void animateCollapseQuickSettings() {
        mStatusBarView.collapseAllPanels(true);
    }

    void makeExpandedInvisibleSoon() {
        mHandler.postDelayed(new Runnable() { @Override
        public void run() { makeExpandedInvisible(); }}, 50);
    }

    void makeExpandedInvisible() {
        if (SPEW) Slog.d(TAG, "makeExpandedInvisible: mExpandedVisible=" + mExpandedVisible
                + " mExpandedVisible=" + mExpandedVisible);

        if (!mExpandedVisible) {
            return;
        }

        // Ensure the panel is fully collapsed (just in case; bug 6765842, 7260868)
        mStatusBarView.collapseAllPanels(/*animate=*/ false);

        if (mHasFlipSettings) {
            // reset things to their proper state
            if (mFlipSettingsViewAnim != null) mFlipSettingsViewAnim.cancel();
            if (mPowerWidgetAnim != null) mPowerWidgetAnim.cancel();
            if (mScrollViewAnim != null) mScrollViewAnim.cancel();
            if (mQuickSettingsButtonAnim != null) mQuickSettingsButtonAnim.cancel();
            if (mNotificationButtonAnim != null) mNotificationButtonAnim.cancel();
            if (mClearButtonAnim != null) mClearButtonAnim.cancel();

            mScrollView.setScaleX(1f);
            mScrollView.setVisibility(View.VISIBLE);
            mQuickSettingsButton.setAlpha(1f);
            mQuickSettingsButton.setVisibility(View.VISIBLE);
            mNotificationPanel.setVisibility(View.GONE);
            if (!mHideSettingsPanel)
                mFlipSettingsView.setVisibility(View.GONE);
            mNotificationButton.setVisibility(View.GONE);
            setAreThereNotifications(); // show the clear button

            if (mPowerWidget.powerWidgetEnabled()) {
                mPowerWidget.setScaleX(1f);
                mPowerWidget.setVisibility(View.VISIBLE);
            } else {
                mPowerWidget.setVisibility(View.GONE);
            }
        }

        mExpandedVisible = false;
        mPile.setLayoutTransitionsEnabled(false);
        if (mNavigationBarView != null)
            mNavigationBarView.setSlippery(false);
        visibilityChanged(false);

        // Shrink the window to the size of the status bar only
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) mStatusBarContainer.getLayoutParams();
        lp.height = getStatusBarHeight();
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        mWindowManager.updateViewLayout(mStatusBarContainer, lp);

        if ((mDisabled & StatusBarManager.DISABLE_NOTIFICATION_ICONS) == 0) {
            setNotificationIconVisibility(true, com.android.internal.R.anim.fade_in);
        }

        // Close any "App info" popups that might have snuck on-screen
        dismissPopups();
    }

    /**
     * Enables or disables layers on the children of the notifications pile.
     *
     * When layers are enabled, this method attempts to enable layers for the minimal
     * number of children. Only children visible when the notification area is fully
     * expanded will receive a layer. The technique used in this method might cause
     * more children than necessary to get a layer (at most one extra child with the
     * current UI.)
     *
     * @param layerType {@link View#LAYER_TYPE_NONE} or {@link View#LAYER_TYPE_HARDWARE}
     */
    private void setPileLayers(int layerType) {
        final int count = mPile.getChildCount();

        switch (layerType) {
            case View.LAYER_TYPE_NONE:
                for (int i = 0; i < count; i++) {
                    mPile.getChildAt(i).setLayerType(layerType, null);
                }
                break;
            case View.LAYER_TYPE_HARDWARE:
                final int[] location = new int[2];
                mNotificationPanel.getLocationInWindow(location);

                final int left = location[0];
                final int top = location[1];
                final int right = left + mNotificationPanel.getWidth();
                final int bottom = top + getExpandedViewMaxHeight();

                final Rect childBounds = new Rect();

                for (int i = 0; i < count; i++) {
                    final View view = mPile.getChildAt(i);
                    view.getLocationInWindow(location);

                    childBounds.set(location[0], location[1],
                            location[0] + view.getWidth(), location[1] + view.getHeight());

                    if (childBounds.intersects(left, top, right, bottom)) {
                        view.setLayerType(layerType, null);
                    }
                }

                break;
        }
    }

    public boolean isClinging() {
        return mCling != null && mCling.getVisibility() == View.VISIBLE;
    }

    public void hideCling() {
        if (isClinging()) {
            mCling.animate().alpha(0f).setDuration(250).start();
            mCling.setVisibility(View.GONE);
            mSuppressStatusBarDrags = false;
        }
    }

    public void showCling() {
        // lazily inflate this to accommodate orientation change
        final ViewStub stub = (ViewStub) mStatusBarWindow.findViewById(R.id.status_bar_cling_stub);
        if (stub == null) {
            mClingShown = true;
            return; // no clings on this device
        }

        mSuppressStatusBarDrags = true;

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mCling = (ViewGroup) stub.inflate();

                mCling.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        return true; // e eats everything
                    }});
                mCling.findViewById(R.id.ok).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        hideCling();
                    }});

                mCling.setAlpha(0f);
                mCling.setVisibility(View.VISIBLE);
                mCling.animate().alpha(1f);

                mClingShown = true;
                SharedPreferences.Editor editor = Prefs.edit(mContext);
                editor.putBoolean(Prefs.SHOWN_QUICK_SETTINGS_HELP, true);
                editor.apply();

                makeExpandedVisible(true); // enforce visibility in case the shade is still animating closed
                animateExpandNotificationsPanel();

                mSuppressStatusBarDrags = false;
            }
        }, 500);

        animateExpandNotificationsPanel();
    }

    private void adjustBrightness(int x) {
        float raw = ((float) x) / mScreenWidth;

        // Add a padding to the brightness control on both sides to
        // make it easier to reach min/max brightness
        float padded = Math.min(1.0f - BRIGHTNESS_CONTROL_PADDING,
                Math.max(BRIGHTNESS_CONTROL_PADDING, raw));
        float value = (padded - BRIGHTNESS_CONTROL_PADDING) /
                (1 - (2.0f * BRIGHTNESS_CONTROL_PADDING));

        int newBrightness = mMinBrightness + (int) Math.round(value *
                (android.os.PowerManager.BRIGHTNESS_ON - mMinBrightness));
        newBrightness = Math.min(newBrightness, android.os.PowerManager.BRIGHTNESS_ON);
        newBrightness = Math.max(newBrightness, mMinBrightness);

        try {
            IPowerManager power = IPowerManager.Stub.asInterface(
                    ServiceManager.getService("power"));
            if (power != null) {
                power.setTemporaryScreenBrightnessSettingOverride(newBrightness);
                Settings.System.putInt(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, newBrightness);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Setting Brightness failed: " + e);
        }
    }

    private void brightnessControl(MotionEvent event)
    {
        if (mBrightnessControl)
        {
            final int action = event.getAction();
            final int x = (int)event.getRawX();
            final int y = (int)event.getRawY();
            if (action == MotionEvent.ACTION_DOWN) {
                mLinger = 0;
                mInitialTouchX = x;
                mInitialTouchY = y;
                mHandler.removeCallbacks(mLongPressBrightnessChange);
                if (y < mNotificationHeaderHeight) {
                    mHandler.postDelayed(mLongPressBrightnessChange,
                            BRIGHTNESS_CONTROL_LONG_PRESS_TIMEOUT);
                }
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (y < mNotificationHeaderHeight) {
                    mVelocityTracker.computeCurrentVelocity(1000);
                    float yVel = mVelocityTracker.getYVelocity();
                    yVel = Math.abs(yVel);
                    if (yVel < 50.0f) {
                        if (mLinger > BRIGHTNESS_CONTROL_LINGER_THRESHOLD) {
                            adjustBrightness(x);
                        } else {
                            mLinger++;
                        }
                    }
                    int touchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
                    if (Math.abs(x - mInitialTouchX) > touchSlop ||
                            Math.abs(y - mInitialTouchY) > touchSlop) {
                        mHandler.removeCallbacks(mLongPressBrightnessChange);
                    }
                } else {
                    mHandler.removeCallbacks(mLongPressBrightnessChange);
                    // remove brightness events from being posted, change mode
                    if (mIsStatusBarBrightNess == 1) {

                        if (mIsBrightNessMode == 1) {
                            mIsBrightNessMode = 2;
                        }
                    }
              }
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                mHandler.removeCallbacks(mLongPressBrightnessChange);
                mLinger = 0;
            }
        }
    }

    public boolean interceptTouchEvent(MotionEvent event) {
        if (DEBUG_GESTURES) {
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                EventLog.writeEvent(EventLogTags.SYSUI_STATUSBAR_TOUCH,
                        event.getActionMasked(), (int) event.getX(), (int) event.getY(), mDisabled);
            }

        }

        if (SPEW) {
            Slog.d(TAG, "Touch: rawY=" + event.getRawY() + " event=" + event + " mDisabled="
                + mDisabled + " mTracking=" + mTracking);
        } else if (CHATTY) {
            if (event.getAction() != MotionEvent.ACTION_MOVE) {
                Slog.d(TAG, String.format(
                            "panel: %s at (%f, %f) mDisabled=0x%08x",
                            MotionEvent.actionToString(event.getAction()),
                            event.getRawX(), event.getRawY(), mDisabled));
            }
        }

        if (DEBUG_GESTURES) {
            mGestureRec.add(event);
        }

        brightnessControl(event);

        // Cling (first-run help) handling.
        // The cling is supposed to show the first time you drag, or even tap, the status bar.
        // It should show the notification panel, then fade in after half a second, giving you
        // an explanation of what just happened, as well as teach you how to access quick
        // settings (another drag). The user can dismiss the cling by clicking OK or by
        // dragging quick settings into view.
        final int act = event.getActionMasked();
        if (mSuppressStatusBarDrags) {
            return true;
        } else if (act == MotionEvent.ACTION_UP && !mClingShown) {
            showCling();
        } else {
            hideCling();
        }

        if (mIsStatusBarBrightNess == 1) {
             mIsBrightNessMode = 0;
             if (!mIsAutoBrightNess) {
                 mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_STATUSBAR_BRIGHTNESS),
                 ViewConfiguration.getGlobalActionKeyTimeout());
                 }
        }
        return false;
    }

    public GestureRecorder getGestureRecorder() {
        return mGestureRec;
    }

    @Override // CommandQueue
    public void setNavigationIconHints(int hints) {
        if (hints == mNavigationIconHints) return;

        mNavigationIconHints = hints;

        propagateNavigationIconHints(hints);
    }

    @Override // CommandQueue
    public void setSystemUiVisibility(int vis, int mask) {
        final int oldVal = mSystemUiVisibility;
        final int newVal = (oldVal&~mask) | (vis&mask);
        final int diff = newVal ^ oldVal;

        if (diff != 0) {
            mSystemUiVisibility = newVal;

            if (0 != (diff & View.SYSTEM_UI_FLAG_LOW_PROFILE)) {
                final boolean lightsOut = (0 != (vis & View.SYSTEM_UI_FLAG_LOW_PROFILE));
                if (lightsOut) {
                    animateCollapsePanels();
                    if (mTicking) {
                        haltTicker();
                    }
                }

                if (mNavigationBarView != null) {
                    mNavigationBarView.setLowProfile(lightsOut);
                }

                setStatusBarLowProfile(lightsOut);
            }

            notifyUiVisibilityChanged();
        }
    }

    private void setStatusBarLowProfile(boolean lightsOut) {
        if (mLightsOutAnimation == null) {
            final View notifications = mStatusBarView.findViewById(R.id.notification_icon_area);
            final View systemIcons = mStatusBarView.findViewById(R.id.statusIcons);
            final View signal = mStatusBarView.findViewById(R.id.signal_cluster);
            final View signal2 = mStatusBarView.findViewById(R.id.signal_cluster_text);
            final View battery = mStatusBarView.findViewById(R.id.battery);
            final View battery2 = mStatusBarView.findViewById(R.id.battery_text);
            final View battery3 = mStatusBarView.findViewById(R.id.circle_battery);
            final View clock = mStatusBarView.findViewById(R.id.clock);

            final AnimatorSet lightsOutAnim = new AnimatorSet();
            lightsOutAnim.playTogether(
                    ObjectAnimator.ofFloat(notifications, View.ALPHA, 0),
                    ObjectAnimator.ofFloat(systemIcons, View.ALPHA, 0),
                    ObjectAnimator.ofFloat(signal, View.ALPHA, 0),
                    ObjectAnimator.ofFloat(signal2, View.ALPHA, 0),
                    ObjectAnimator.ofFloat(battery, View.ALPHA, 0.5f),
                    ObjectAnimator.ofFloat(battery2, View.ALPHA, 0.5f),
                    ObjectAnimator.ofFloat(battery3, View.ALPHA, 0.5f),
                    ObjectAnimator.ofFloat(clock, View.ALPHA, 0.5f)
                );
            lightsOutAnim.setDuration(750);

            final AnimatorSet lightsOnAnim = new AnimatorSet();
            lightsOnAnim.playTogether(
                    ObjectAnimator.ofFloat(notifications, View.ALPHA, 1),
                    ObjectAnimator.ofFloat(systemIcons, View.ALPHA, 1),
                    ObjectAnimator.ofFloat(signal, View.ALPHA, 1),
                    ObjectAnimator.ofFloat(signal2, View.ALPHA, 1),
                    ObjectAnimator.ofFloat(battery, View.ALPHA, 1),
                    ObjectAnimator.ofFloat(battery2, View.ALPHA, 1),
                    ObjectAnimator.ofFloat(battery3, View.ALPHA, 1),
                    ObjectAnimator.ofFloat(clock, View.ALPHA, 1)
                );
            lightsOnAnim.setDuration(250);

            mLightsOutAnimation = lightsOutAnim;
            mLightsOnAnimation = lightsOnAnim;
        }

        mLightsOutAnimation.cancel();
        mLightsOnAnimation.cancel();

        final Animator a = lightsOut ? mLightsOutAnimation : mLightsOnAnimation;
        a.start();

        setAreThereNotifications();
    }

    private boolean areLightsOn() {
        return 0 == (mSystemUiVisibility & View.SYSTEM_UI_FLAG_LOW_PROFILE);
    }

    public void setLightsOn(boolean on) {
        Log.v(TAG, "setLightsOn(" + on + ")");
        if (on) {
            setSystemUiVisibility(0, View.SYSTEM_UI_FLAG_LOW_PROFILE);
        } else {
            setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE, View.SYSTEM_UI_FLAG_LOW_PROFILE);
        }
    }

    private void notifyUiVisibilityChanged() {
        try {
            mWindowManagerService.statusBarVisibilityChanged(mSystemUiVisibility);
        } catch (RemoteException ex) {
        }
    }

    @Override
    public void topAppWindowChanged(boolean showMenu) {
        mStatusBarView.updateBackgroundAlpha();
        if (DEBUG) {
            Slog.d(TAG, (showMenu?"showing":"hiding") + " the MENU button");
        }
        propagateMenuVisibility(showMenu);

        // hide pie triggers when keyguard is visible
        try {
            if (mWindowManagerService.isKeyguardLocked()) {
                disableTriggers(true);
            } else {
                disableTriggers(false);
            }
        } catch (RemoteException e) {
            // nothing else to do ...
        }

        // See above re: lights-out policy for legacy apps.
        if (showMenu) setLightsOn(true);
    }

    @Override
    public void setImeWindowStatus(IBinder token, int vis, int backDisposition) {
        boolean altBack = (backDisposition == InputMethodService.BACK_DISPOSITION_WILL_DISMISS)
            || ((vis & InputMethodService.IME_VISIBLE) != 0);

        mCommandQueue.setNavigationIconHints(
                altBack ? (mNavigationIconHints | StatusBarManager.NAVIGATION_HINT_BACK_ALT)
                        : (mNavigationIconHints & ~StatusBarManager.NAVIGATION_HINT_BACK_ALT));
        if (mQS != null) mQS.setImeWindowStatus(vis > 0);
    }

    @Override
    public void setHardKeyboardStatus(boolean available, boolean enabled) {}

    @Override
    protected void tick(IBinder key, StatusBarNotification n, boolean firstTime) {
        // no ticking in lights-out mode
        if (!areLightsOn()) return;

        // no ticking in Setup
        if (!isDeviceProvisioned()) return;

        // not for you
        if (!notificationIsForCurrentUser(n)) return;

        // Show the ticker if one is requested. Also don't do this
        // until status bar window is attached to the window manager,
        // because...  well, what's the point otherwise?  And trying to
        // run a ticker without being attached will crash!
        if (n.getNotification().tickerText != null && mStatusBarWindow.getWindowToken() != null) {
            if (0 == (mDisabled & (StatusBarManager.DISABLE_NOTIFICATION_ICONS
                            | StatusBarManager.DISABLE_NOTIFICATION_TICKER))) {
                mTicker.addEntry(n);
            }
        }
    }

    private class MyTicker extends Ticker {
        MyTicker(Context context, View sb) {
            super(context, sb);
        }

        @Override
        public void tickerStarting() {
            mTicking = true;
            mStatusBarContents.setVisibility(View.GONE);
            mCenterClockLayout.setVisibility(View.GONE);
            mTickerView.setVisibility(View.VISIBLE);
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_up_in, null));
            mStatusBarContents.startAnimation(loadAnim(com.android.internal.R.anim.push_up_out, null));
            mCenterClockLayout.startAnimation(loadAnim(com.android.internal.R.anim.push_up_out,
                    null));
        }

        @Override
        public void tickerDone() {
            mStatusBarContents.setVisibility(View.VISIBLE);
            mCenterClockLayout.setVisibility(View.VISIBLE);
            mTickerView.setVisibility(View.GONE);
            mStatusBarContents.startAnimation(loadAnim(com.android.internal.R.anim.push_down_in, null));
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_down_out,
                        mTickingDoneListener));
            mCenterClockLayout.startAnimation(loadAnim(com.android.internal.R.anim.push_down_in,
                    null));
        }

        @Override
        public void tickerHalting() {
            mStatusBarContents.setVisibility(View.VISIBLE);
            mCenterClockLayout.setVisibility(View.VISIBLE);
            mTickerView.setVisibility(View.GONE);
            mStatusBarContents.startAnimation(loadAnim(com.android.internal.R.anim.fade_in, null));
            mCenterClockLayout.startAnimation(loadAnim(com.android.internal.R.anim.fade_in, null));
            // we do not animate the ticker away at this point, just get rid of it (b/6992707)
        }
    }

    Animation.AnimationListener mTickingDoneListener = new Animation.AnimationListener() {;
        @Override
        public void onAnimationEnd(Animation animation) {
            mTicking = false;
        }
        @Override
        public void onAnimationRepeat(Animation animation) {
        }
        @Override
        public void onAnimationStart(Animation animation) {
        }
    };

    private Animation loadAnim(int id, Animation.AnimationListener listener) {
        Animation anim = AnimationUtils.loadAnimation(mContext, id);
        if (listener != null) {
            anim.setAnimationListener(listener);
        }
        return anim;
    }

    public static String viewInfo(View v) {
        return "[(" + v.getLeft() + "," + v.getTop() + ")(" + v.getRight() + "," + v.getBottom()
                + ") " + v.getWidth() + "x" + v.getHeight() + "]";
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mQueueLock) {
            pw.println("Current Status Bar state:");
            pw.println("  mExpandedVisible=" + mExpandedVisible
                    + ", mTrackingPosition=" + mTrackingPosition);
            pw.println("  mTicking=" + mTicking);
            pw.println("  mTracking=" + mTracking);
            pw.println("  mDisplayMetrics=" + mDisplayMetrics);
            pw.println("  mPile: " + viewInfo(mPile));
            pw.println("  mTickerView: " + viewInfo(mTickerView));
            pw.println("  mScrollView: " + viewInfo(mScrollView)
                    + " scroll " + mScrollView.getScrollX() + "," + mScrollView.getScrollY());
        }

        pw.println("  Panels: ");
        if (mNotificationPanel != null) {
            pw.println("    mNotificationPanel=" +
                mNotificationPanel + " params=" + mNotificationPanel.getLayoutParams().debug(""));
            pw.print  ("      ");
            mNotificationPanel.dump(fd, pw, args);
        }
        if (mSettingsPanel != null) {
            pw.println("    mSettingsPanel=" +
                mSettingsPanel + " params=" + mSettingsPanel.getLayoutParams().debug(""));
            pw.print  ("      ");
            mSettingsPanel.dump(fd, pw, args);
        }

        if (DUMPTRUCK) {
            synchronized (mNotificationData) {
                int N = mNotificationData.size();
                pw.println("  notification icons: " + N);
                for (int i=0; i<N; i++) {
                    NotificationData.Entry e = mNotificationData.get(i);
                    pw.println("    [" + i + "] key=" + e.key + " icon=" + e.icon);
                    StatusBarNotification n = e.notification;
                    pw.println("         pkg=" + n.getPackageName() + " id=" + n.getId() + " score=" + n.getScore());
                    pw.println("         notification=" + n.getNotification());
                    pw.println("         tickerText=\"" + n.getNotification().tickerText + "\"");
                }
            }

            int N = mStatusIcons.getChildCount();
            pw.println("  system icons: " + N);
            for (int i=0; i<N; i++) {
                StatusBarIconView ic = (StatusBarIconView) mStatusIcons.getChildAt(i);
                pw.println("    [" + i + "] icon=" + ic);
            }

            if (false) {
                pw.println("see the logcat for a dump of the views we have created.");
                // must happen on ui thread
                mHandler.post(new Runnable() {
                        public void run() {
                            mStatusBarView.getLocationOnScreen(mAbsPos);
                            Slog.d(TAG, "mStatusBarView: ----- (" + mAbsPos[0] + "," + mAbsPos[1]
                                    + ") " + mStatusBarView.getWidth() + "x"
                                    + getStatusBarHeight());
                            mStatusBarView.debug();
                        }
                    });
            }
        }

        if (DEBUG_GESTURES) {
            pw.print("  status bar gestures: ");
            mGestureRec.dump(fd, pw, args);
        }

        mNetworkController.dump(fd, pw, args);
    }

    @Override
    public void createAndAddWindows() {
        addStatusBarWindow();
    }

    private void addStatusBarWindow() {
        // Put up the view
        final int height = getStatusBarHeight();
        // Now that the status bar window encompasses the sliding panel and its
        // translucent backdrop, the entire thing is made TRANSLUCENT and is
        // hardware-accelerated.
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height,
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);

        lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        lp.gravity = getStatusBarGravity();
        lp.setTitle("StatusBar");
        lp.packageName = mContext.getPackageName();

        makeStatusBarView();
        mStatusBarContainer.addView(mStatusBarWindow);
        mWindowManager.addView(mStatusBarContainer, lp);
    }

    void setNotificationIconVisibility(boolean visible, int anim) {
        int old = mNotificationIcons.getVisibility();
        int v = visible ? View.VISIBLE : View.INVISIBLE;
        if (old != v) {
            mNotificationIcons.setVisibility(v);
            mNotificationIcons.startAnimation(loadAnim(anim, null));
        }
    }

    void updateExpandedInvisiblePosition() {
        mTrackingPosition = -mDisplayMetrics.heightPixels;
    }

    static final float saturate(float a) {
        return a < 0f ? 0f : (a > 1f ? 1f : a);
    }

    @Override
    protected int getExpandedViewMaxHeight() {
        return mDisplayMetrics.heightPixels - mNotificationPanelMarginBottomPx;
    }

    @Override
    protected boolean isNotificationPanelFullyVisible() {
        return mExpandedVisible && !isShowingSettings();
    }

    @Override
    public void updateExpandedViewPos(int thingy) {
        if (DEBUG) Slog.v(TAG, "updateExpandedViewPos");

        // on larger devices, the notification panel is propped open a bit
        mNotificationPanel.setMinimumHeight(
                (int)(mNotificationPanelMinHeightFrac * mCurrentDisplaySize.y));

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mNotificationPanel.getLayoutParams();
        lp.gravity = mNotificationPanelGravity;
        lp.setMarginStart(mNotificationPanelMarginPx);
        mNotificationPanel.setLayoutParams(lp);

        if (mSettingsPanel != null) {
            lp = (FrameLayout.LayoutParams) mSettingsPanel.getLayoutParams();
            lp.gravity = mSettingsPanelGravity;
            lp.setMarginEnd(mNotificationPanelMarginPx);
            mSettingsPanel.setLayoutParams(lp);
        }

        updateCarrierAndWifiLabelVisibility(false);
    }

    // called by makeStatusbar and also by PhoneStatusBarView
    void updateDisplaySize() {
        mDisplay.getMetrics(mDisplayMetrics);
        if (DEBUG_GESTURES) {
            mGestureRec.tag("display",
                    String.format("%dx%d", mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels));
        }
    }

    private final View.OnClickListener mClearButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            synchronized (mNotificationData) {
                // animate-swipe all dismissable notifications
                int numChildren = mPile.getChildCount();

                int scrollTop = mScrollView.getScrollY();
                int scrollBottom = scrollTop + mScrollView.getHeight();
                final ArrayList<View> snapshot = new ArrayList<View>(numChildren);
                for (int i=0; i<numChildren; i++) {
                    final View child = mPile.getChildAt(i);
                    if (mPile.canChildBeDismissed(child) && child.getBottom() > scrollTop &&
                            child.getTop() < scrollBottom) {
                        snapshot.add(child);
                    }
                }

                if (snapshot.isEmpty()) {
                    animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
                    return;
                }

                // Decrease the delay for every row we animate to give the sense of
                // accelerating the swipes
                final int ROW_DELAY_DECREMENT = 10;
                int currentDelay = 140;
                int totalDelay = 0;

                // Set the shade-animating state to avoid doing other work, in
                // particular layout and redrawing, during all of these animations.
                mPile.setViewRemoval(false);

                View sampleView = snapshot.get(0);
                int width = sampleView.getWidth();
                final int dir = sampleView.isLayoutRtl() ? -1 : +1;
                final int velocity = dir * width * 8; // 1000/8 = 125 ms duration
                for (final View _v : snapshot) {
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mPile.dismissRowAnimated(_v, velocity);
                        }
                    }, totalDelay);
                    currentDelay = Math.max(50, currentDelay - ROW_DELAY_DECREMENT);
                    totalDelay += currentDelay;
                }

                // After ending all animations, tell the service to remove the
                // notifications, which will trigger collapsing the shade
                final View lastEntry = snapshot.get(snapshot.size() - 1);
                mPile.runOnDismiss(lastEntry, mNotifyClearAll);
            }
        }
    };

    public void startActivityDismissingKeyguard(Intent intent, boolean onlyProvisioned) {
        if (onlyProvisioned && !isDeviceProvisioned()) return;
        try {
            // Dismiss the lock screen when Settings starts.
            ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
        } catch (RemoteException e) {
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
        animateCollapsePanels();
    }

    private final View.OnClickListener mQuickSettingsButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (mHasSettingsPanel) {
                animateExpandSettingsPanel(true);
            } else {
                startActivityDismissingKeyguard(
                        new Intent(android.provider.Settings.ACTION_SETTINGS), true);
            }
        }
    };

    private final View.OnClickListener mSettingsButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            startActivityDismissingKeyguard(
                    new Intent(android.provider.Settings.ACTION_SETTINGS), true);
        }
    };

    private View.OnLongClickListener mSettingsLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            if (mPowerWidget.getVisibility() == View.GONE) {
                int height = mPowerWidget.getHeight();
                Animation anim = AnimationUtils.makeInAnimation(mContext, true);
                anim.setDuration(500);
                anim.setAnimationListener(new AnimationListener() {
                    @Override
                    public void onAnimationStart(Animation animation) {
                        mPowerWidget.setVisibility(View.VISIBLE);
                        Settings.System.putInt(mContext.getContentResolver(),
                                Settings.System.EXPANDED_VIEW_WIDGET, 1);
                    }
                    //stupid android wont compile empty methods so I have to override them to work.... better make them public too!
                    @Override
                    public void onAnimationEnd(Animation animation) {
                    }
                    @Override
                    public void onAnimationRepeat(Animation animation) {
                    }
                });
                mPowerWidget.startAnimation(anim);
            } else {
                int height = mPowerWidget.getHeight();
                Animation anim = AnimationUtils.makeOutAnimation(mContext, false);
                anim.setDuration(500);
                anim.setAnimationListener(new AnimationListener() {
                     @Override
                     public void onAnimationEnd(Animation animation) {
                         mPowerWidget.setVisibility(View.GONE);
                         Settings.System.putInt(mContext.getContentResolver(),
                                Settings.System.EXPANDED_VIEW_WIDGET, 0);
                     }
                     //stupid android wont compile empty methods so I have to override them to work....
                     @Override
                     public void onAnimationStart(Animation animation) {
                     }
                     @Override
                     public void onAnimationRepeat(Animation animation) {
                     }
                });
                mPowerWidget.startAnimation(anim);
            }
            return true;
        }
    };

    private final View.OnClickListener mClockClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            startActivityDismissingKeyguard(
                    new Intent(Intent.ACTION_QUICK_CLOCK), true); // have fun, everyone
        }
    };

    private final View.OnClickListener mNotificationButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            animateExpandNotificationsPanel();
        }
    };

    private View.OnLongClickListener mNotificationLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            try {
                ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
            } catch (RemoteException e) {
            }
            try {
                animateCollapsePanels();
                Intent i = new Intent("com.android.settings.slim.quicksettings.QuickSettings");
                i.addCategory(Intent.CATEGORY_DEFAULT);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                v.getContext().startActivity(i);
            } catch (Exception e) {
            }
            return true;
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Slog.v(TAG, "onReceive: " + intent);
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                int flags = CommandQueue.FLAG_EXCLUDE_NONE;
                String reason = intent.getStringExtra("reason");
                if (reason != null && reason.equals(SYSTEM_DIALOG_REASON_RECENT_APPS)) {
                    flags |= CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL;
                }
                animateCollapsePanels(flags);
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                // no waiting!
                makeExpandedInvisible();
                notifyNavigationBarScreenOn(false);
            } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                if (DEBUG) {
                    Slog.v(TAG, "configuration changed: " + mContext.getResources().getConfiguration());
                }
                Configuration config = mContext.getResources().getConfiguration();
                mDisplay.getSize(mCurrentDisplaySize);

                updateResources();
                repositionNavigationBar();
                updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
                updateSwapXY();
                updateShowSearchHoldoff();
                try {
                    // position app sidebar on left if in landscape orientation and device has a navbar
                    if (mWindowManagerService.hasNavigationBar() &&
                            config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        mWindowManager.updateViewLayout(mAppSidebar,
                                getAppSidebarLayoutParams(AppSidebar.SIDEBAR_POSITION_LEFT));
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                mAppSidebar.setPosition(AppSidebar.SIDEBAR_POSITION_LEFT);
                            }
                        }, 500);
                    }
                } catch (RemoteException e) {
                }
             } 
             else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                // work around problem where mDisplay.getRotation() is not stable while screen is off (bug 7086018)
                repositionNavigationBar();
                notifyNavigationBarScreenOn(true);
            }
        }
    };

    private void updateSwapXY() {
        if (mNavigationBarView != null
            && mNavigationBarView.mDelegateHelper != null) {
                if (Settings.System.getInt(mContext.getContentResolver(),
                            Settings.System.NAVIGATION_BAR_CAN_MOVE, 1) == 1) {
                    // if we are in landscape mode and NavBar can move swap the XY coordinates for NaVRing Swipe
                    mNavigationBarView.mDelegateHelper.setSwapXY((
                            mContext.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE));
                } else {
                    mNavigationBarView.mDelegateHelper.setSwapXY(false);
                }
        }
    }

    @Override
    public void userSwitched(int newUserId) {
        if (MULTIUSER_DEBUG) mNotificationPanelDebugText.setText("USER " + newUserId);
        animateCollapsePanels();
        updateNotificationIcons();
        resetUserSetupObserver();
    }

    private void resetUserSetupObserver() {
        mContext.getContentResolver().unregisterContentObserver(mUserSetupObserver);
        mUserSetupObserver.onChange(false);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE), true,
                mUserSetupObserver,
                mCurrentUserId);
    }

    private void setIntruderAlertVisibility(boolean vis) {
        if (!ENABLE_INTRUDERS) return;
        if (DEBUG) {
            Slog.v(TAG, (vis ? "showing" : "hiding") + " intruder alert window");
        }
        mIntruderAlertView.setVisibility(vis ? View.VISIBLE : View.GONE);
    }

    @Override
    public void dismissIntruder() {
        if (mCurrentlyIntrudingNotification == null) return;

        try {
            mBarService.onNotificationClear(
                    mCurrentlyIntrudingNotification.getPackageName(),
                    mCurrentlyIntrudingNotification.getTag(),
                    mCurrentlyIntrudingNotification.getId());
        } catch (android.os.RemoteException ex) {
            // oh well
        }
    }

    private static void copyNotifications(ArrayList<Pair<IBinder, StatusBarNotification>> dest,
            NotificationData source) {
        int N = source.size();
        for (int i = 0; i < N; i++) {
            NotificationData.Entry entry = source.get(i);
            dest.add(Pair.create(entry.key, entry.notification));
        }
    }

    private void recreateStatusBar() {
        mRecreating = true;
        mStatusBarContainer.removeAllViews();

        // extract icons from the soon-to-be recreated viewgroup.
        int nIcons = mStatusIcons.getChildCount();
        ArrayList<StatusBarIcon> icons = new ArrayList<StatusBarIcon>(nIcons);
        ArrayList<String> iconSlots = new ArrayList<String>(nIcons);
        for (int i = 0; i < nIcons; i++) {
            StatusBarIconView iconView = (StatusBarIconView)mStatusIcons.getChildAt(i);
            icons.add(iconView.getStatusBarIcon());
            iconSlots.add(iconView.getStatusBarSlot());
        }

        // extract notifications.
        int nNotifs = mNotificationData.size();
        ArrayList<Pair<IBinder, StatusBarNotification>> notifications =
                new ArrayList<Pair<IBinder, StatusBarNotification>>(nNotifs);
        copyNotifications(notifications, mNotificationData);
        mNotificationData.clear();

        makeStatusBarView();

        if (mNavigationBarView != null) {
            // recreate and reposition navigationbar
            mNavigationBarView.recreateNavigationBar();
            repositionNavigationBar();
        }

        // recreate pie navigation
        recreatePie();

        // recreate StatusBarIconViews.
        for (int i = 0; i < nIcons; i++) {
            StatusBarIcon icon = icons.get(i);
            String slot = iconSlots.get(i);
            addIcon(slot, i, i, icon);
        }

        // recreate notifications.
        for (int i = 0; i < nNotifs; i++) {
            Pair<IBinder, StatusBarNotification> notifData = notifications.get(i);
            addNotificationViews(notifData.first, notifData.second);
        }

        setAreThereNotifications();

        mStatusBarContainer.addView(mStatusBarWindow);

        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
        mNotificationShortcutsLayout.recreateShortcutLayout();
        mRecreating = false;
    }

    /**
     * Reload some of our resources when the configuration changes.
     *
     * We don't reload everything when the configuration changes -- we probably
     * should, but getting that smooth is tough.  Someday we'll fix that.  In the
     * meantime, just update the things that we know change.
     */
    void updateResources() {
        final Context context = mContext;
        final Resources res = context.getResources();

        // detect inverted ui mode change
        int uiInvertedMode =
            mContext.getResources().getConfiguration().uiInvertedMode;

        // detect theme change.
        CustomTheme newTheme = res.getConfiguration().customTheme;
        if ((newTheme != null &&
                (mCurrentTheme == null || !mCurrentTheme.equals(newTheme)))
            || uiInvertedMode != mCurrUiInvertedMode) {
            if (uiInvertedMode != mCurrUiInvertedMode) {
                mCurrUiInvertedMode = uiInvertedMode;
            } else {
                mCurrentTheme = (CustomTheme) newTheme.clone();
            }
            recreateStatusBar();
        } else {

            if (mClearButton instanceof TextView) {
                ((TextView)mClearButton).setText(context.getText(R.string.status_bar_clear_all_button));
            }
            loadDimens();
        }

        // Update the QuickSettings container
        if (mSettingsContainer != null) mSettingsContainer.updateResources();

    }

    protected void loadDimens() {
        final Resources res = mContext.getResources();

        mNaturalBarHeight = res.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);

        int newIconSize = res.getDimensionPixelSize(
            com.android.internal.R.dimen.status_bar_icon_size);
        int newIconHPadding = res.getDimensionPixelSize(
            R.dimen.status_bar_icon_padding);

        if (newIconHPadding != mIconHPadding || newIconSize != mIconSize) {
//            Slog.d(TAG, "size=" + newIconSize + " padding=" + newIconHPadding);
            mIconHPadding = newIconHPadding;
            mIconSize = newIconSize;
            //reloadAllNotificationIcons(); // reload the tray
        }

        mEdgeBorder = res.getDimensionPixelSize(R.dimen.status_bar_edge_ignore);

        mSelfExpandVelocityPx = res.getDimension(R.dimen.self_expand_velocity);
        mSelfCollapseVelocityPx = res.getDimension(R.dimen.self_collapse_velocity);
        mFlingExpandMinVelocityPx = res.getDimension(R.dimen.fling_expand_min_velocity);
        mFlingCollapseMinVelocityPx = res.getDimension(R.dimen.fling_collapse_min_velocity);

        mCollapseMinDisplayFraction = res.getFraction(R.dimen.collapse_min_display_fraction, 1, 1);
        mExpandMinDisplayFraction = res.getFraction(R.dimen.expand_min_display_fraction, 1, 1);

        mExpandAccelPx = res.getDimension(R.dimen.expand_accel);
        mCollapseAccelPx = res.getDimension(R.dimen.collapse_accel);

        mFlingGestureMaxXVelocityPx = res.getDimension(R.dimen.fling_gesture_max_x_velocity);

        mFlingGestureMaxOutputVelocityPx = res.getDimension(R.dimen.fling_gesture_max_output_velocity);

        mNotificationPanelMarginBottomPx
            = (int) res.getDimension(R.dimen.notification_panel_margin_bottom);
        mNotificationPanelMarginPx
            = (int) res.getDimension(R.dimen.notification_panel_margin_left);
        mNotificationPanelGravity = res.getInteger(R.integer.notification_panel_layout_gravity);
        if (mNotificationPanelGravity <= 0) {
            mNotificationPanelGravity = Gravity.START | Gravity.TOP;
        }
        mSettingsPanelGravity = res.getInteger(R.integer.settings_panel_layout_gravity);
        Log.d(TAG, "mSettingsPanelGravity = " + mSettingsPanelGravity);
        if (mSettingsPanelGravity <= 0) {
            mSettingsPanelGravity = Gravity.END | Gravity.TOP;
        }

        mCarrierAndWifiViewHeight = res.getDimensionPixelSize(R.dimen.carrier_label_height);
        mNotificationHeaderHeight = res.getDimensionPixelSize(R.dimen.notification_panel_header_height);

        mNotificationPanelMinHeightFrac = res.getFraction(R.dimen.notification_panel_min_height_frac, 1, 1);
        if (mNotificationPanelMinHeightFrac < 0f || mNotificationPanelMinHeightFrac > 1f) {
            mNotificationPanelMinHeightFrac = 0f;
        }

        if (false) Slog.v(TAG, "updateResources");
    }

    //
    // tracing
    //

    void postStartTracing() {
        mHandler.postDelayed(mStartTracing, 3000);
    }

    void customButtonVibrate() {
        final boolean hapticsDisabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) == 0;
        if (!hapticsDisabled) {
            android.os.Vibrator vib = (android.os.Vibrator)mContext.getSystemService(
                    Context.VIBRATOR_SERVICE);
            vib.vibrate(30);
        }
    }

    void vibrate() {
        android.os.Vibrator vib = (android.os.Vibrator)mContext.getSystemService(
                Context.VIBRATOR_SERVICE);
        vib.vibrate(250);
    }

    Runnable mStartTracing = new Runnable() {
        @Override
        public void run() {
            vibrate();
            SystemClock.sleep(250);
            Slog.d(TAG, "startTracing");
            android.os.Debug.startMethodTracing("/data/statusbar-traces/trace");
            mHandler.postDelayed(mStopTracing, 10000);
        }
    };

    Runnable mStopTracing = new Runnable() {
        @Override
        public void run() {
            android.os.Debug.stopMethodTracing();
            Slog.d(TAG, "stopTracing");
            vibrate();
        }
    };

    @Override
    protected void haltTicker() {
        mTicker.halt();
    }

    @Override
    protected boolean shouldDisableNavbarGestures() {
        return !isDeviceProvisioned()
                || mExpandedVisible
                || (mDisabled & StatusBarManager.DISABLE_SEARCH) != 0;
    }

    private static class FastColorDrawable extends Drawable {
        private final int mColor;

        public FastColorDrawable(int color) {
            mColor = 0xff000000 | color;
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawColor(mColor, PorterDuff.Mode.SRC);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }

        @Override
        public void setBounds(int left, int top, int right, int bottom) {
        }

        @Override
        public void setBounds(Rect bounds) {
        }
    }

    /**
     * ContentObserver to watch for Quick Settings tiles changes and notification wallpaper/alpha
     * @author dvtonder
     * @author kufikugel
     */
    private class TilesChangedObserver extends ContentObserver {
        public TilesChangedObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            boolean hideSettingsPanel = Settings.System.getInt(mContext.getContentResolver(),
                                    Settings.System.QS_DISABLE_PANEL, 0) == 1;

            if (hideSettingsPanel != mHideSettingsPanel) {
                recreateStatusBar();
            }

            setNotificationWallpaperHelper();
            setNotificationAlphaHelper();

            if (mSettingsContainer != null) {
                // Refresh the container
                mSettingsContainer.removeAllViews();
                mQS.setupQuickSettings();
                mSettingsContainer.updateResources();
            }

            String navBarConfig = Settings.System.getStringForUser(mContext.getContentResolver(),
                                    Settings.System.SYSTEMUI_NAVBAR_CONFIG,
                                    UserHandle.USER_CURRENT);

            if (navBarConfig != null  && mNavigationBarView != null
                    && !mOldNavBarConfig.equals(navBarConfig)) {
                mDisableHomeLongpress = false;
                ArrayList<ButtonConfig> buttonsConfig =
                    ButtonsHelper.getNavBarConfig(mContext);
                ButtonConfig buttonConfig;
                for (int j = 0; j < buttonsConfig.size(); j++) {
                    buttonConfig = buttonsConfig.get(j);
                    if (buttonConfig.getClickAction().equals(ButtonsConstants.ACTION_HOME)
                            && !buttonConfig.getLongpressAction().equals(ButtonsConstants.ACTION_NULL)) {
                        mDisableHomeLongpress = true;
                    }
                }
                // recreate navigationbar
                mNavigationBarView.recreateNavigationBar();
            }
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_DISABLE_PANEL),
                    false, this);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QUICK_SETTINGS_TILES),
                    false, this);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_DYNAMIC_ALARM),
                    false, this);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_DYNAMIC_BUGREPORT),
                    false, this);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_DYNAMIC_IME),
                    false, this);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_DYNAMIC_USBTETHER),
                    false, this);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_DYNAMIC_WIFI),
                    false, this);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QUICK_TILES_PER_ROW),
                    false, this);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QUICK_TILES_PER_ROW_DUPLICATE_LANDSCAPE),
                    false, this);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QUICK_TILES_BG_COLOR),
                    false, this);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QUICK_TILES_BG_PRESSED_COLOR),
                    false, this);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QUICK_TILES_TEXT_COLOR),
                    false, this);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NOTIF_WALLPAPER_ALPHA),
                    false, this);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NOTIF_ALPHA),
                    false, this);

            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SYSTEMUI_NAVBAR_CONFIG),
                    false, this);
        }
    }

    private void setNotificationWallpaperHelper() {
        float wallpaperAlpha = Settings.System.getFloat(mContext.getContentResolver(), Settings.System.NOTIF_WALLPAPER_ALPHA, 0.1f);
        String notifiBack = Settings.System.getString(mContext.getContentResolver(), Settings.System.NOTIFICATION_BACKGROUND);
        File file = new File(NOTIF_WALLPAPER_IMAGE_PATH);
        mNotificationPanel.setBackgroundResource(0);
        mNotificationPanel.setBackgroundResource(R.drawable.notification_panel_bg);
        Drawable background = mNotificationPanel.getBackground();
        background.setAlpha(0);
        if (!file.exists()) {
            if (notifiBack != null && !notifiBack.isEmpty()) {
                background.setColorFilter(Integer.parseInt(notifiBack), Mode.SRC_ATOP);
            }
         background.setAlpha((int) ((1-wallpaperAlpha) * 255));
        }
    }

    private void setNotificationAlphaHelper() {
        float notifAlpha = Settings.System.getFloat(mContext.getContentResolver(), Settings.System.NOTIF_ALPHA, 0.0f);
        if (mPile != null) {
            int N = mNotificationData.size();
            for (int i=0; i<N; i++) {
              Entry ent = mNotificationData.get(N-i-1);
              View expanded = ent.expanded;
              if (expanded !=null && expanded.getBackground()!=null) expanded.getBackground().setAlpha((int) ((1-notifAlpha) * 255));
              View large = ent.getLargeView();
              if (large != null && large.getBackground()!=null) large.getBackground().setAlpha((int) ((1-notifAlpha) * 255));
            }
        }
    }

    private boolean checkAutoBrightNess() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                mCurrentUserId) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    }

    private void updatePropFactorValue() {
        mPropFactor = Float.valueOf((float) android.os.PowerManager.BRIGHTNESS_ON
                / Integer.valueOf(mDisplay.getWidth()).floatValue());
    }

    private void doBrightNess(MotionEvent e) {
        int screenBrightness;
        try {
            screenBrightness = checkMinMax(Float.valueOf((e.getRawX() * mPropFactor.floatValue()))
                    .intValue());
            Settings.System.putInt(mContext.getContentResolver(), "screen_brightness",
                    screenBrightness);
        } catch (NullPointerException e2) {
            return;
        }
        double percent = ((screenBrightness / (double) 255) * 100) + 0.5;

    }

    private int checkMinMax(int brightness) {
        int min = 0;
        int max = 255;

        if (min > brightness) // brightness < 0x1E
            return min;
        else if (max < brightness) { // brightness > 0xFF
            return max;
        }

        return brightness;
    }

}
