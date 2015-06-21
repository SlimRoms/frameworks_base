/*
* Copyright (C) 2014 SlimRoms Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.internal.util.slim;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.hardware.ITorchService;
import android.media.AudioManager;
import android.media.session.MediaSessionLegacyHelper;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.MediaStore;
import android.util.Log;
import android.view.InputDevice;
import android.view.IWindowManager;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerPolicyControl;
import android.widget.Toast;

import com.android.internal.statusbar.IStatusBarService;

import java.net.URISyntaxException;

public class Action {

    private static final int MSG_INJECT_KEY_DOWN = 1066;
    private static final int MSG_INJECT_KEY_UP = 1067;

    private static final int STATE_ENABLE_FOR_ALL = 1;
    private static final int STATE_USER_CONFIGURABLE = 2;
    private static int mExpandedDesktopState;

    private static Context mContext;

    public static void processAction(Context context, String action, boolean isLongpress) {
        processActionWithOptions(context, action, isLongpress, true);
    }

    public static void processActionWithOptions(Context context,
            String action, boolean isLongpress, boolean collapseShade) {

            mContext = context;
            mExpandedDesktopState = getExpandedDesktopState(mContext.getContentResolver());

            if (action == null || action.equals(ActionConstants.ACTION_NULL)) {
                return;
            }

            boolean isKeyguardShowing = false;
            try {
                isKeyguardShowing =
                        WindowManagerGlobal.getWindowManagerService().isKeyguardLocked();
            } catch (RemoteException e) {
                Log.w("Action", "Error getting window manager service", e);
            }

            IStatusBarService barService = IStatusBarService.Stub.asInterface(
                    ServiceManager.getService(Context.STATUS_BAR_SERVICE));
            if (barService == null) {
                return; // ouch
            }

            final IWindowManager windowManagerService = IWindowManager.Stub.asInterface(
                    ServiceManager.getService(Context.WINDOW_SERVICE));
           if (windowManagerService == null) {
               return; // ouch
           }

            boolean isKeyguardSecure = false;
            try {
                isKeyguardSecure = windowManagerService.isKeyguardSecure();
            } catch (RemoteException e) {
            }

            // process the actions
            if (action.equals(ActionConstants.ACTION_HOME)) {
                triggerVirtualKeypress(KeyEvent.KEYCODE_HOME, isLongpress);
                return;
            } else if (action.equals(ActionConstants.ACTION_BACK)) {
                triggerVirtualKeypress(KeyEvent.KEYCODE_BACK, isLongpress);
                return;
            } else if (action.equals(ActionConstants.ACTION_SEARCH)) {
                triggerVirtualKeypress(KeyEvent.KEYCODE_SEARCH, isLongpress);
                return;
            } else if (action.equals(ActionConstants.ACTION_KILL)) {
                if (isKeyguardShowing) return;
                try {
                    barService.toggleKillApp();
                } catch (RemoteException e) {}
                return;
            } else if (action.equals(ActionConstants.ACTION_NOTIFICATIONS)) {
                if (isKeyguardShowing && isKeyguardSecure) {
                    return;
                }
                try {
                    barService.expandNotificationsPanel();
                } catch (RemoteException e) {
                }
                return;
            } else if (action.equals(ActionConstants.ACTION_SETTINGS_PANEL)) {
                if (isKeyguardShowing && isKeyguardSecure) {
                    return;
                }
                try {
                    barService.expandSettingsPanel();
                } catch (RemoteException e) {}
            } else if (action.equals(ActionConstants.ACTION_LAST_APP)) {
                if (isKeyguardShowing) {
                    return;
                }
                try {
                    barService.toggleLastApp();
                } catch (RemoteException e) {
                }
                return;
            } else if (action.equals(ActionConstants.ACTION_TORCH)) {
                try {
                    ITorchService torchService = ITorchService.Stub.asInterface(
                            ServiceManager.getService(Context.TORCH_SERVICE));
                    torchService.toggleTorch();
                } catch (RemoteException e) {
                }
                return;
            } else if (action.equals(ActionConstants.ACTION_POWER_MENU)) {
                try {
                    windowManagerService.toggleGlobalMenu();
                } catch (RemoteException e) {
                }
                return;
            } else if (action.equals(ActionConstants.ACTION_MENU)
                    || action.equals(ActionConstants.ACTION_MENU_BIG)) {
                triggerVirtualKeypress(KeyEvent.KEYCODE_MENU, isLongpress);
                return;
            } else if (action.equals(ActionConstants.ACTION_IME_NAVIGATION_LEFT)) {
                triggerVirtualKeypress(KeyEvent.KEYCODE_DPAD_LEFT, isLongpress);
                return;
            } else if (action.equals(ActionConstants.ACTION_IME_NAVIGATION_RIGHT)) {
                triggerVirtualKeypress(KeyEvent.KEYCODE_DPAD_RIGHT, isLongpress);
                return;
            } else if (action.equals(ActionConstants.ACTION_IME_NAVIGATION_UP)) {
                triggerVirtualKeypress(KeyEvent.KEYCODE_DPAD_UP, isLongpress);
                return;
            } else if (action.equals(ActionConstants.ACTION_IME_NAVIGATION_DOWN)) {
                triggerVirtualKeypress(KeyEvent.KEYCODE_DPAD_DOWN, isLongpress);
                return;
            } else if (action.equals(ActionConstants.ACTION_POWER)) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                pm.goToSleep(SystemClock.uptimeMillis());
                return;
            } else if (action.equals(ActionConstants.ACTION_IME)) {
                if (isKeyguardShowing) {
                    return;
                }
                context.sendBroadcastAsUser(
                        new Intent("android.settings.SHOW_INPUT_METHOD_PICKER"),
                        new UserHandle(UserHandle.USER_CURRENT));
                return;
            } else if (action.equals(ActionConstants.ACTION_PIE)) {
                boolean pieState = isPieEnabled(context);
                if (pieState && !isNavBarEnabled(context) && isNavBarDefault(context)) {
                    Toast.makeText(context,
                            com.android.internal.R.string.disable_pie_navigation_error,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Settings.System.putIntForUser(
                        context.getContentResolver(),
                        Settings.System.PIE_CONTROLS,
                        pieState ? 0 : 1, UserHandle.USER_CURRENT);
                return;
            } else if (action.equals(ActionConstants.ACTION_NAVBAR)) {
                boolean navBarState = isNavBarEnabled(context);
                if (navBarState && !isPieEnabled(context) && isNavBarDefault(context)) {
                    Toast.makeText(context,
                            com.android.internal.R.string.disable_navigation_pie_error,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Settings.System.putIntForUser(
                        context.getContentResolver(),
                        Settings.System.NAVIGATION_BAR_SHOW,
                        navBarState ? 0 : 1, UserHandle.USER_CURRENT);
                return;
            } else if (action.equals(ActionConstants.ACTION_EXPANDED_DESKTOP)) {
                int state = mExpandedDesktopState;
                switch (state) {
                    case STATE_ENABLE_FOR_ALL:
                        userConfigurableSettings();
                        break;
                    case STATE_USER_CONFIGURABLE:
                        enableForAll();
                        break;
                }
                return;
            } else if (action.equals(ActionConstants.ACTION_KILL)) {
                if (isKeyguardShowing) {
                    return;
                }
                try {
                    barService.toggleKillApp();
                } catch (RemoteException e) {
                }
                return;
            } else if (action.equals(ActionConstants.ACTION_LAST_APP)) {
                if (isKeyguardShowing) {
                    return;
                }
                try {
                    barService.toggleLastApp();
                } catch (RemoteException e) {
                }
                return;
            } else if (action.equals(ActionConstants.ACTION_SCREENSHOT)) {
                try {
                    barService.toggleScreenshot();
                } catch (RemoteException e) {
                }
                return;
            } else if (action.equals(ActionConstants.ACTION_RECENTS)) {
                if (isKeyguardShowing) {
                    return;
                }
                try {
                    barService.toggleRecentApps();
                } catch (RemoteException e) {
                }
                return;
            } else if (action.equals(ActionConstants.ACTION_ASSIST)
                    || action.equals(ActionConstants.ACTION_KEYGUARD_SEARCH)) {
                Intent intent = ((SearchManager) context.getSystemService(Context.SEARCH_SERVICE))
                  .getAssistIntent(context, true, UserHandle.USER_CURRENT);
                if (intent == null) {
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.google.com"));
                }
                startActivity(context, intent, barService, isKeyguardShowing);
                return;
            } else if (action.equals(ActionConstants.ACTION_VOICE_SEARCH)) {
                // launch the search activity
                Intent intent = new Intent(Intent.ACTION_SEARCH_LONG_PRESS);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    // TODO: This only stops the factory-installed search manager.
                    // Need to formalize an API to handle others
                    SearchManager searchManager =
                            (SearchManager) context.getSystemService(Context.SEARCH_SERVICE);
                    if (searchManager != null) {
                        searchManager.stopSearch();
                    }
                    startActivity(context, intent, barService, isKeyguardShowing);
                } catch (ActivityNotFoundException e) {
                    Log.e("Action:", "No activity to handle assist long press action.", e);
                }
                return;
            } else if (action.equals(ActionConstants.ACTION_VIB)) {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if(am != null && ActivityManagerNative.isSystemReady()) {
                    if(am.getRingerMode() != AudioManager.RINGER_MODE_VIBRATE) {
                        am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                        Vibrator vib = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                        if(vib != null){
                            vib.vibrate(50);
                        }
                    }else{
                        am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                        ToneGenerator tg = new ToneGenerator(
                                AudioManager.STREAM_NOTIFICATION,
                                (int)(ToneGenerator.MAX_VOLUME * 0.85));
                        if(tg != null){
                            tg.startTone(ToneGenerator.TONE_PROP_BEEP);
                        }
                    }
                }
                return;
            } else if (action.equals(ActionConstants.ACTION_SILENT)) {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am != null && ActivityManagerNative.isSystemReady()) {
                    if (am.getRingerMode() != AudioManager.RINGER_MODE_SILENT) {
                        am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                    } else {
                        am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                        ToneGenerator tg = new ToneGenerator(
                                AudioManager.STREAM_NOTIFICATION,
                                (int)(ToneGenerator.MAX_VOLUME * 0.85));
                        if (tg != null) {
                            tg.startTone(ToneGenerator.TONE_PROP_BEEP);
                        }
                    }
                }
                return;
            } else if (action.equals(ActionConstants.ACTION_VIB_SILENT)) {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am != null && ActivityManagerNative.isSystemReady()) {
                    if (am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL) {
                        am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                        Vibrator vib = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                        if (vib != null) {
                            vib.vibrate(50);
                        }
                    } else if (am.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE) {
                        am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                    } else {
                        am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                        ToneGenerator tg = new ToneGenerator(
                                AudioManager.STREAM_NOTIFICATION,
                                (int)(ToneGenerator.MAX_VOLUME * 0.85));
                        if (tg != null) {
                            tg.startTone(ToneGenerator.TONE_PROP_BEEP);
                        }
                    }
                }
                return;
            } else if (action.equals(ActionConstants.ACTION_CAMERA)) {
                // ToDo: Send for secure keyguard secure camera intent.
                // We need to add support for it first.
                Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, null);
                startActivity(context, intent, barService, isKeyguardShowing);
                return;
            } else if (action.equals(ActionConstants.ACTION_MEDIA_PREVIOUS)) {
                dispatchMediaKeyWithWakeLock(KeyEvent.KEYCODE_MEDIA_PREVIOUS, context);
                return;
            } else if (action.equals(ActionConstants.ACTION_MEDIA_NEXT)) {
                dispatchMediaKeyWithWakeLock(KeyEvent.KEYCODE_MEDIA_NEXT, context);
                return;
            } else if (action.equals(ActionConstants.ACTION_MEDIA_PLAY_PAUSE)) {
                dispatchMediaKeyWithWakeLock(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, context);
                return;
            } else if (action.equals(ActionConstants.ACTION_WAKE_DEVICE)) {
                PowerManager powerManager =
                        (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (!powerManager.isScreenOn()) {
                    powerManager.wakeUp(SystemClock.uptimeMillis());
                }
                return;
            } else if (action.equals(ActionConstants.ACTION_SCREENSHOT)) {
                try {
                    barService.toggleScreenshot();
                } catch (RemoteException e) {}
                return;
            } else {
                // we must have a custom uri
                Intent intent = null;
                try {
                    intent = Intent.parseUri(action, 0);
                } catch (URISyntaxException e) {
                    Log.e("Action:", "URISyntaxException: [" + action + "]");
                    return;
                }
                startActivity(context, intent, barService, isKeyguardShowing);
                return;
            }

    }

    public static boolean isPieEnabled(Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.PIE_CONTROLS,
                0, UserHandle.USER_CURRENT) == 1;
    }

    public static boolean isNavBarEnabled(Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.NAVIGATION_BAR_SHOW,
                isNavBarDefault(context) ? 1 : 0, UserHandle.USER_CURRENT) == 1;
    }

    public static boolean isNavBarDefault(Context context) {
        return context.getResources().getBoolean(
                com.android.internal.R.bool.config_showNavigationBar);
    }

    public static boolean isActionKeyEvent(String action) {
        if (action.equals(ActionConstants.ACTION_HOME)
                || action.equals(ActionConstants.ACTION_BACK)
                || action.equals(ActionConstants.ACTION_SEARCH)
                || action.equals(ActionConstants.ACTION_MENU)
                || action.equals(ActionConstants.ACTION_MENU_BIG)
                || action.equals(ActionConstants.ACTION_NULL)) {
            return true;
        }
        return false;
    }

    private static void startActivity(Context context, Intent intent,
            IStatusBarService barService, boolean isKeyguardShowing) {
        if (intent == null) {
            return;
        }
        if (isKeyguardShowing) {
            // Have keyguard show the bouncer and launch the activity if the user succeeds.
            try {
                barService.showCustomIntentAfterKeyguard(intent);
            } catch (RemoteException e) {
                Log.w("Action", "Error starting custom intent on keyguard", e);
            }
        } else {
            // otherwise let us do it here
            try {
                WindowManagerGlobal.getWindowManagerService().dismissKeyguard();
            } catch (RemoteException e) {
                Log.w("Action", "Error dismissing keyguard", e);
            }
            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivityAsUser(intent,
                    new UserHandle(UserHandle.USER_CURRENT));
        }
    }

    private static void dispatchMediaKeyWithWakeLock(int keycode, Context context) {
        if (ActivityManagerNative.isSystemReady()) {
            KeyEvent event = new KeyEvent(SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keycode, 0);
            MediaSessionLegacyHelper.getHelper(context).sendMediaButtonEvent(event, true);
            event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
            MediaSessionLegacyHelper.getHelper(context).sendMediaButtonEvent(event, true);
        }
    }

    public static void triggerVirtualKeypress(final int keyCode, boolean longpress) {
        InputManager im = InputManager.getInstance();
        long now = SystemClock.uptimeMillis();
        int downflags = 0;
        int upflags = 0;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
            || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            || keyCode == KeyEvent.KEYCODE_DPAD_UP
            || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            downflags = upflags = KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE;
        } else {
            downflags = upflags = KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY;
        }
        if (longpress) {
            downflags |= KeyEvent.FLAG_LONG_PRESS;
        }

        final KeyEvent downEvent = new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                downflags,
                InputDevice.SOURCE_KEYBOARD);
        im.injectInputEvent(downEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);

        final KeyEvent upEvent = new KeyEvent(now, now, KeyEvent.ACTION_UP,
                keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                upflags,
                InputDevice.SOURCE_KEYBOARD);
        im.injectInputEvent(upEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    private static int getExpandedDesktopState(ContentResolver cr) {
        String value = Settings.Global.getString(cr, Settings.Global.POLICY_CONTROL);
        if ("immersive.full=*".equals(value)) {
            return STATE_ENABLE_FOR_ALL;
        }
        return STATE_USER_CONFIGURABLE;
    }

    protected static void toggleState() {
        int state = mExpandedDesktopState;
        switch (state) {
            case STATE_ENABLE_FOR_ALL:
                userConfigurableSettings();
                break;
            case STATE_USER_CONFIGURABLE:
                enableForAll();
                break;
        }
    }

    private static void userConfigurableSettings() {
        mExpandedDesktopState = STATE_USER_CONFIGURABLE;
        writeValue("");
        WindowManagerPolicyControl.reloadFromSetting(mContext);
    }

    private static  void enableForAll() {
        mExpandedDesktopState = STATE_ENABLE_FOR_ALL;
        writeValue("immersive.full=*");
    }

    private static void writeValue(String value) {
        Settings.Global.putString(mContext.getContentResolver(),
             Settings.Global.POLICY_CONTROL, value);
    }

}
