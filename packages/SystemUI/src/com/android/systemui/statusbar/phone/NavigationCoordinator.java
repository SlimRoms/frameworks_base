/*
 * Copyright (C) 2014 The TeamEos Project
 *
 * Author: Randall Rushing aka Bigrushdog (randall.rushing@gmail.com)
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
 * NavigationCoordinator facilitates management of custom device navigation
 * states. Add/remove navigation views from window, provide PhoneStatusBar
 * with navigation abstraction, monitor navigation button assigned action
 * uri's for package changes and reset action if needed
 *
 */

package com.android.systemui.statusbar.phone;

import java.util.ArrayList;
import java.util.List;

import com.android.internal.util.actions.ActionHandler;
import com.android.internal.util.actions.ActionUtils;

import com.android.systemui.statusbar.BaseNavigationBar;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

public class NavigationCoordinator {
    private static final String TAG = NavigationCoordinator.class.getSimpleName();
    private static final boolean DEBUG = false;

    public static final int NAVIGATION_MODE_AOSP = 0;
    public static final int NAVIGATION_MODE_NX = 1;
    private static final int[] NAVIGATION_LAYOUTS = {
        com.android.systemui.R.layout.navigation_bar,
        com.android.systemui.R.layout.nx_bar};

    private PhoneStatusBar mBar;
    private NavbarObserver mNavbarObserver;
    private Handler mHandler = new Handler();
    private Runnable mAddNavbar;
    private Runnable mRemoveNavbar;

    // monitor package changes and clear actions on features
    // that launched the package, if one was assigned
    // we monitor softkeys, hardkeys, and NX here
    private PackageReceiver mPackageReceiver;

    private final boolean mHasHardkeys;
    private List<String> mHardkeyActions;
    private List<String> mNxActions = new ArrayList<String>();
    private List<String> mSoftkeyActions = new ArrayList<String>();

    private Context mContext;

    public NavigationCoordinator(Context context, PhoneStatusBar statusBar,
            Runnable forceAddNavbar, Runnable removeNavbar) {
        mContext = context;
        mBar = statusBar;
        mAddNavbar = forceAddNavbar;
        mRemoveNavbar = removeNavbar;
        mHasHardkeys = ActionUtils.isCapKeyDevice(context);
        mNavbarObserver = new NavbarObserver(mHandler);
        mNavbarObserver.observe();

        // map action uri's and resolve intents on package change
        if (mHasHardkeys) {
            mHardkeyActions = new ArrayList<String>();
            mHardkeyActions.add(Settings.System.HARDWARE_BUTTON_BACK_DOUBLETAP);
            mHardkeyActions.add(Settings.System.HARDWARE_BUTTON_BACK_LONGPRESS);
            mHardkeyActions.add(Settings.System.HARDWARE_BUTTON_HOME_DOUBLETAP);
            mHardkeyActions.add(Settings.System.HARDWARE_BUTTON_HOME_LONGPRESS);
            mHardkeyActions.add(Settings.System.HARDWARE_BUTTON_RECENT_SINGLETAP);
            mHardkeyActions.add(Settings.System.HARDWARE_BUTTON_RECENT_DOUBLETAP);
            mHardkeyActions.add(Settings.System.HARDWARE_BUTTON_RECENT_LONGPRESS);
            mHardkeyActions.add(Settings.System.HARDWARE_BUTTON_MENU_SINGLETAP);
            mHardkeyActions.add(Settings.System.HARDWARE_BUTTON_MENU_DOUBLETAP);
            mHardkeyActions.add(Settings.System.HARDWARE_BUTTON_MENU_LONGPRESS);
            mHardkeyActions.add(Settings.System.HARDWARE_BUTTON_ASSIST_SINGLETAP);
            mHardkeyActions.add(Settings.System.HARDWARE_BUTTON_ASSIST_DOUBLETAP);
            mHardkeyActions.add(Settings.System.HARDWARE_BUTTON_ASSIST_LONGPRESS);
        }

        mSoftkeyActions.add(Settings.System.SOFTKEY_BACK_LONGPRESS);
        mSoftkeyActions.add(Settings.System.SOFTKEY_HOME_LONGPRESS);
        mSoftkeyActions.add(Settings.System.SOFTKEY_RECENT_LONGPRESS);
        mSoftkeyActions.add(Settings.System.SOFTKEY_MENU_LONGPRESS);
        mSoftkeyActions.add(Settings.System.SOFTKEY_BACK_DOUBLETAP);
        mSoftkeyActions.add(Settings.System.SOFTKEY_HOME_DOUBLETAP);
        mSoftkeyActions.add(Settings.System.SOFTKEY_RECENT_DOUBLETAP);
        mSoftkeyActions.add(Settings.System.SOFTKEY_MENU_DOUBLETAP);

        mNxActions.add(Settings.System.NX_SINGLETAP_RIGHT);
        mNxActions.add(Settings.System.NX_SINGLETAP_LEFT);
        mNxActions.add(Settings.System.NX_DOUBLETAP_RIGHT);
        mNxActions.add(Settings.System.NX_DOUBLETAP_LEFT);
        mNxActions.add(Settings.System.NX_LONGPRESS_RIGHT);
        mNxActions.add(Settings.System.NX_LONGPRESS_LEFT);
        mNxActions.add(Settings.System.NX_SHORT_FLING_LEFT);
        mNxActions.add(Settings.System.NX_SHORT_FLING_RIGHT);
        mNxActions.add(Settings.System.NX_LONG_FLING_LEFT);
        mNxActions.add(Settings.System.NX_LONG_FLING_RIGHT);

        mPackageReceiver = new PackageReceiver();
        mPackageReceiver.registerBootReceiver(context);
    }

    public BaseNavigationBar getNavigationBarView() {
        int navMode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.NAVIGATION_BAR_MODE, NAVIGATION_MODE_AOSP, UserHandle.USER_CURRENT);
        BaseNavigationBar navBar = (BaseNavigationBar) View.inflate(mContext,
                NAVIGATION_LAYOUTS[navMode], null);
        return navBar;
    }

    // for now, it makes sense to let PhoneStatusBar add/remove navbar view
    // from window manager. Define the add/remove runnables in PSB then pass
    // to us for handling
    class NavbarObserver extends ContentObserver {

        NavbarObserver(Handler handler) {
            super(handler);
        }

        void unobserve() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.unregisterContentObserver(this);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NAVIGATION_BAR_MODE), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.DEV_FORCE_SHOW_NAVBAR), false, this, UserHandle.USER_ALL);
            //resolver.registerContentObserver(Settings.System.getUriFor(
            //        Settings.System.NAVBAR_LEFT_IN_LANDSCAPE), false, this, UserHandle.USER_ALL);
        }

        public void onChange(boolean selfChange, Uri uri) {
            final ContentResolver resolver = mContext.getContentResolver();
            final boolean isBarShowingNow = mBar.getNavigationBarView() != null; // sanity checks

            //if (isBarShowingNow) {
            //    boolean navLeftInLandscape = Settings.System.getIntForUser(resolver,
            //            Settings.System.NAVBAR_LEFT_IN_LANDSCAPE, 0, UserHandle.USER_CURRENT) == 1;
            //    mBar.getNavigationBarView().setLeftInLandscape(navLeftInLandscape);
            //}

            if (mHasHardkeys) {
                // force navbar adds or removes the bar view
                Uri forceBarUri = Settings.System.getUriFor(Settings.System.DEV_FORCE_SHOW_NAVBAR);
                if (forceBarUri != null && forceBarUri.equals(uri)) {
                    boolean visible = Settings.System.getIntForUser(resolver,
                            Settings.System.DEV_FORCE_SHOW_NAVBAR, 0, UserHandle.USER_CURRENT) == 1;
                    if (isBarShowingNow) {
                        mBar.getNavigationBarView().dispose();
                        mHandler.post(mRemoveNavbar);
                    }
                    if (visible) {
                        mHandler.postDelayed(mAddNavbar, 500);
                    }
                    return;
                }
            }
            // mode change: this always removes the bar and adds it back, no permanently removed state
            Uri barModeUri = Settings.System.getUriFor(Settings.System.NAVIGATION_BAR_MODE);
            if (barModeUri != null && barModeUri.equals(uri)) {
                if (isBarShowingNow) {
                    mBar.getNavigationBarView().dispose();
                    mHandler.post(mRemoveNavbar);
                }
                mHandler.postDelayed(mAddNavbar, 500);
                return;
            }
        }
    }

    /*
     * Initially register for boot completed, as PackageManager is likely not
     * online yet. Once boot is completed, reregister for package changes and
     * handle as needed
     */
    private class PackageReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
                handlePackageChanged();
                registerPackageReceiver(context);
            } else if (Intent.ACTION_PACKAGE_ADDED.equals(action) ||
                    Intent.ACTION_PACKAGE_CHANGED.equals(action) ||
                    Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action) ||
                    Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                handlePackageChanged();
            }
        }

        void registerPackageReceiver(Context ctx) {
            Log.i(TAG, "Boot completed received, registering package receiver");
            ctx.unregisterReceiver(this);
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addDataScheme("package");
            ctx.registerReceiver(this, filter);
        }

        void registerBootReceiver(Context ctx) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BOOT_COMPLETED);
            ctx.registerReceiver(this, filter);
        }
    }

    private void handlePackageChanged() {
        final ContentResolver resolver = mContext.getContentResolver();
        final PackageManager pm = mContext.getPackageManager();
        final Thread updateThread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (mHasHardkeys) {
                    for (String uri : mHardkeyActions) {
                        ActionHandler.resolveOrClearIntent(pm, resolver, uri);
                    }
                }
                for (String uri : mSoftkeyActions) {
                    ActionHandler.resolveOrClearIntent(pm, resolver, uri);
                }

                for (String uri : mNxActions) {
                    ActionHandler.resolveOrClearIntent(pm, resolver, uri);
                }
            }
        });
        updateThread.setPriority(Process.THREAD_PRIORITY_BACKGROUND);
        updateThread.run();
    }
}
