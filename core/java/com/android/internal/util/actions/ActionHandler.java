/*
 * Copyright (C) 2015 The TeamEos Project
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
 * Launches actions assigned to widgets. Creates bundles of state based
 * on the type of action passed.
 *
 */

package com.android.internal.util.actions;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.IActivityManager;
import android.app.SearchManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.hardware.input.InputManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.android.internal.statusbar.IStatusBarService;

public abstract class ActionHandler {
    protected static String TAG = ActionHandler.class.getSimpleName();

    protected ArrayList<String> mActions;
    protected Context mContext;

    private static final String SYSTEM_PREFIX = "task";
    private static final String SYSTEMUI = "com.android.systemui";

    public static final String SYSTEMUI_TASK_NO_ACTION = "task_no_action";
    public static final String SYSTEMUI_TASK_SETTINGS_PANEL = "task_settings_panel";
    public static final String SYSTEMUI_TASK_NOTIFICATION_PANEL = "task_notification_panel";
    public static final String SYSTEMUI_TASK_SCREENSHOT = "task_screenshot";
    //public static final String SYSTEMUI_TASK_SCREENRECORD = "task_screenrecord";
    // public static final String SYSTEMUI_TASK_AUDIORECORD =
    // "task_audiorecord";
    public static final String SYSTEMUI_TASK_EXPANDED_DESKTOP = "task_expanded_desktop";
    public static final String SYSTEMUI_TASK_SCREENOFF = "task_screenoff";
    public static final String SYSTEMUI_TASK_KILL_PROCESS = "task_killcurrent";
    public static final String SYSTEMUI_TASK_ASSIST = "task_assist";
    public static final String SYSTEMUI_TASK_POWER_MENU = "task_powermenu";
    public static final String SYSTEMUI_TASK_TORCH = "task_torch";
    public static final String SYSTEMUI_TASK_CAMERA = "task_camera";
    public static final String SYSTEMUI_TASK_BT = "task_bt";
    public static final String SYSTEMUI_TASK_WIFI = "task_wifi";
    public static final String SYSTEMUI_TASK_WIFIAP = "task_wifiap";
    public static final String SYSTEMUI_TASK_RECENTS = "task_recents";
    public static final String SYSTEMUI_TASK_LAST_APP = "task_last_app";
    public static final String SYSTEMUI_TASK_VOICE_SEARCH = "task_voice_search";
    public static final String SYSTEMUI_TASK_APP_SEARCH = "task_app_search";
    public static final String SYSTEMUI_TASK_MENU = "task_menu";
    public static final String SYSTEMUI_TASK_BACK = "task_back";
    public static final String SYSTEMUI_TASK_HOME = "task_home";

    public static final String INTENT_SHOW_POWER_MENU = "action_handler_show_power_menu";
    public static final String INTENT_TOGGLE_SCREENRECORD = "action_handler_toggle_screenrecord";

    private static enum SystemAction {
        NoAction(SYSTEMUI_TASK_NO_ACTION, "No Action", SYSTEMUI, "ic_sysbar_null"),
        SettingsPanel(SYSTEMUI_TASK_SETTINGS_PANEL, "Settings Panel", SYSTEMUI, "ic_notify_quicksettings_normal"),
        NotificationPanel(SYSTEMUI_TASK_NOTIFICATION_PANEL, "Notification Panel", SYSTEMUI, "ic_sysbar_notifications"),
        Screenshot(SYSTEMUI_TASK_SCREENSHOT, "Take screenshot", SYSTEMUI, "ic_sysbar_screenshot"),
        //Screenrecord(SYSTEMUI_TASK_SCREENRECORD, "Toggle screenrecord", SYSTEMUI, "ic_camera_alt_24dp"),
        //ExpandedDesktop(SYSTEMUI_TASK_EXPANDED_DESKTOP, "Expanded desktop", SYSTEMUI, "ic_qc_expanded_desktop"),
        ScreenOff(SYSTEMUI_TASK_SCREENOFF, "Screen off", SYSTEMUI, "ic_qs_sleep"),
        KillApp(SYSTEMUI_TASK_KILL_PROCESS, "Close app", SYSTEMUI, "ic_sysbar_killtask"),
        Assistant(SYSTEMUI_TASK_ASSIST, "Google Now", SYSTEMUI, "ic_sysbar_assist"),
        VoiceSearch(SYSTEMUI_TASK_VOICE_SEARCH, "Voice search", SYSTEMUI, "ic_sysbar_assist"),
        //Flashlight(SYSTEMUI_TASK_TORCH, "Flashlight", SYSTEMUI, "ic_sysbar_torch"),
        Bluetooth(SYSTEMUI_TASK_BT, "Bluetooth toggle", SYSTEMUI, "ic_sysbar_bt"),
        WiFi(SYSTEMUI_TASK_WIFI, "WiFi toggle", SYSTEMUI, "ic_sysbar_wifi"),
        Hotspot(SYSTEMUI_TASK_WIFIAP, "Hotspot toggle", SYSTEMUI, "ic_qs_wifi_ap_on"),
        LastApp(SYSTEMUI_TASK_LAST_APP, "Last app", SYSTEMUI, "ic_sysbar_lastapp"),
        Overview(SYSTEMUI_TASK_RECENTS, "Overview", SYSTEMUI, "ic_sysbar_recent"),
        PowerMenu(SYSTEMUI_TASK_POWER_MENU, "Power menu", SYSTEMUI, "ic_sysbar_null"),
        Menu(SYSTEMUI_TASK_MENU, "Menu", SYSTEMUI, "ic_sysbar_menu_big"),
        Back(SYSTEMUI_TASK_BACK, "Back", SYSTEMUI, "ic_sysbar_back"),
        Home(SYSTEMUI_TASK_HOME, "Home", SYSTEMUI, "ic_sysbar_home");

        private String mAction;
        private String mLabel;
        private String mIconPackage;
        private String mIconName;

        private SystemAction(String action, String label, String iconPackage, String iconName) {
            mAction = action;
            mLabel = label;
            mIconPackage = iconPackage;
            mIconName = iconName;
        }

        private ActionBundle create(Context ctx) {
            ActionBundle a = new ActionBundle();
            a.action = mAction;
            a.label = mLabel;
            a.icon = getDrawableFromResources(ctx);
            return a;
        }

        private Drawable getDrawableFromResources(Context context) {
            try {
                Resources res = context.getPackageManager()
                        .getResourcesForApplication(mIconPackage);
                Drawable icon = res.getDrawable(res.getIdentifier(mIconName, "drawable",
                        mIconPackage));
                return icon;
            } catch (Exception e) {
                return context.getResources().getDrawable(
                        com.android.internal.R.drawable.sym_def_app_icon);
            }
        }
    }

    private static SystemAction[] systemActions = new SystemAction[] {
            SystemAction.NoAction, SystemAction.SettingsPanel,
            SystemAction.NotificationPanel, SystemAction.Screenshot,
            SystemAction.ScreenOff, SystemAction.KillApp,
            SystemAction.Assistant,
            SystemAction.Bluetooth, SystemAction.WiFi,
            SystemAction.Hotspot, SystemAction.LastApp,
            SystemAction.PowerMenu, SystemAction.Overview,
            SystemAction.Menu, SystemAction.Back,
            SystemAction.VoiceSearch, SystemAction.Home
    };

    public static ArrayList<ActionBundle> getSystemActions(Context context) {
        ArrayList<ActionBundle> bundle = new ArrayList<ActionBundle>();
        for (int i = 0; i < systemActions.length; i++) {
            bundle.add(systemActions[i].create(context));
        }
        Collections.sort(bundle);
        return bundle;
    }

    public static class ActionBundle implements Comparable<ActionBundle> {
        public String action = "";
        public String label = "";
        public Drawable icon = null;

        public ActionBundle() {
        }

        public ActionBundle(Context context, String _action) {
            action = _action;
            label = getFriendlyNameForUri(context.getPackageManager(), _action);
            icon = getDrawableForAction(context, _action);
        }

        @Override
        public int compareTo(ActionBundle another) {
            int result = label.toString().compareToIgnoreCase(another.label.toString());
            return result;
        }

        public static Intent getIntent(String uri) {
            if (uri == null || uri.startsWith(SYSTEM_PREFIX)) {
                return null;
            }

            Intent intent = null;
            try {
                intent = Intent.parseUri(uri, 0);
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
            return intent;
        }

        private static Drawable getDrawableForAction(Context context, String action) {
            Drawable d = null;

            // this null check is probably no-op but let's be safe anyways
            if (action == null || context == null) {
                return d;
            }
            if (action.startsWith(SYSTEM_PREFIX)) {
                for (int i = 0; i < systemActions.length; i++) {
                    if (systemActions[i].mAction.equals(action)) {
                        d = systemActions[i].getDrawableFromResources(context);
                    }
                }
            } else {
                d = getDrawableFromComponent(context.getPackageManager(), action);
            }
            return d;
        }

        private static Drawable getDrawableFromComponent(PackageManager pm, String activity) {
            Drawable d = null;
            try {
                Intent intent = Intent.parseUri(activity, 0);
                ActivityInfo info = intent.resolveActivityInfo(pm,
                        PackageManager.GET_ACTIVITIES);
                if (info != null) {
                    d = info.loadIcon(pm);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return d;
        }

        private static String getFriendlyActivityName(PackageManager pm, Intent intent,
                boolean labelOnly) {
            ActivityInfo ai = intent.resolveActivityInfo(pm, PackageManager.GET_ACTIVITIES);
            String friendlyName = null;
            if (ai != null) {
                friendlyName = ai.loadLabel(pm).toString();
                if (friendlyName == null && !labelOnly) {
                    friendlyName = ai.name;
                }
            }
            return friendlyName != null || labelOnly ? friendlyName : intent.toUri(0);
        }

        private static String getFriendlyShortcutName(PackageManager pm, Intent intent) {
            String activityName = getFriendlyActivityName(pm, intent, true);
            String name = intent.getStringExtra(Intent.EXTRA_SHORTCUT_NAME);

            if (activityName != null && name != null) {
                return activityName + ": " + name;
            }
            return name != null ? name : intent.toUri(0);
        }

        private static String getFriendlyNameForUri(PackageManager pm, String uri) {
            if (uri == null) {
                return null;
            }
            if (uri.startsWith(SYSTEM_PREFIX)) {
                for (int i = 0; i < systemActions.length; i++) {
                    if (systemActions[i].mAction.equals(uri)) {
                        return systemActions[i].mLabel;
                    }
                }
            } else {
                try {
                    Intent intent = Intent.parseUri(uri, 0);
                    if (Intent.ACTION_MAIN.equals(intent.getAction())) {
                        return getFriendlyActivityName(pm, intent, false);
                    }
                    return getFriendlyShortcutName(pm, intent);
                } catch (URISyntaxException e) {
                }
            }
            return uri;
        }
    }

    private static final class StatusBarHelper {
        private static boolean isPreloaded = false;
        private static final Object mLock = new Object();
        private static IStatusBarService mService = null;

        private static IStatusBarService getStatusBarService() {
            synchronized (mLock) {
                if (mService == null) {
                    try {
                        mService = IStatusBarService.Stub.asInterface(
                                ServiceManager.getService("statusbar"));
                    } catch (Exception e) {
                    }
                }
                return mService;
            }
        }

        private static void toggleRecentsApps() {
            IStatusBarService service = getStatusBarService();
            if (service != null) {
                try {
                    sendCloseSystemWindows("recentapps");
                    service.toggleRecentApps();
                } catch (RemoteException e) {
                    return;
                }
                isPreloaded = false;
            }
        }

        private static void cancelPreloadRecentApps() {
            if (isPreloaded == false)
                return;
            IStatusBarService service = getStatusBarService();
            if (service != null) {
                try {
                    service.cancelPreloadRecentApps();
                } catch (Exception e) {
                    return;
                }
            }
            isPreloaded = false;
        }

        private static void preloadRecentApps() {
            IStatusBarService service = getStatusBarService();
            if (service != null) {
                try {
                    service.preloadRecentApps();
                } catch (RemoteException e) {
                    isPreloaded = false;
                    return;
                }
                isPreloaded = true;
            }
        }

        private static void expandNotificationPanel() {
            IStatusBarService service = getStatusBarService();
            if (service != null) {
                try {
                    service.expandNotificationsPanel();
                } catch (RemoteException e) {
                }
            }
        }

        private static void expandSettingsPanel() {
            IStatusBarService service = getStatusBarService();
            if (service != null) {
                try {
                    service.expandSettingsPanel();
                } catch (RemoteException e) {
                }
            }
        }
    }

    public static void toggleRecentApps() {
        StatusBarHelper.toggleRecentsApps();
    }

    public static void cancelPreloadRecentApps() {
        StatusBarHelper.cancelPreloadRecentApps();
    }

    public static void preloadRecentApps() {
        StatusBarHelper.preloadRecentApps();
    }

    /*
     * checks that the action set in uri resolves. If not, set uri action to no
     * action. Only applies to uri that do not have a system action
     */
    public static void resolveOrClearIntent(PackageManager pm, ContentResolver resolver, String uri) {
        String action = Settings.System.getStringForUser(resolver, uri, UserHandle.USER_CURRENT);
        if (action != null && !action.startsWith(ActionHandler.SYSTEM_PREFIX)) {
            String resolvedName = ActionBundle.getFriendlyNameForUri(pm, action);
            // if resolved name is null or the full raw intent string is
            // returned
            // we were unable to resolve
            if (resolvedName == null || resolvedName.equals(action)) {
                Settings.System.putStringForUser(resolver, uri,
                        ActionHandler.SYSTEMUI_TASK_NO_ACTION, UserHandle.USER_CURRENT);
            }
        }
    }

    public ActionHandler(Context context, ArrayList<String> actions) {
        if (context == null)
            throw new IllegalArgumentException("Context cannot be null");
        mContext = context;
        mActions = actions;
    }

    public ActionHandler(Context context, String actions) {
        if (context == null)
            throw new IllegalArgumentException("Context cannot be null");
        mContext = context;
        mActions = new ArrayList<String>();
        mActions.addAll(Arrays.asList(actions.split("\\|")));
    }

    public ActionHandler(Context context) {
        if (context == null)
            throw new IllegalArgumentException("Context cannot be null");
        mContext = context;
    }

    /**
     * Set the actions to perform.
     * 
     * @param actions
     */
    public void setActions(List<String> actions) {
        if (actions == null) {
            mActions = null;
        } else {
            mActions = new ArrayList<String>();
            mActions.addAll(actions);
        }
    }

    /**
     * Event handler. This method must be called when the event should be
     * triggered.
     * 
     * @param location
     * @return
     */
    public final boolean handleEvent(int location) {
        if (mActions == null) {
            Log.d("ActionHandler", "Discarding event due to null actions");
            return false;
        }

        String action = mActions.get(location);
        if (action == null || action.equals("")) {
            return false;
        } else {
            performTask(action);
            return true;
        }
    }

    public void performTask(ActionBundle bundle) {
        performTask(bundle.action);
    }

    public void performTask(String action) {
        // null: throw it out
        if (action == null) {
            return;
        }
        // not a system action, should be intent
        if(!action.startsWith(SYSTEM_PREFIX)) {
            Intent intent = ActionBundle.getIntent(action);
            if (intent == null) {
                return;
            }
            launchActivity(intent);
        } else if (action.equals(SYSTEMUI_TASK_NO_ACTION)) {
            return;
        } else if (action.equals(SYSTEMUI_TASK_KILL_PROCESS)) {
            killProcess();
        } else if (action.equals(SYSTEMUI_TASK_SCREENSHOT)) {
            takeScreenshot();
        //} else if (action.equals(SYSTEMUI_TASK_SCREENRECORD)) {
        //    takeScreenrecord();
            // } else if (action.equals(SYSTEMUI_TASK_AUDIORECORD)) {
            // takeAudiorecord();
        //} else if (action.equals(SYSTEMUI_TASK_EXPANDED_DESKTOP)) {
        //    toggleExpandedDesktop();
        } else if (action.equals(SYSTEMUI_TASK_SCREENOFF)) {
            screenOff();
        } else if (action.equals(SYSTEMUI_TASK_ASSIST)) {
            launchAssistAction();
        } else if (action.equals(SYSTEMUI_TASK_POWER_MENU)) {
            showPowerMenu();
        //} else if (action.equals(SYSTEMUI_TASK_TORCH)) {
        //    toggleTorch();
        } else if (action.equals(SYSTEMUI_TASK_CAMERA)) {
            launchCamera();
        } else if (action.equals(SYSTEMUI_TASK_WIFI)) {
            toggleWifi();
        } else if (action.equals(SYSTEMUI_TASK_WIFIAP)) {
            toggleWifiAP();
        } else if (action.equals(SYSTEMUI_TASK_BT)) {
            toggleBluetooth();
        } else if (action.equals(SYSTEMUI_TASK_RECENTS)) {
            toggleRecentApps();
        } else if (action.equals(SYSTEMUI_TASK_LAST_APP)) {
            switchToLastApp();
        } else if (action.equals(SYSTEMUI_TASK_SETTINGS_PANEL)) {
            StatusBarHelper.expandSettingsPanel();
        } else if (action.equals(SYSTEMUI_TASK_NOTIFICATION_PANEL)) {
            StatusBarHelper.expandNotificationPanel();
        } else if (action.equals(SYSTEMUI_TASK_VOICE_SEARCH)) {
            launchVoiceSearch();
        } else if (action.equals(SYSTEMUI_TASK_APP_SEARCH)) {
            triggerVirtualKeypress(KeyEvent.KEYCODE_SEARCH);
        } else if (action.equals(SYSTEMUI_TASK_MENU)) {
            triggerVirtualKeypress(KeyEvent.KEYCODE_MENU);
        } else if (action.equals(SYSTEMUI_TASK_BACK)) {
            triggerVirtualKeypress(KeyEvent.KEYCODE_BACK);
        } else if (action.equals(SYSTEMUI_TASK_HOME)) {
            triggerVirtualKeypress(KeyEvent.KEYCODE_HOME);
        } else {
            postActionEventHandled(false);
        }
    }

    public Handler getHandler() {
        return H;
    }

    private Handler H = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {

            }
        }
    };

    private void launchActivity(Intent intent) {
        try {
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
            postActionEventHandled(true);
        } catch (Exception e) {
            Log.i(TAG, "Unable to launch activity " + e);
            postActionEventHandled(false);
            String uri = intent.toUri(0);
            handleAction(uri);
        }
    }

    private void switchToLastApp() {
        final ActivityManager am =
                (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.RunningTaskInfo lastTask = getLastTask(am);

        if (lastTask != null) {
            final ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext,
                    com.android.internal.R.anim.last_app_in,
                    com.android.internal.R.anim.last_app_out);
            am.moveTaskToFront(lastTask.id, ActivityManager.MOVE_TASK_NO_USER_ACTION,
                    opts.toBundle());
        }
    }

    private ActivityManager.RunningTaskInfo getLastTask(final ActivityManager am) {
        final String defaultHomePackage = resolveCurrentLauncherPackage();
        List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(5);

        for (int i = 1; i < tasks.size(); i++) {
            String packageName = tasks.get(i).topActivity.getPackageName();
            if (!packageName.equals(defaultHomePackage)
                    && !packageName.equals(mContext.getPackageName())
                    && !packageName.equals(SYSTEMUI)) {
                return tasks.get(i);
            }
        }
        return null;
    }

    private String resolveCurrentLauncherPackage() {
        final Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME);
        final PackageManager pm = mContext.getPackageManager();
        final ResolveInfo launcherInfo = pm.resolveActivity(launcherIntent, 0);
        return launcherInfo.activityInfo.packageName;
    }

    private static void sendCloseSystemWindows(String reason) {
        if (ActivityManagerNative.isSystemReady()) {
            try {
                ActivityManagerNative.getDefault().closeSystemDialogs(reason);
            } catch (RemoteException e) {
            }
        }
    }

/*
    private void toggleExpandedDesktop() {
        ContentResolver cr = mContext.getContentResolver();
        String newVal = "";
        String currentVal = Settings.Global.getString(cr, Settings.Global.POLICY_CONTROL);
        if (currentVal == null) {
            currentVal = newVal;
        }
        if ("".equals(currentVal)) {
            newVal = "immersive.full=*";
        }
        Settings.Global.putString(cr, Settings.Global.POLICY_CONTROL, newVal);
        if (newVal.equals("")) {
            WindowManagerPolicyControl.reloadFromSetting(mContext);
        }
    }
*/

    private void launchVoiceSearch() {
        sendCloseSystemWindows("assist");
        // launch the search activity
        Intent intent = new Intent(Intent.ACTION_SEARCH_LONG_PRESS);
        try {
            // TODO: This only stops the factory-installed search manager.
            // Need to formalize an API to handle others
            SearchManager searchManager = (SearchManager) mContext
                    .getSystemService(Context.SEARCH_SERVICE);
            if (searchManager != null) {
                searchManager.stopSearch();
            }
            launchActivity(intent);
        } catch (ActivityNotFoundException e) {
            Slog.w(TAG, "No assist activity installed", e);
        }
    }

    private void triggerVirtualKeypress(final int keyCode) {
        InputManager im = InputManager.getInstance();
        long now = SystemClock.uptimeMillis();

        final KeyEvent downEvent = new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        final KeyEvent upEvent = KeyEvent.changeAction(downEvent, KeyEvent.ACTION_UP);

        im.injectInputEvent(downEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        im.injectInputEvent(upEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    private void launchCamera() {
        Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        PackageManager pm = mContext.getPackageManager();
        final ResolveInfo mInfo = pm.resolveActivity(i, 0);
        Intent intent = new Intent().setComponent(new ComponentName(mInfo.activityInfo.packageName,
                mInfo.activityInfo.name));
        launchActivity(intent);
    }

    private void toggleWifi() {
        WifiManager wfm = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        wfm.setWifiEnabled(!wfm.isWifiEnabled());
    }

    private void toggleBluetooth() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        boolean enabled = bluetoothAdapter.isEnabled();
        if (enabled) {
            bluetoothAdapter.disable();
        } else {
            bluetoothAdapter.enable();
        }
    }

    private void toggleWifiAP() {
        WifiManager wfm = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        int state = wfm.getWifiApState();
        switch (state) {
            case WifiManager.WIFI_AP_STATE_ENABLING:
            case WifiManager.WIFI_AP_STATE_ENABLED:
                setSoftapEnabled(wfm, false);
                break;
            case WifiManager.WIFI_AP_STATE_DISABLING:
            case WifiManager.WIFI_AP_STATE_DISABLED:
                setSoftapEnabled(wfm, true);
                break;
        }
    }

    private void setSoftapEnabled(WifiManager wm, boolean enable) {
        final ContentResolver cr = mContext.getContentResolver();
        /**
         * Disable Wifi if enabling tethering
         */
        int wifiState = wm.getWifiState();
        if (enable && ((wifiState == WifiManager.WIFI_STATE_ENABLING) ||
                (wifiState == WifiManager.WIFI_STATE_ENABLED))) {
            wm.setWifiEnabled(false);
            Settings.Global.putInt(cr, Settings.Global.WIFI_SAVED_STATE, 1);
        }

        // Turn on the Wifi AP
        wm.setWifiApEnabled(null, enable);

        /**
         * If needed, restore Wifi on tether disable
         */
        if (!enable) {
            int wifiSavedState = 0;
            try {
                wifiSavedState = Settings.Global.getInt(cr, Settings.Global.WIFI_SAVED_STATE);
            } catch (Settings.SettingNotFoundException e) {
                // Do nothing here
            }
            if (wifiSavedState == 1) {
                wm.setWifiEnabled(true);
                Settings.Global.putInt(cr, Settings.Global.WIFI_SAVED_STATE, 0);
            }
        }
    }

/*
    private void toggleTorch() {
        try {
            ITorchService torchService = ITorchService.Stub.asInterface(ServiceManager
                    .getService(Context.TORCH_SERVICE));
            torchService.toggleTorch();
        } catch (Exception e) {
            Log.e(TAG, "Exception thrown acquiring torch service" + e.toString());
        }
    }
*/
    /**
     * functions needed for taking screenhots. This leverages the built in ICS
     * screenshot functionality
     */
    final Object mScreenshotLock = new Object();
    static ServiceConnection mScreenshotConnection = null;

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
                        Handler h = new Handler(H.getLooper()) {
                            @Override
                            public void handleMessage(Message msg) {
                                synchronized (mScreenshotLock) {
                                    if (mScreenshotConnection == myConn) {
                                        mContext.unbindService(mScreenshotConnection);
                                        mScreenshotConnection = null;
                                        H.removeCallbacks(mScreenshotTimeout);
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
            if (mContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
                mScreenshotConnection = conn;
                H.postDelayed(mScreenshotTimeout, 10000);
            }
        }
    }

    private void takeScreenrecord() {
        mContext.sendBroadcastAsUser(new Intent(INTENT_TOGGLE_SCREENRECORD), new UserHandle(
                UserHandle.USER_ALL));
    }

    private void killProcess() {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.FORCE_STOP_PACKAGES) == PackageManager.PERMISSION_GRANTED
                && !isLockTaskOn()) {
            try {
                final Intent intent = new Intent(Intent.ACTION_MAIN);
                String defaultHomePackage = "com.android.launcher";
                intent.addCategory(Intent.CATEGORY_HOME);
                final ResolveInfo res = mContext.getPackageManager()
                        .resolveActivity(intent, 0);
                if (res.activityInfo != null
                        && !res.activityInfo.packageName.equals("android")) {
                    defaultHomePackage = res.activityInfo.packageName;
                }
                IActivityManager am = ActivityManagerNative.getDefault();
                List<RunningAppProcessInfo> apps = am.getRunningAppProcesses();
                for (RunningAppProcessInfo appInfo : apps) {
                    int uid = appInfo.uid;
                    // Make sure it's a foreground user application (not system,
                    // root, phone, etc.)
                    if (uid >= Process.FIRST_APPLICATION_UID
                            && uid <= Process.LAST_APPLICATION_UID
                            && appInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        if (appInfo.pkgList != null
                                && (appInfo.pkgList.length > 0)) {
                            for (String pkg : appInfo.pkgList) {
                                if (!pkg.equals("com.android.systemui")
                                        && !pkg.equals(defaultHomePackage)) {
                                    am.forceStopPackage(pkg,
                                            UserHandle.USER_CURRENT);
                                    postActionEventHandled(true);
                                    break;
                                }
                            }
                        } else {
                            Process.killProcess(appInfo.pid);
                            postActionEventHandled(true);
                            break;
                        }
                    }
                }
            } catch (RemoteException remoteException) {
                Log.d("ActionHandler", "Caller cannot kill processes, aborting");
                postActionEventHandled(false);
            }
        } else {
            Log.d("ActionHandler", "Caller cannot kill processes, aborting");
            postActionEventHandled(false);
        }
    }

    final Object mAudiorecordLock = new Object();
    static ServiceConnection mAudiorecordConnection = null;

    final Runnable mAudiorecordTimeout = new Runnable() {
        @Override
        public void run() {
            synchronized (mAudiorecordLock) {
                if (mAudiorecordConnection != null) {
                    mContext.unbindService(mAudiorecordConnection);
                    mAudiorecordConnection = null;
                }
            }
        }
    };

    // Assume this is called from the Handler thread.
    private void takeAudiorecord() {
        synchronized (mAudiorecordLock) {
            if (mAudiorecordConnection != null) {
                return;
            }
            ComponentName cn = new ComponentName("com.android.systemui",
                    "com.android.systemui.eos.AudioRecordService");
            Intent intent = new Intent();
            intent.setComponent(cn);
            ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    synchronized (mAudiorecordLock) {
                        Messenger messenger = new Messenger(service);
                        Message msg = Message.obtain(null, 1);
                        final ServiceConnection myConn = this;
                        Handler h = new Handler(H.getLooper()) {
                            @Override
                            public void handleMessage(Message msg) {
                                synchronized (mAudiorecordLock) {
                                    if (mAudiorecordConnection == myConn) {
                                        mContext.unbindService(mAudiorecordConnection);
                                        mAudiorecordConnection = null;
                                        H.removeCallbacks(mAudiorecordTimeout);
                                    }
                                }
                            }
                        };
                        msg.replyTo = new Messenger(h);
                        msg.arg1 = msg.arg2 = 0;
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
            if (mContext.bindServiceAsUser(
                    intent, conn, Context.BIND_AUTO_CREATE, UserHandle.CURRENT)) {
                mAudiorecordConnection = conn;
                // Set max to 2 hours, sounds reasonable
                H.postDelayed(mAudiorecordTimeout, 120 * 60 * 1000);
            }
        }
    }

    private void screenOff() {
        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        pm.goToSleep(SystemClock.uptimeMillis());
    }

    private void launchAssistAction() {
        sendCloseSystemWindows("assist");
        Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(mContext, true, UserHandle.USER_CURRENT);
        if (intent != null) {
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try {
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);
            } catch (ActivityNotFoundException e) {
                Slog.w(TAG, "No activity to handle assist action.", e);
            }
        }
    }

    public void turnOffLockTask() {
        try {
            ActivityManagerNative.getDefault().stopLockTaskModeOnCurrent();
        } catch (Exception e) {
        }
    }

    public boolean isLockTaskOn() {
        try {
            return ActivityManagerNative.getDefault().isInLockTaskMode();
        } catch (Exception e) {
        }
        return false;
    }

    private void showPowerMenu() {
        mContext.sendBroadcastAsUser(new Intent(INTENT_SHOW_POWER_MENU), new UserHandle(
                UserHandle.USER_ALL));
    }

    /**
     * This method is called after an action is performed. This is useful for
     * subclasses to override, such as the one in the lock screen. As you need
     * to unlock the device after performing an action.
     * 
     * @param actionWasPerformed
     */
    protected boolean postActionEventHandled(boolean actionWasPerformed) {
        return actionWasPerformed;
    }

    /**
     * This the the fall over method that is called if this base class cannot
     * process an action. You do not need to manually call
     * {@link postActionEventHandled}
     * 
     * @param action
     * @return
     */
    public abstract boolean handleAction(String action);
}
