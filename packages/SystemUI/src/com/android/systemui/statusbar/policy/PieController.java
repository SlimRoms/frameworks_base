/*
 * Copyright (C) 2013 The CyanogenMod Project (Jens Doll)
 * This code is loosely based on portions of the ParanoidAndroid Project source, Copyright (C) 2012.
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
package com.android.systemui.statusbar.policy;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ActivityOptions;
import android.app.SearchManager;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff.Mode;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Slog;
import android.view.HapticFeedbackConstants;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.List;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.pie.PieItem;
import com.android.systemui.statusbar.pie.PieLayout;
import com.android.systemui.statusbar.pie.PieLayout.PieDrawable;
import com.android.systemui.statusbar.pie.PieLayout.PieSlice;
import com.android.systemui.statusbar.pie.PieSliceContainer;
import com.android.systemui.statusbar.pie.PieSysInfo;

/**
 * Controller class for the default pie control.
 * <p>
 * This class is responsible for setting up the pie control, activating it, and defining and
 * executing the actions that can be triggered by the pie control.
 */
public class PieController implements BaseStatusBar.NavigationBarCallback,
        PieLayout.OnSnapListener, PieItem.PieOnClickListener, PieItem.PieOnLongClickListener {
    private static final String TAG = "PieController";
    private static final boolean DEBUG = false;

    private final static String ACTION_HOME = "**home**";
    private final static String ACTION_BACK = "**back**";
    private final static String ACTION_SEARCH = "**search**";
    private final static String ACTION_MENU = "**menu**";
    private final static String ACTION_POWER = "**power**";
    private final static String ACTION_NOTIFICATIONS = "**notifications**";
    private final static String ACTION_RECENTS = "**recents**";
    private final static String ACTION_SCREENSHOT = "**screenshot**";
    private final static String ACTION_IME = "**ime**";
    private final static String ACTION_LAST_APP = "**lastapp**";
    private final static String ACTION_KILL = "**kill**";
    private final static String ACTION_WIDGETS = "**widgets**";
    private final static String ACTION_NULL = "**null**";

    private int mNumberOfButtons = 3;
    private String[] mClickActions = new String[5];
    private String[] mLongpressActions = new String[5];
    private String[] mPortraitIcons = new String[5];

    private final static String[] StockClickActions = {
        "**back**",
        "**home**",
        "**recents**",
        "**null**",
        "**null**"
    };

    private final static String[] StockLongpress = {
        "**null**",
        "**null**",
        "**null**",
        "**null**",
        "**null**"
    };

    public static final float EMPTY_ANGLE = 10;
    public static final float START_ANGLE = 180 + EMPTY_ANGLE;

    private static final int MSG_INJECT_KEY_DOWN = 1066;
    private static final int MSG_INJECT_KEY_UP = 1067;

    private Context mContext;
    private PieLayout mPieContainer;
    /**
     * This is only needed for #toggleRecentApps()
     */
    private BaseStatusBar mStatusBar;
    private Vibrator mVibrator;
    private IWindowManager mWm;
    private int mBatteryLevel;
    private int mBatteryStatus;
    private boolean mHasTelephony;
    private ServiceState mServiceState;
    private ActivityManager mActivityManager;
    private IStatusBarService mBarService;

    private final Object mScreenshotLock = new Object();
    private ServiceConnection mScreenshotConnection = null;

    // all pie slices that are managed by the controller
    private PieSliceContainer mNavigationSlice;
    private PieSysInfo mSysInfo;

    private int mNavigationIconHints = 0;
    private int mDisabledFlags = 0;
    private Drawable mBackIcon;
    private Drawable mBackAltIcon;

    /**
     * Defines the positions in which pie controls may appear. This enumeration is used to store
     * an index, a flag and the android gravity for each position.
     */
    public enum Position {
        LEFT(0, 0, android.view.Gravity.LEFT),
        BOTTOM(1, 1, android.view.Gravity.BOTTOM),
        RIGHT(2, 1, android.view.Gravity.RIGHT),
        TOP(3, 0, android.view.Gravity.TOP);

        Position(int index, int factor, int android_gravity) {
            INDEX = index;
            FLAG = (0x01<<index);
            ANDROID_GRAVITY = android_gravity;
            FACTOR = factor;
        }

        public final int INDEX;
        public final int FLAG;
        public final int ANDROID_GRAVITY;
        /**
         * This is 1 when the position is not at the axis (like {@link Position.RIGHT} is
         * at {@code Layout.getWidth()} not at {@code 0}).
         */
        public final int FACTOR;
    }

    private Position mPosition;

    public static class Tracker {
        public static float sDistance;
        private float initialX = 0;
        private float initialY = 0;
        private float gracePeriod = 0;

        private Tracker(Position position) {
            this.position = position;
        }

        public void start(MotionEvent event) {
            initialX = event.getX();
            initialY = event.getY();
            switch (position) {
                case LEFT:
                    gracePeriod = initialX + sDistance / 3.0f;
                    break;
                case RIGHT:
                    gracePeriod = initialX - sDistance / 3.0f;
                    break;
            }
            active = true;
        }

        public boolean move(MotionEvent event) {
            final float x = event.getX();
            final float y = event.getY();
            if (!active) {
                return false;
            }

            // Unroll the complete logic here - we want to be fast and out of the
            // event chain as fast as possible.
            boolean loaded = false;
            switch (position) {
                case LEFT:
                    if (x < gracePeriod) {
                        initialY = y;
                    }
                    if (initialY - y < sDistance && y - initialY < sDistance) {
                        if (x - initialX <= sDistance) {
                            return false;
                        }
                        loaded = true;
                    }
                    break;
                case BOTTOM:
                    if (initialX - x < sDistance && x - initialX < sDistance) {
                        if (initialY - y <= sDistance) {
                            return false;
                        }
                        loaded = true;
                    }
                    break;
                case TOP:
                    if (initialX - x < sDistance && x - initialX < sDistance) {
                        if (y - initialY <= sDistance) {
                            return false;
                        }
                        loaded = true;
                    }
                    break;
                case RIGHT:
                    if (x > gracePeriod) {
                        initialY = y;
                    }
                    if (initialY - y < sDistance && y - initialY < sDistance) {
                        if (initialX - x <= sDistance) {
                            return false;
                        }
                        loaded = true;
                    }
                    break;
            }
            active = false;
            return loaded;
        }

        public boolean active = false;
        public final Position position;
    }

    public Tracker buildTracker(Position position) {
        return new Tracker(position);
    }

    private class H extends Handler {
        public void handleMessage(Message m) {
            final InputManager inputManager = InputManager.getInstance();
            switch (m.what) {
                case MSG_INJECT_KEY_DOWN:
                    inputManager.injectInputEvent((KeyEvent) m.obj,
                            InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
                    break;
                case MSG_INJECT_KEY_UP:
                    inputManager.injectInputEvent((KeyEvent) m.obj,
                            InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
                    break;
            }
        }
    }
    private H mHandler = new H();

    private void injectKeyDelayed(int keyCode, long when) {
        mHandler.removeMessages(MSG_INJECT_KEY_DOWN);
        mHandler.removeMessages(MSG_INJECT_KEY_UP);

        KeyEvent down = new KeyEvent(when, when + 10, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = new KeyEvent(when, when + 30, KeyEvent.ACTION_UP, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);

        mHandler.sendMessageDelayed(Message.obtain(mHandler, MSG_INJECT_KEY_DOWN, down), 10);
        mHandler.sendMessageDelayed(Message.obtain(mHandler, MSG_INJECT_KEY_UP, up), 30);
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_BUTTON_COLOR), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_BUTTON_PRESSED_COLOR), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_BUTTON_OUTLINE_COLOR), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_ICON_COLOR), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_ICON_COLOR_MODE), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_BUTTON_ALPHA), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_BUTTON_PRESSED_ALPHA), false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.PIE_LONG_PRESS_ENABLE),
                    false,
                    this);
            for (int j = 0; j < 5; j++) { // watch all 5 settings for changes.
                resolver.registerContentObserver(
                        Settings.System.getUriFor(Settings.System.PIE_CUSTOM_ACTIVITIES[j]),
                        false,
                        this);
                resolver.registerContentObserver(
                        Settings.System
                                .getUriFor(Settings.System.PIE_LONGPRESS_ACTIVITIES[j]),
                        false,
                        this);
                resolver.registerContentObserver(
                        Settings.System.getUriFor(Settings.System.PIE_CUSTOM_ICONS[j]),
                        false,
                        this);
            }
        }

        @Override
        public void onChange(boolean selfChange) {
            setupNavigationItems();
        }
    }

    private SettingsObserver mSettingsObserver = new SettingsObserver(mHandler);

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                mBatteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                mBatteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                         BatteryManager.BATTERY_STATUS_UNKNOWN);
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                // Give up on screen off. what's the point in pie controls if you don't see them?
                if (mPieContainer != null) {
                    mPieContainer.exit();
                }
            }
        }
    };

    private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            mServiceState = serviceState;
        }
    };

    public PieController(Context context) {
        mContext = context;

        mActivityManager = (ActivityManager) mContext.getSystemService(Activity.ACTIVITY_SERVICE);
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        mWm = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));

        final PackageManager pm = mContext.getPackageManager();
        mHasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY);

        Tracker.sDistance = mContext.getResources().getDimensionPixelSize(R.dimen.pie_trigger_distance);
    }

    public void attachTo(BaseStatusBar statusBar) {
        mStatusBar = statusBar;
    }

    public void attachTo(PieLayout container) {
        mPieContainer = container;
        mPieContainer.clearSlices();

        if (DEBUG) {
            Slog.d(TAG, "Attaching to container: " + container);
        }

        mPieContainer.setOnSnapListener(this);

        final Resources res = mContext.getResources();

        // construct navbar slice
        int inner = res.getDimensionPixelSize(R.dimen.pie_navbar_radius);
        int outer = inner + res.getDimensionPixelSize(R.dimen.pie_navbar_height);
        mNavigationSlice = new PieSliceContainer(mPieContainer, PieSlice.IMPORTANT
                | PieDrawable.DISPLAY_ALL);
        mNavigationSlice.setGeometry(START_ANGLE, 180 - 2 * EMPTY_ANGLE, inner, outer);
        setupNavigationItems();
        mPieContainer.addSlice(mNavigationSlice);

        // construct sysinfo slice
        inner = res.getDimensionPixelSize(R.dimen.pie_sysinfo_radius);
        outer = inner + res.getDimensionPixelSize(R.dimen.pie_sysinfo_height);
        mSysInfo = new PieSysInfo(mContext, mPieContainer, this, PieDrawable.DISPLAY_NOT_AT_TOP);
        mSysInfo.setGeometry(START_ANGLE, 180 - 2 * EMPTY_ANGLE, inner, outer);
        mPieContainer.addSlice(mSysInfo);

        // start listening for changes
        mSettingsObserver.observe();

        mContext.registerReceiver(mBroadcastReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        if (mHasTelephony) {
            TelephonyManager telephonyManager =
                    (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
            telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);
        }
    }

    private void setupNavigationItems() {
        ContentResolver resolver = mContext.getContentResolver();
        int minimumImageSize = (int) mContext.getResources().getDimension(R.dimen.pie_item_size);

        mNavigationSlice.clear();
        prepareBackAltIcon();

        mNumberOfButtons = Settings.System.getInt(resolver,
                Settings.System.PIE_BUTTONS_QTY, 0);
        if (mNumberOfButtons == 0) {
            mNumberOfButtons = 3;
            Settings.System.putInt(resolver,
                    Settings.System.PIE_BUTTONS_QTY, 3);
        }

        for (int j = 0; j < 5; j++) {
            mClickActions[j] = Settings.System.getString(resolver,
                    Settings.System.PIE_CUSTOM_ACTIVITIES[j]);
            if (mClickActions[j] == null) {
                mClickActions[j] = StockClickActions[j];
                Settings.System.putString(resolver,
                        Settings.System.PIE_CUSTOM_ACTIVITIES[j], mClickActions[j]);
            }

            mLongpressActions[j] = Settings.System.getString(resolver,
                    Settings.System.PIE_LONGPRESS_ACTIVITIES[j]);

            if (mLongpressActions[j] == null) {
                mLongpressActions[j] = StockLongpress[j];
                Settings.System.putString(resolver,
                        Settings.System.PIE_LONGPRESS_ACTIVITIES[j], mLongpressActions[j]);
            }

            mPortraitIcons[j] = Settings.System.getString(resolver,
                    Settings.System.PIE_CUSTOM_ICONS[j]);
            if (mPortraitIcons[j] == null) {
                mPortraitIcons[j] = "";
                Settings.System.putString(resolver,
                        Settings.System.PIE_CUSTOM_ICONS[j], "");
            }
        }

        int mLongpressEnabled = Settings.System.getInt(mContext.getContentResolver(),
                 Settings.System.PIE_LONG_PRESS_ENABLE, 0);
        int buttonWidth = 6 / mNumberOfButtons;

        for (int j = 0; j < mNumberOfButtons; j++) {

            if (mLongpressEnabled == 0) {
                        mLongpressActions[j] = "**null**";
            }

            mNavigationSlice.addItem(constructItem(buttonWidth, mClickActions[j],
                    mLongpressActions[j], mPortraitIcons[j], minimumImageSize));
        }

        setNavigationIconHints(mNavigationIconHints, true);
    }

    private PieItem constructItem(int width, String clickAction, String longPressAction,
                String imageUri, int minimumImageSize) {
        ImageView view = new ImageView(mContext);
        int iconType = setPieItemIcon(view, imageUri, clickAction);
        view.setMinimumWidth(minimumImageSize);
        view.setMinimumHeight(minimumImageSize);
        LayoutParams lp = new LayoutParams(minimumImageSize, minimumImageSize);
        view.setLayoutParams(lp);
        PieItem item = new PieItem(mContext, mPieContainer, 0, width, clickAction,
                longPressAction, view, iconType);
        item.setOnClickListener(this);
        if (!longPressAction.equals(ACTION_NULL)) {
            item.setOnLongClickListener(this);
        }
        return item;
    }

    private int setPieItemIcon(ImageView view, String imageUri, String clickAction) {
        boolean drawableSet = false;
        if (imageUri != null) {
            if (imageUri.length() > 0) {
                // custom icon from the URI here
                File f = new File(Uri.parse(imageUri).getPath());
                if (f.exists()) {
                    Drawable d = new BitmapDrawable(mContext.getResources(), f.getAbsolutePath());
                    if (clickAction.equals(ACTION_RECENTS)) {
                        mBackIcon = d;
                    }
                    view.setImageDrawable(d);
                    drawableSet = true;
                    return 2;
                }
            }
            if (!drawableSet && clickAction != null && !clickAction.startsWith("**")) {
                // here it's not a system action (**action**), so it must be an
                // app intent
                try {
                    Drawable d = mContext.getPackageManager().getActivityIcon(
                            Intent.parseUri(clickAction, 0));
                    view.setImageDrawable(d);
                    drawableSet = true;
                    return 1;
                } catch (NameNotFoundException e) {
                    e.printStackTrace();
                    drawableSet = false;
                } catch (URISyntaxException e) {
                    e.printStackTrace();
                    drawableSet = false;
                }
            }
        }

        if (!drawableSet) {
            view.setImageDrawable(getPieSystemIconImage(clickAction));
        }
        return 0;
    }

    private Drawable getPieSystemIconImage(String uri) {
        if (uri == null)
            return mContext.getResources().getDrawable(R.drawable.ic_sysbar_null);

        if (uri.startsWith("**")) {
            if (uri.equals(ACTION_HOME)) {
                return mContext.getResources().getDrawable(R.drawable.ic_sysbar_home);
            } else if (uri.equals(ACTION_BACK)) {
                mBackIcon = mContext.getResources().getDrawable(R.drawable.ic_sysbar_back);
                return mBackIcon;
            } else if (uri.equals(ACTION_RECENTS)) {
                return mContext.getResources().getDrawable(R.drawable.ic_sysbar_recent);
            } else if (uri.equals(ACTION_SCREENSHOT)) {
                return mContext.getResources().getDrawable(R.drawable.ic_sysbar_screenshot);
            } else if (uri.equals(ACTION_SEARCH)) {
                return mContext.getResources().getDrawable(R.drawable.ic_sysbar_search);
            } else if (uri.equals(ACTION_MENU)) {
                return mContext.getResources().getDrawable(R.drawable.ic_sysbar_menu_big);
            } else if (uri.equals(ACTION_IME)) {
                return mContext.getResources().getDrawable(R.drawable.ic_sysbar_ime_switcher);
            } else if (uri.equals(ACTION_LAST_APP)) {
                return mContext.getResources().getDrawable(R.drawable.ic_sysbar_lastapp);
            } else if (uri.equals(ACTION_KILL)) {
                return mContext.getResources().getDrawable(R.drawable.ic_sysbar_killtask);
            } else if (uri.equals(ACTION_WIDGETS)) {
                return mContext.getResources().getDrawable(R.drawable.ic_sysbar_widget);
            } else if (uri.equals(ACTION_POWER)) {
                return mContext.getResources().getDrawable(R.drawable.ic_sysbar_power);
            } else if (uri.equals(ACTION_NOTIFICATIONS)) {
                return mContext.getResources().getDrawable(R.drawable.ic_sysbar_notifications);
            }
        }
        return mContext.getResources().getDrawable(R.drawable.ic_sysbar_null);
    }

    private void prepareBackAltIcon() {
        int drawableColor = (Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.PIE_ICON_COLOR, -2));

        mBackAltIcon = mContext.getResources().getDrawable(R.drawable.ic_sysbar_back_ime);
        if (drawableColor != -2) {
            mBackAltIcon.setColorFilter(drawableColor, Mode.SRC_ATOP);
        } else {
            mBackAltIcon.setColorFilter(null);
        }   
    }

    public void activateFromTrigger(View view, MotionEvent event, Position position) {
        if (mPieContainer != null && !isShowing()) {
            doHapticTriggerFeedback();

            mPosition = position;
            Point center = new Point((int) event.getRawX(), (int) event.getRawY());
            mPieContainer.activate(center, position);
            mPieContainer.invalidate();
        }
    }

    @Override
    public void setNavigationIconHints(int hints) {
        // this call may come from outside
        // check if we already have a navigation slice to manipulate
        if (mNavigationSlice != null) {
            setNavigationIconHints(hints, false);
        } else {
            mNavigationIconHints = hints;
        }
    }

    protected void setNavigationIconHints(int hints, boolean force) {
        if (!force && hints == mNavigationIconHints) return;

        if (DEBUG) Slog.v(TAG, "Pie navigation hints: " + hints);

        mNavigationIconHints = hints;

        PieItem item = findItem(ACTION_HOME);
        if (item != null) {
            boolean isNop = (hints & StatusBarManager.NAVIGATION_HINT_HOME_NOP) != 0;
            item.setAlpha(isNop ? 0.5f : 1.0f);
        }
        item = findItem(ACTION_RECENTS);
        if (item != null) {
            boolean isNop = (hints & StatusBarManager.NAVIGATION_HINT_RECENT_NOP) != 0;
            item.setAlpha(isNop ? 0.5f : 1.0f);
        }
        item = findItem(ACTION_BACK);
        if (item != null) {
            boolean isNop = (hints & StatusBarManager.NAVIGATION_HINT_BACK_NOP) != 0;
            boolean isAlt = (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
            item.setAlpha(isNop ? 0.5f : 1.0f);
            item.setImageDrawable(isAlt ? mBackAltIcon : mBackIcon);
        }
        setDisabledFlags(mDisabledFlags, true);
    }

    private PieItem findItem(String type) {
        for (PieItem item : mNavigationSlice.getItems()) {
            String itemType = (String) item.tag;
            if (type.equals(itemType)) {
                return item;
            }
        }

        return null;
    }

    @Override
    public void setDisabledFlags(int disabledFlags) {
        // this call may come from outside
        // check if we already have a navigation slice to manipulate
        if (mNavigationSlice != null) {
            setDisabledFlags(disabledFlags, false);
        } else {
            mDisabledFlags = disabledFlags;
        }
    }

    protected void setDisabledFlags(int disabledFlags, boolean force) {
        if (!force && mDisabledFlags == disabledFlags) return;

        mDisabledFlags = disabledFlags;

        final boolean disableHome = ((disabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);
        final boolean disableRecent = ((disabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0);
        final boolean disableBack = ((disabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                && ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) == 0);

        PieItem item = findItem(ACTION_BACK);
        if (item != null) {
            item.show(!disableBack);
        }
        item = findItem(ACTION_HOME);
        if (item != null) {
            item.show(!disableHome);
        }
        item = findItem(ACTION_RECENTS);
        if (item != null) {
            item.show(!disableRecent);
        }
    }

    @Override
    public void setMenuVisibility(boolean showMenu) {
        // this call may come from outside
        // nothing to do here
    }

    @Override
    public void onSnap(Position position) {
        if (position == mPosition) {
            return;
        }

        doHapticTriggerFeedback();

        if (DEBUG) {
            Slog.d(TAG, "onSnap from " + position.name());
        }

        int triggerSlots = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.PIE_GRAVITY, Position.LEFT.FLAG);

        triggerSlots = triggerSlots & ~mPosition.FLAG | position.FLAG;

        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.PIE_GRAVITY, triggerSlots);
    }

    @Override
    public void onLongClick(PieItem item) {
        String type = (String) item.longTag;
        mPieContainer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        mPieContainer.playSoundEffect(SoundEffectConstants.CLICK);
        processAction(type);
    }

    @Override
    public void onClick(PieItem item) {
        String type = (String) item.tag;
        mPieContainer.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        mPieContainer.playSoundEffect(SoundEffectConstants.CLICK);
        processAction(type);
    }

    private void processAction(String type) {
        long when = SystemClock.uptimeMillis();

        if (type.equals(ACTION_NULL) || type == null) {
            return;
        } else if (type.equals(ACTION_HOME)) {
            injectKeyDelayed(KeyEvent.KEYCODE_HOME, when);
            return;
        } else if (type.equals(ACTION_BACK)) {
            injectKeyDelayed(KeyEvent.KEYCODE_BACK, when);
            return;
        } else if (type.equals(ACTION_SEARCH)) {
            injectKeyDelayed(KeyEvent.KEYCODE_SEARCH, when);
            return;
        } else if (type.equals(ACTION_MENU)) {
            injectKeyDelayed(KeyEvent.KEYCODE_MENU, when);
            return;
        } else if (type.equals(ACTION_POWER)) {
            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            pm.goToSleep(SystemClock.uptimeMillis());
            return;
        } else if (type.equals(ACTION_IME)) {
            mContext.sendBroadcast(new Intent("android.settings.SHOW_INPUT_METHOD_PICKER"));
            return;
        } else if (type.equals(ACTION_KILL)) {
            mHandler.post(mKillTask);
            return;
        } else if (type.equals(ACTION_WIDGETS)) {
            try {
                mBarService.toggleWidgets();
            } catch (RemoteException e) {
            }
            return;
        } else if (type.equals(ACTION_LAST_APP)) {
            toggleLastApp();
            return;
        } else if (type.equals(ACTION_RECENTS)) {
            try {
                mBarService.toggleRecentApps();
            } catch (RemoteException e) {
                // let it go.
            }
            return;
        } else if (type.equals(ACTION_SCREENSHOT)) {
            takeScreenshot();
            return;
        } else if (type.equals(ACTION_NOTIFICATIONS)) {
            try {
                mBarService.toggleNotificationShade();
            } catch (RemoteException e) {
                // wtf is this
            }
            return;
        } else {  // we must have a custom uri
            try {
                Intent intent = Intent.parseUri(type, 0);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
            } catch (URISyntaxException e) {
                Log.e(TAG, "URISyntaxException: [" + type + "]");
            } catch (ActivityNotFoundException e){
                Log.e(TAG, "ActivityNotFound: [" + type + "]");
            }
            return;
        }
    }

    private void doHapticTriggerFeedback() {
        if (mVibrator == null || !mVibrator.hasVibrator()) {
            return;
        }

        int hapticSetting = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1, UserHandle.USER_CURRENT);
        if (hapticSetting != 0) {
            mVibrator.vibrate(5);
        }
    }

    public boolean isShowing() {
        return mPieContainer != null && mPieContainer.isShowing();
    }

    public String getOperatorState() {
        if (!mHasTelephony) {
            return null;
        }
        if (mServiceState == null || mServiceState.getState() == ServiceState.STATE_OUT_OF_SERVICE) {
            return mContext.getString(R.string.pie_phone_status_no_service);
        }
        if (mServiceState.getState() == ServiceState.STATE_POWER_OFF) {
            return mContext.getString(R.string.pie_phone_status_airplane_mode);
        }
        if (mServiceState.isEmergencyOnly()) {
            return mContext.getString(R.string.pie_phone_status_emergency_only);
        }
        return mServiceState.getOperatorAlphaLong();
    }

    public String getBatteryLevel() {
        if (mBatteryStatus == BatteryManager.BATTERY_STATUS_FULL) {
            return mContext.getString(R.string.pie_battery_status_full);
        }
        if (mBatteryStatus == BatteryManager.BATTERY_STATUS_CHARGING) {
            return mContext.getString(R.string.pie_battery_status_charging, mBatteryLevel);
        }
        return mContext.getString(R.string.pie_battery_status_discharging, mBatteryLevel);
    }

    Runnable mKillTask = new Runnable() {
        public void run() {
            final Intent intent = new Intent(Intent.ACTION_MAIN);
            String defaultHomePackage = "com.android.launcher";
            intent.addCategory(Intent.CATEGORY_HOME);
            final ResolveInfo res = mContext.getPackageManager().resolveActivity(intent, 0);
            if (res.activityInfo != null && !res.activityInfo.packageName.equals("android")) {
                defaultHomePackage = res.activityInfo.packageName;
            }
            boolean targetKilled = false;
            final ActivityManager am = (ActivityManager) mContext
                    .getSystemService(Activity.ACTIVITY_SERVICE);
            List<RunningAppProcessInfo> apps = am.getRunningAppProcesses();
            for (RunningAppProcessInfo appInfo : apps) {
                int uid = appInfo.uid;
                // Make sure it's a foreground user application (not system,
                // root, phone, etc.)
                if (uid >= Process.FIRST_APPLICATION_UID && uid <= Process.LAST_APPLICATION_UID
                        && appInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    if (appInfo.pkgList != null && (appInfo.pkgList.length > 0)) {
                        for (String pkg : appInfo.pkgList) {
                            if (!pkg.equals("com.android.systemui") && !pkg.equals(defaultHomePackage)) {
                                am.forceStopPackage(pkg);
                                targetKilled = true;
                                break;
                            }
                        }
                    } else {
                        Process.killProcess(appInfo.pid);
                        targetKilled = true;
                    }
                }
                if (targetKilled) {
                    Toast.makeText(mContext, R.string.app_killed_message, Toast.LENGTH_SHORT).show();
                    break;
                }
            }
        }
    };

    final Runnable mScreenshotTimeout = new Runnable() {
        @Override
        public void run() {
            synchronized (mScreenshotLock) {
                if (mScreenshotConnection != null) {
                    mContext.unbindService(mScreenshotConnection);
                    mScreenshotConnection = null;
                }
            }
        }
    };

    private void takeScreenshot() {
        synchronized (mScreenshotLock) {
            if (mScreenshotConnection != null) {
                return;
            }
            ComponentName cn = new ComponentName("com.android.systemui",
                    "com.android.systemui.screenshot.TakeScreenshotService");
            Intent intent = new Intent();
            intent.setComponent(cn);
            ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    synchronized (mScreenshotLock) {
                        if (mScreenshotConnection != this) {
                            return;
                        }
                        Messenger messenger = new Messenger(service);
                        Message msg = Message.obtain(null, 1);
                        final ServiceConnection myConn = this;
                        Handler h = new Handler(HDL.getLooper()) {
                            @Override
                            public void handleMessage(Message msg) {
                                synchronized (mScreenshotLock) {
                                    if (mScreenshotConnection == myConn) {
                                        mContext.unbindService(mScreenshotConnection);
                                        mScreenshotConnection = null;
                                        HDL.removeCallbacks(mScreenshotTimeout);
                                    }
                                }
                            }
                        };
                        msg.replyTo = new Messenger(h);
                        msg.arg1 = msg.arg2 = 0;

                        /*
                         * remove for the time being if (mStatusBar != null &&
                         * mStatusBar.isVisibleLw()) msg.arg1 = 1; if
                         * (mNavigationBar != null &&
                         * mNavigationBar.isVisibleLw()) msg.arg2 = 1;
                         */

                        /* wait for the dialog box to close */
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                        }

                        /* take the screenshot */
                        try {
                            messenger.send(msg);
                        } catch (RemoteException e) {
                        }
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                }
            };
            if (mContext.bindService(intent, conn, mContext.BIND_AUTO_CREATE)) {
                mScreenshotConnection = conn;
                HDL.postDelayed(mScreenshotTimeout, 10000);
            }
        }
    }

    private void toggleLastApp() {
        int lastAppId = 0;
        int looper = 1;
        String packageName;
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        final ActivityManager am = (ActivityManager) mContext
                .getSystemService(Activity.ACTIVITY_SERVICE);
        String defaultHomePackage = "com.android.launcher";
        intent.addCategory(Intent.CATEGORY_HOME);
        final ResolveInfo res = mContext.getPackageManager().resolveActivity(intent, 0);
        if (res.activityInfo != null && !res.activityInfo.packageName.equals("android")) {
            defaultHomePackage = res.activityInfo.packageName;
        }
        List <ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(5);
        // lets get enough tasks to find something to switch to
        // Note, we'll only get as many as the system currently has - up to 5
        while ((lastAppId == 0) && (looper < tasks.size())) {
            packageName = tasks.get(looper).topActivity.getPackageName();
            if (!packageName.equals(defaultHomePackage) && !packageName.equals("com.android.systemui")) {
                lastAppId = tasks.get(looper).id;
            }
            looper++;
        }
        if (lastAppId != 0) {
            am.moveTaskToFront(lastAppId, am.MOVE_TASK_NO_USER_ACTION);
        }
    }

    private Handler HDL = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {

            }
        }
    };

}
