/*
 * Copyright (C) 2013 The CyanogenMod Project (Jens Doll)
 * Copyright (C) 2013 SlimRoms Project
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
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Point;
import android.graphics.PorterDuff.Mode;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.ServiceManager;
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
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;

import com.android.internal.util.slim.ButtonConfig;
import com.android.internal.util.slim.ButtonsConstants;
import com.android.internal.util.slim.ButtonsHelper;
import com.android.internal.util.slim.SlimActions;
import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.pie.PieItem;
import com.android.systemui.statusbar.pie.PieLayout;
import com.android.systemui.statusbar.pie.PieLayout.PieDrawable;
import com.android.systemui.statusbar.pie.PieLayout.PieSlice;
import com.android.systemui.statusbar.pie.PieSliceContainer;
import com.android.systemui.statusbar.pie.PieSysInfo;

import java.util.ArrayList;

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

    private boolean mSecondLayerActive;

    private Handler mHandler = new Handler();

    public static final float EMPTY_ANGLE = 10;
    public static final float START_ANGLE = 180 + EMPTY_ANGLE;

    private final static int MENU_VISIBILITY_ALWAYS = 0;
    private final static int MENU_VISIBILITY_NEVER = 1;
    private final static int MENU_VISIBILITY_SYSTEM = 2;

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
    private TelephonyManager mTelephonyManager;
    private ServiceState mServiceState;

    // all pie slices that are managed by the controller
    private PieSliceContainer mNavigationSlice;
    private PieSliceContainer mNavigationSliceSecondLayer;
    private PieItem mMenuButton;
    private PieSysInfo mSysInfo;

    private int mNavigationIconHints = 0;
    private int mDisabledFlags = 0;
    private boolean mShowMenu = false;
    private int mShowMenuVisibility;
    private Drawable mBackIcon;
    private Drawable mBackAltIcon;
    private boolean mIconResize = false;
    private float mIconResizeFactor ;

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
            active = true;
        }

        public boolean move(MotionEvent event) {
            if (!active) {
                return false;
            }
            // Unroll the complete logic here - we want to be fast and out of the
            // event chain as fast as possible.
            float distance = 0;
            boolean loaded = false;
            switch (position) {
                case TOP:
                case BOTTOM:
                    distance = Math.abs(event.getY() - initialY);
                    break;
                case LEFT:
                case RIGHT:
                    distance = Math.abs(event.getX() - initialX);
                    break;
            }
            // Swipe up
            if (distance > sDistance) {
                loaded = true;
                active = false;
            }
            return loaded;
        }

        public boolean active = false;
        public final Position position;
    }

    public Tracker buildTracker(Position position) {
        return new Tracker(position);
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_BUTTONS_CONFIG), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_SIZE), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_BUTTON_COLOR), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_BUTTON_PRESSED_COLOR), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_BUTTON_LONG_PRESSED_COLOR), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_BUTTON_OUTLINE_COLOR), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_ICON_COLOR), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_ICON_COLOR_MODE), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_BUTTON_ALPHA), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_BUTTON_PRESSED_ALPHA), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_SECOND_LAYER_ACTIVE), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_MENU), false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange) {
            boolean secondLayerActive = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.PIE_SECOND_LAYER_ACTIVE, 0) == 1;

            if (mSecondLayerActive != secondLayerActive) {
                if (secondLayerActive) {
                    // second layer is enabled....start observing the settings
                    mSecondLayerObserver.observe();
                } else {
                    // second layer is disabled....unregister observer for it
                    mContext.getContentResolver().unregisterContentObserver(mSecondLayerObserver);
                }
                mSecondLayerActive = secondLayerActive;
                constructSlices();
            } else {
                setupNavigationItems();
            }
        }
    }
    private SettingsObserver mSettingsObserver = new SettingsObserver(mHandler);

    // second layer observer is only active when user activated it to
    // reduce mem usage on normal mode
    private final class SecondLayerObserver extends ContentObserver {
        SecondLayerObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.PIE_BUTTONS_CONFIG_SECOND_LAYER),
                    false,
                    this);
        }

        @Override
        public void onChange(boolean selfChange) {
            setupNavigationItems();
        }
    }
    private SecondLayerObserver mSecondLayerObserver = new SecondLayerObserver(mHandler);

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                mBatteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                mBatteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                         BatteryManager.BATTERY_STATUS_UNKNOWN);
            } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)
                        || Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)) {
                setupNavigationItems();
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

        mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        mWm = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));

        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            mTelephonyManager =
                    (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        }

        Tracker.sDistance = mContext.getResources().getDimensionPixelSize(R.dimen.pie_trigger_distance);
    }

    public void detachContainer() {
        if (mPieContainer == null) {
            return;
        }

        if (mTelephonyManager != null) {
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
        }

        if (mSecondLayerActive) {
            mContext.getContentResolver().unregisterContentObserver(mSecondLayerObserver);
        }

        mContext.unregisterReceiver(mBroadcastReceiver);
        mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);

        mPieContainer.clearSlices();
        mPieContainer = null;
    }

    public void attachStatusBar(BaseStatusBar statusBar) {
        mStatusBar = statusBar;
    }

    public void attachContainer(PieLayout container) {
        mPieContainer = container;

        if (DEBUG) {
            Slog.d(TAG, "Attaching to container: " + container);
        }

        mPieContainer.setOnSnapListener(this);

        mSecondLayerActive = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.PIE_SECOND_LAYER_ACTIVE, 0) == 1;

        // construct the slices
        constructSlices();

        // start listening for changes
        mSettingsObserver.observe();

        // add intent actions to listen on it
        // battery change for the battery
        // screen off to get rid of the pie
        // apps available to check if apps on external sdcard
        // are available and reconstruct the button icons
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        filter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        // start listening for second layer observer
        // only when active
        if (mSecondLayerActive) {
            mSecondLayerObserver.observe();
        }

        if (mTelephonyManager != null) {
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);
        }
    }

    public void constructSlices() {
        final Resources res = mContext.getResources();

        // if already constructed...clear the slices
        if (mPieContainer != null) {
            mPieContainer.clearSlices();
        }

        // construct navbar slice
        int inner = res.getDimensionPixelSize(R.dimen.pie_navbar_radius);
        int outer = inner + res.getDimensionPixelSize(R.dimen.pie_navbar_height);
        mNavigationSlice = new PieSliceContainer(mPieContainer, PieSlice.IMPORTANT
                | PieDrawable.DISPLAY_ALL);
        mNavigationSlice.setGeometry(START_ANGLE, 180 - 2 * EMPTY_ANGLE, inner, outer);

        // construct maybe navbar slice second layer
        if (mSecondLayerActive) {
            inner = res.getDimensionPixelSize(R.dimen.pie_navbar_second_layer_radius);
            outer = inner + res.getDimensionPixelSize(R.dimen.pie_navbar_height);
            mNavigationSliceSecondLayer = new PieSliceContainer(mPieContainer, PieSlice.IMPORTANT
                    | PieDrawable.DISPLAY_ALL);
            mNavigationSliceSecondLayer.setGeometry(START_ANGLE, 180 - 2 * EMPTY_ANGLE, inner, outer);
        }

        // setup buttons and add the slices finally
        setupNavigationItems();
        mPieContainer.addSlice(mNavigationSlice);
        if (mSecondLayerActive) {
            mPieContainer.addSlice(mNavigationSliceSecondLayer);
            // adjust dimensions for sysinfo when second layer is active
            inner = res.getDimensionPixelSize(R.dimen.pie_sysinfo_second_layer_radius);
        } else {
            inner = res.getDimensionPixelSize(R.dimen.pie_sysinfo_radius);
        }

        // construct sysinfo slice
        outer = inner + res.getDimensionPixelSize(R.dimen.pie_sysinfo_height);
        mSysInfo = new PieSysInfo(mContext, mPieContainer, this, PieDrawable.DISPLAY_NOT_AT_TOP);
        mSysInfo.setGeometry(START_ANGLE, 180 - 2 * EMPTY_ANGLE, inner, outer);
        mPieContainer.addSlice(mSysInfo);
    }

    private void setupNavigationItems() {
        ContentResolver resolver = mContext.getContentResolver();
        // get minimum allowed image size for layout
        int minimumImageSize = (int) mContext.getResources().getDimension(R.dimen.pie_item_size);

        mNavigationSlice.clear();

        // reset mIconResizeFactor
        mIconResizeFactor = 1.0f;
        // check the size set from the user and set resize values if needed
        float diff = PieLayout.PIE_ICON_START_SIZE_FACTOR - Settings.System.getFloat(resolver,
                Settings.System.PIE_SIZE, PieLayout.PIE_CONTROL_SIZE_DEFAULT);
        if (diff > 0.0f) {
            mIconResize = true;
            mIconResizeFactor = 1.0f - diff;
        } else {
            mIconResize = false;
        }

        // prepare IME back icon
        mBackAltIcon = mContext.getResources().getDrawable(R.drawable.ic_sysbar_back_ime);
        mBackAltIcon = prepareBackIcon(mBackAltIcon, false, false);

        getCustomActionsAndConstruct(resolver, false, minimumImageSize);

        if (mSecondLayerActive) {
            mNavigationSliceSecondLayer.clear();
            getCustomActionsAndConstruct(resolver, true, minimumImageSize);
        }

        mShowMenuVisibility = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.PIE_MENU, MENU_VISIBILITY_SYSTEM,
                UserHandle.USER_CURRENT);

        setNavigationIconHints(mNavigationIconHints, true);
        setMenuVisibility(mShowMenu);
    }

    private void getCustomActionsAndConstruct(ContentResolver resolver,
            boolean secondLayer, int minimumImageSize) {

        ArrayList<ButtonConfig> buttonsConfig;

        if (secondLayer) {
            buttonsConfig = ButtonsHelper.getPieSecondLayerConfig(mContext);
        } else {
            buttonsConfig = ButtonsHelper.getPieConfig(mContext);
        }

        int buttonWidth = 10 / buttonsConfig.size();
        ButtonConfig buttonConfig;

        for (int j = 0; j < buttonsConfig.size(); j++) {
            buttonConfig = buttonsConfig.get(j);
            if (secondLayer) {
                addItemToLayer(mNavigationSliceSecondLayer, buttonConfig, buttonWidth, minimumImageSize);
            } else {
                addItemToLayer(mNavigationSlice, buttonConfig, buttonWidth, minimumImageSize);
            }
        }

        if (!secondLayer) {
            mMenuButton = constructItem(1, ButtonsConstants.ACTION_MENU,
                    ButtonsConstants.ACTION_NULL,
                    ButtonsConstants.ICON_EMPTY,
                    minimumImageSize);
            mNavigationSlice.addItem(mMenuButton);
        }
    }

    private void addItemToLayer(PieSliceContainer layer, ButtonConfig buttonConfig,
            int buttonWidth, int minimumImageSize) {
        layer.addItem(constructItem(buttonWidth,
                buttonConfig.getClickAction(),
                buttonConfig.getLongpressAction(),
                buttonConfig.getIcon(), minimumImageSize));

        if (buttonConfig.getClickAction().equals(ButtonsConstants.ACTION_HOME)) {
            layer.addItem(constructItem(buttonWidth,
                    ButtonsConstants.ACTION_KEYGUARD_SEARCH,
                    buttonConfig.getLongpressAction(),
                    ButtonsConstants.ICON_EMPTY,
                    minimumImageSize));
        }
    }

    private PieItem constructItem(int width, String clickAction, String longPressAction,
                String iconUri, int minimumImageSize) {
        ImageView view = new ImageView(mContext);
        int iconType = setPieItemIcon(view, iconUri, clickAction);
        view.setMinimumWidth(minimumImageSize);
        view.setMinimumHeight(minimumImageSize);
        LayoutParams lp = new LayoutParams(minimumImageSize, minimumImageSize);
        view.setLayoutParams(lp);
        PieItem item = new PieItem(mContext, mPieContainer, 0, width, clickAction,
                longPressAction, view, iconType);
        item.setOnClickListener(this);
        if (!longPressAction.equals(ButtonsConstants.ACTION_NULL)) {
            item.setOnLongClickListener(this);
        }
        return item;
    }

    private int setPieItemIcon(ImageView view, String iconUri, String clickAction) {
        Drawable d = ButtonsHelper.getButtonIconImage(mContext, clickAction, iconUri);
        if (d != null) {
            view.setImageDrawable(d);
        }

        if (iconUri != null && !iconUri.equals(ButtonsConstants.ICON_EMPTY)
            && !iconUri.startsWith(ButtonsConstants.SYSTEM_ICON_IDENTIFIER)) {
            if (clickAction.equals(ButtonsConstants.ACTION_BACK)) {
                // back icon image needs to be handled seperatly
                // all other is handled in PieItem
                int customImageColorize = Settings.System.getInt(
                        mContext.getContentResolver(),
                        Settings.System.PIE_ICON_COLOR_MODE, 0);
                mBackIcon = prepareBackIcon(d,
                    (customImageColorize == 0 || customImageColorize == 2), true);
            } else {
                // custom images need to be forced to resize to fit better
                resizeIcon(view, null, true);
            }
            return 2;
        } else {
            if (mIconResize) {
                resizeIcon(view, null, false);
            }
            if (clickAction.startsWith("**")) {
                if (clickAction.equals(ButtonsConstants.ACTION_BACK)) {
                    mBackIcon = prepareBackIcon(d, false, false);
                }
                return 0;
            }
            return 1;
        }
    }

    private Drawable resizeIcon(ImageView view, Drawable d, boolean useSystemDimens) {
        int width = 0;
        int height = 0;
        Drawable dOriginal = d;
        if (d == null) {
            dOriginal = view.getDrawable();
        }
        Bitmap bitmap = ((BitmapDrawable) dOriginal).getBitmap();
        if (useSystemDimens) {
            width = height = (int) (mContext.getResources()
                .getDimensionPixelSize(com.android.internal.R.dimen.app_icon_size) * 0.9f);
        } else {
            width = bitmap.getWidth();
            height = bitmap.getHeight();
        }
        width = (int) (width * mIconResizeFactor);
        height = (int) (height * mIconResizeFactor);

        Drawable dResized = new BitmapDrawable(mContext.getResources(),
            Bitmap.createScaledBitmap(bitmap, width, height, false));
        if (d == null) {
            view.setImageDrawable(dResized);
            return null;
        } else {
            return (dResized);
        }
    }

    private Drawable prepareBackIcon(Drawable d, boolean customImageColorize, boolean forceResize) {
        int drawableColor = (Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.PIE_ICON_COLOR, -2));
        if (mIconResize && !forceResize) {
            d = resizeIcon(null, d, false);
        } else if (forceResize) {
            d = resizeIcon(null, d, true);
        }
        if (drawableColor != -2 && customImageColorize) {
            d.setColorFilter(drawableColor, Mode.MULTIPLY);
        // forceResize gives us the information that it must
        // be a custom image icon....so do not colorize
        // it if not already done before
        } else if (drawableColor != -2 && !forceResize) {
            d.setColorFilter(drawableColor, Mode.SRC_ATOP);
        } else {
            d.setColorFilter(null);
        }
        return d;
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
        PieItem item;

        for (int j = 0; j < 2; j++) {
            item = findItem(ButtonsConstants.ACTION_HOME, j);
            if (item != null) {
                boolean isNop = (hints & StatusBarManager.NAVIGATION_HINT_HOME_NOP) != 0;
                item.setAlpha(isNop ? 0.5f : 1.0f);
            }
            item = findItem(ButtonsConstants.ACTION_RECENTS, j);
            if (item != null) {
                boolean isNop = (hints & StatusBarManager.NAVIGATION_HINT_RECENT_NOP) != 0;
                item.setAlpha(isNop ? 0.5f : 1.0f);
            }
            item = findItem(ButtonsConstants.ACTION_BACK, j);
            if (item != null) {
                boolean isNop = (hints & StatusBarManager.NAVIGATION_HINT_BACK_NOP) != 0;
                boolean isAlt = (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
                item.setAlpha(isNop ? 0.5f : 1.0f);
                item.setImageDrawable(isAlt ? mBackAltIcon : mBackIcon);
            }
        }
        setDisabledFlags(mDisabledFlags, true);
    }

    private PieItem findItem(String type, int secondLayer) {
        if (secondLayer == 1) {
            if (mSecondLayerActive && mNavigationSliceSecondLayer != null) {
                for (PieItem item : mNavigationSliceSecondLayer.getItems()) {
                    String itemType = (String) item.tag;
                    if (type.equals(itemType)) {
                       return item;
                    }
                }
            }
        } else {
            for (PieItem item : mNavigationSlice.getItems()) {
                String itemType = (String) item.tag;
                if (type.equals(itemType)) {
                   return item;
                }
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

        PieItem item;
        for (int j = 0; j < 2; j++) {
            item = findItem(ButtonsConstants.ACTION_BACK, j);
            if (item != null) {
                item.show(!disableBack);
            }
            item = findItem(ButtonsConstants.ACTION_HOME, j);
            if (item != null) {
                item.show(!disableHome);
                // if the homebutton exists we can assume that the keyguard
                // search button exists as well.
                item = findItem(ButtonsConstants.ACTION_KEYGUARD_SEARCH, j);
                item.show(disableHome);
            }
            item = findItem(ButtonsConstants.ACTION_RECENTS, j);
            if (item != null) {
                item.show(!disableRecent);
            }
        }
        setMenuVisibility(mShowMenu, true);
    }

    @Override
    public void setMenuVisibility(boolean showMenu) {
        setMenuVisibility(showMenu, false);
    }

    private void setMenuVisibility(boolean showMenu, boolean force) {
        if (!force && mShowMenu == showMenu) {
            return;
        }
        if (mMenuButton != null) {
            final boolean disableRecent = ((mDisabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0);
            mMenuButton.show((showMenu || mShowMenuVisibility == MENU_VISIBILITY_ALWAYS)
                && mShowMenuVisibility != MENU_VISIBILITY_NEVER && !disableRecent);
        }
        mShowMenu = showMenu;
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
        if (!SlimActions.isActionKeyEvent(type)) {
            mPieContainer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
        mPieContainer.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
        SlimActions.processAction(mContext, type, true);
    }

    @Override
    public void onClick(PieItem item) {
        String type = (String) item.tag;
        if (!SlimActions.isActionKeyEvent(type)) {
            mPieContainer.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        if (!type.equals(ButtonsConstants.ACTION_MENU)) {
            mPieContainer.playSoundEffect(SoundEffectConstants.CLICK);
        }
        mPieContainer.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        SlimActions.processAction(mContext, type, false);
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
        if (mTelephonyManager == null) {
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

}
