/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.misc;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.ITaskStackListener;
import android.app.SearchManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import com.android.systemui.R;
import com.android.systemui.recents.AlternateRecentsComponent;
import com.android.systemui.recents.Constants;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Acts as a shim around the real system services that we need to access data from, and provides
 * a point of injection when testing UI.
 */
public class SystemServicesProxy {
    final static String TAG = "SystemServicesProxy";

    final static BitmapFactory.Options sBitmapOptions;

    AccessibilityManager mAccm;
    ActivityManager mAm;
    IActivityManager mIam;
    AppWidgetManager mAwm;
    PackageManager mPm;
    IPackageManager mIpm;
    SearchManager mSm;
    WindowManager mWm;
    Display mDisplay;
    String mRecentsPackage;
    ComponentName mAssistComponent;

    Bitmap mDummyIcon;
    int mDummyThumbnailWidth;
    int mDummyThumbnailHeight;
    Paint mBgProtectionPaint;
    Canvas mBgProtectionCanvas;

    static {
        sBitmapOptions = new BitmapFactory.Options();
        sBitmapOptions.inMutable = true;
    }

    /** Private constructor */
    public SystemServicesProxy(Context context) {
        mAccm = AccessibilityManager.getInstance(context);
        mAm = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mIam = ActivityManagerNative.getDefault();
        mAwm = AppWidgetManager.getInstance(context);
        mPm = context.getPackageManager();
        mIpm = AppGlobals.getPackageManager();
        mSm = (SearchManager) context.getSystemService(Context.SEARCH_SERVICE);
        mWm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mDisplay = mWm.getDefaultDisplay();
        mRecentsPackage = context.getPackageName();

        // Get the dummy thumbnail width/heights
        Resources res = context.getResources();
        int wId = com.android.internal.R.dimen.thumbnail_width;
        int hId = com.android.internal.R.dimen.thumbnail_height;
        mDummyThumbnailWidth = res.getDimensionPixelSize(wId);
        mDummyThumbnailHeight = res.getDimensionPixelSize(hId);

        // Create the protection paints
        mBgProtectionPaint = new Paint();
        mBgProtectionPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_ATOP));
        mBgProtectionPaint.setColor(0xFFffffff);
        mBgProtectionCanvas = new Canvas();

        // Resolve the assist intent
        Intent assist = mSm.getAssistIntent(context, false);
        if (assist != null) {
            mAssistComponent = assist.getComponent();
        }

        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            // Create a dummy icon
            mDummyIcon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            mDummyIcon.eraseColor(0xFF999999);
        }
    }

    /** Returns a list of the recents tasks */
    public List<ActivityManager.RecentTaskInfo> getRecentTasks(int numLatestTasks, int userId,
            boolean isTopTaskHome) {
        if (mAm == null) return null;

        // If we are mocking, then create some recent tasks
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            ArrayList<ActivityManager.RecentTaskInfo> tasks =
                    new ArrayList<ActivityManager.RecentTaskInfo>();
            int count = Math.min(numLatestTasks, Constants.DebugFlags.App.SystemServicesProxyMockTaskCount);
            for (int i = 0; i < count; i++) {
                // Create a dummy component name
                int packageIndex = i % Constants.DebugFlags.App.SystemServicesProxyMockPackageCount;
                ComponentName cn = new ComponentName("com.android.test" + packageIndex,
                        "com.android.test" + i + ".Activity");
                String description = "" + i + " - "
                        Long.toString(Math.abs(new Random().nextLong()), 36);
                // Create the recent task info
                ActivityManager.RecentTaskInfo rti = new ActivityManager.RecentTaskInfo();
                rti.id = rti.persistentId = i;
                rti.baseIntent = new Intent();
                rti.baseIntent.setComponent(cn);
                rti.description = description;
                rti.firstActiveTime = rti.lastActiveTime = i;
                if (i % 2 == 0) {
                    rti.taskDescription = new ActivityManager.TaskDescription(description,
                        Bitmap.createBitmap(mDummyIcon),
                        0xFF000000 | (0xFFFFFF & new Random().nextInt()));
                } else {
                    rti.taskDescription = new ActivityManager.TaskDescription();
                }
                tasks.add(rti);
            }
            return tasks;
        }

        // Remove home/recents/excluded tasks
        int minNumTasksToQuery = 10;
        int numTasksToQuery = Math.max(minNumTasksToQuery, numLatestTasks);
        List<ActivityManager.RecentTaskInfo> tasks = mAm.getRecentTasksForUser(numTasksToQuery,
                ActivityManager.RECENT_IGNORE_HOME_STACK_TASKS |
                ActivityManager.RECENT_IGNORE_UNAVAILABLE |
                ActivityManager.RECENT_INCLUDE_PROFILES |
                ActivityManager.RECENT_WITH_EXCLUDED, userId);

        // Break early if we can't get a valid set of tasks
        if (tasks == null) {
            return new ArrayList<ActivityManager.RecentTaskInfo>();
        }

        boolean isFirstValidTask = true;
        Iterator<ActivityManager.RecentTaskInfo> iter = tasks.iterator();
        while (iter.hasNext()) {
            ActivityManager.RecentTaskInfo t = iter.next();

            // NOTE: The order of these checks happens in the expected order of the traversal of the
            // tasks

            // Check the first non-recents task, include this task even if it is marked as excluded
            // from recents if we are currently in the app.  In other words, only remove excluded
            // tasks if it is not the first active task.
            boolean isExcluded = (t.baseIntent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    == Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
            if (isExcluded && (isTopTaskHome || !isFirstValidTask)) {
                iter.remove();
                continue;
            }
            isFirstValidTask = false;
        }

        return tasks.subList(0, Math.min(tasks.size(), numLatestTasks));
    }

    /** Returns a list of the running tasks */
    public List<ActivityManager.RunningTaskInfo> getRunningTasks(int numTasks) {
        if (mAm == null) return null;
        return mAm.getRunningTasks(numTasks);
    }

    /** Returns the top task. */
    public ActivityManager.RunningTaskInfo getTopMostTask() {
        List<ActivityManager.RunningTaskInfo> tasks = getRunningTasks(1);
        if (!tasks.isEmpty()) {
            return tasks.get(0);
        }
        return null;
    }

    /** Returns whether the recents is currently running */
    public boolean isRecentsTopMost(ActivityManager.RunningTaskInfo topTask,
            AtomicBoolean isHomeTopMost) {
        if (topTask != null) {
            ComponentName topActivity = topTask.topActivity;

            // Check if the front most activity is recents
            if (topActivity.getPackageName().equals(AlternateRecentsComponent.sRecentsPackage) &&
                    topActivity.getClassName().equals(AlternateRecentsComponent.sRecentsActivity)) {
                if (isHomeTopMost != null) {
                    isHomeTopMost.set(false);
                }
                return true;
            }

            if (isHomeTopMost != null) {
                isHomeTopMost.set(isInHomeStack(topTask.id));
            }
        }
        return false;
    }

    /** Returns whether the specified task is in the home stack */
    public boolean isInHomeStack(int taskId) {
        if (mAm == null) return false;

        // If we are mocking, then just return false
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            return false;
        }

        return mAm.isInHomeStack(taskId);
    }

    /** Returns the top task thumbnail for the given task id */
    public Bitmap getTaskThumbnail(int taskId) {
        if (mAm == null) return null;

        // If we are mocking, then just return a dummy thumbnail
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            Bitmap thumbnail = Bitmap.createBitmap(mDummyThumbnailWidth, mDummyThumbnailHeight,
                    Bitmap.Config.ARGB_8888);
            thumbnail.eraseColor(0xff333333);
            return thumbnail;
        }

        Bitmap thumbnail = SystemServicesProxy.getThumbnail(mAm, taskId);
        if (thumbnail != null) {
            thumbnail.setHasAlpha(false);
            // We use a dumb heuristic for now, if the thumbnail is purely transparent in the top
            // left pixel, then assume the whole thumbnail is transparent. Generally, proper
            // screenshots are always composed onto a bitmap that has no alpha.
            if (Color.alpha(thumbnail.getPixel(0, 0)) == 0) {
                mBgProtectionCanvas.setBitmap(thumbnail);
                mBgProtectionCanvas.drawRect(0, 0, thumbnail.getWidth(), thumbnail.getHeight(),
                        mBgProtectionPaint);
                mBgProtectionCanvas.setBitmap(null);
                Log.e(TAG, "Invalid screenshot detected from getTaskThumbnail()");
            }
        }
        return thumbnail;
    }

    /**
     * Returns a task thumbnail from the activity manager
     */
    public static Bitmap getThumbnail(ActivityManager activityManager, int taskId) {
        ActivityManager.TaskThumbnail taskThumbnail = activityManager.getTaskThumbnail(taskId);
        if (taskThumbnail == null) return null;

        Bitmap thumbnail = taskThumbnail.mainThumbnail;
        ParcelFileDescriptor descriptor = taskThumbnail.thumbnailFileDescriptor;
        if (thumbnail == null && descriptor != null) {
            thumbnail = BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(),
                    null, sBitmapOptions);
        }
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException e) {
            }
        }
        return thumbnail;
    }

    /** Moves a task to the front with the specified activity options */
    public void moveTaskToFront(int taskId, ActivityOptions opts) {
        if (mAm == null) return;
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) return;

        if (opts != null) {
            mAm.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME,
                    opts.toBundle());
        } else {
            mAm.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME);
        }
    }

    /** Removes the task */
    public void removeTask(int taskId) {
        if (mAm == null) return;
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) return;

        // Remove the task.
        mAm.removeTask(taskId);
    }

    /**
     * Returns the activity info for a given component name.
     *
     * @param cn The component name of the activity.
     * @param userId The userId of the user that this is for.
     */
    public ActivityInfo getActivityInfo(ComponentName cn, int userId) {
        if (mIpm == null) return null;
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) return new ActivityInfo();

        try {
            return mIpm.getActivityInfo(cn, PackageManager.GET_META_DATA, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Returns the activity info for a given component name.
     *
     * @param cn The component name of the activity.
     */
    public ActivityInfo getActivityInfo(ComponentName cn) {
        if (mPm == null) return null;
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) return new ActivityInfo();

        try {
            return mPm.getActivityInfo(cn, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    /** Returns the activity label */
    public String getActivityLabel(ActivityInfo info) {
        if (mPm == null) return null;

        // If we are mocking, then return a mock label
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            return "Recent Task";
        }

        return info.loadLabel(mPm).toString();
    }

    /**
     * Returns the activity icon for the ActivityInfo for a user, badging if
     * necessary.
     */
    public Drawable getActivityIcon(ActivityInfo info, int userId) {
        if (mPm == null) return null;

        // If we are mocking, then return a mock label
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) {
            return new ColorDrawable(0xFF666666);
        }

        Drawable icon = info.loadIcon(mPm);
        return getBadgedIcon(icon, userId);
    }

    /**
     * Returns the given icon for a user, badging if necessary.
     */
    public Drawable getBadgedIcon(Drawable icon, int userId) {
        if (userId != UserHandle.myUserId()) {
            icon = mPm.getUserBadgedIcon(icon, new UserHandle(userId));
        }
        return icon;
    }

    /** Returns the package name of the home activity. */
    public String getHomeActivityPackageName() {
        if (mPm == null) return null;
        if (Constants.DebugFlags.App.EnableSystemServicesProxy) return null;

        ArrayList<ResolveInfo> homeActivities = new ArrayList<ResolveInfo>();
        ComponentName defaultHomeActivity = mPm.getHomeActivities(homeActivities);
        if (defaultHomeActivity != null) {
            return defaultHomeActivity.getPackageName();
        } else if (homeActivities.size() == 1) {
            ResolveInfo info = homeActivities.get(0);
            if (info.activityInfo != null) {
                return info.activityInfo.packageName;
            }
        }
        return null;
    }

    /**
     * Returns whether the foreground user is the owner.
     */
    public boolean isForegroundUserOwner() {
        if (mAm == null) return false;

        return mAm.getCurrentUser() == UserHandle.USER_OWNER;
    }

    /**
     * Resolves and returns the first Recents widget from the same package as the global
     * assist activity.
     */
    public AppWidgetProviderInfo resolveSearchAppWidget() {
        if (mAwm == null) return null;
        if (mAssistComponent == null) return null;

        // Find the first Recents widget from the same package as the global assist activity
        List<AppWidgetProviderInfo> widgets = mAwm.getInstalledProviders(
                AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX);
        for (AppWidgetProviderInfo info : widgets) {
            if (info.provider.getPackageName().equals(mAssistComponent.getPackageName())) {
                return info;
            }
        }
        return null;
    }

    /**
     * Resolves and binds the search app widget that is to appear in the recents.
     */
    public Pair<Integer, AppWidgetProviderInfo> bindSearchAppWidget(AppWidgetHost host) {
        if (mAwm == null) return null;
        if (mAssistComponent == null) return null;

        // Find the first Recents widget from the same package as the global assist activity
        AppWidgetProviderInfo searchWidgetInfo = resolveSearchAppWidget();

        // Return early if there is no search widget
        if (searchWidgetInfo == null) return null;

        // Allocate a new widget id and try and bind the app widget (if that fails, then just skip)
        int searchWidgetId = host.allocateAppWidgetId();
        Bundle opts = new Bundle();
        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
        if (!mAwm.bindAppWidgetIdIfAllowed(searchWidgetId, searchWidgetInfo.provider, opts)) {
            host.deleteAppWidgetId(searchWidgetId);
            return null;
        }
        return new Pair<Integer, AppWidgetProviderInfo>(searchWidgetId, searchWidgetInfo);
    }

    /**
     * Returns the app widget info for the specified app widget id.
     */
    public AppWidgetProviderInfo getAppWidgetInfo(int appWidgetId) {
        if (mAwm == null) return null;

        return mAwm.getAppWidgetInfo(appWidgetId);
    }

    /**
     * Destroys the specified app widget.
     */
    public void unbindSearchAppWidget(AppWidgetHost host, int appWidgetId) {
        if (mAwm == null) return;

        // Delete the app widget
        host.deleteAppWidgetId(appWidgetId);
    }

    /**
     * Returns whether touch exploration is currently enabled.
     */
    public boolean isTouchExplorationEnabled() {
        if (mAccm == null) return false;

        return mAccm.isEnabled() && mAccm.isTouchExplorationEnabled();
    }

    /**
     * Returns a global setting.
     */
    public int getGlobalSetting(Context context, String setting) {
        ContentResolver cr = context.getContentResolver();
        return Settings.Global.getInt(cr, setting, 0);
    }

    /**
     * Returns a system setting.
     */
    public int getSystemSetting(Context context, String setting) {
        ContentResolver cr = context.getContentResolver();
        return Settings.System.getInt(cr, setting, 0);
    }

    /**
     * Returns the window rect.
     */
    public Rect getWindowRect() {
        Rect windowRect = new Rect();
        if (mWm == null) return windowRect;

        Point p = new Point();
        mWm.getDefaultDisplay().getRealSize(p);
        windowRect.set(0, 0, p.x, p.y);
        return windowRect;
    }

    /**
     * Takes a screenshot of the current surface.
     */
    public Bitmap takeScreenshot() {
        DisplayInfo di = new DisplayInfo();
        mDisplay.getDisplayInfo(di);
        return SurfaceControl.screenshot(di.getNaturalWidth(), di.getNaturalHeight());
    }

    /**
     * Takes a screenshot of the current app.
     */
    public Bitmap takeAppScreenshot() {
        return takeScreenshot();
    }

    /** Starts an activity from recents. */
    public boolean startActivityFromRecents(Context context, int taskId, String taskName,
            ActivityOptions options) {
        if (mIam != null) {
            try {
                mIam.startActivityFromRecents(taskId, options == null ? null : options.toBundle());
                return true;
            } catch (Exception e) {
                Console.logError(context,
                        context.getString(R.string.recents_launch_error_message, taskName));
            }
        }
        return false;
    }

    /** Starts an in-place animation on the front most application windows. */
    public void startInPlaceAnimationOnFrontMostApplication(ActivityOptions opts) {
        if (mIam == null) return;

        try {
            mIam.startInPlaceAnimationOnFrontMostApplication(opts);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Registers a task stack listener with the system. */
    public void registerTaskStackListener(ITaskStackListener listener) {
        if (mIam == null) return;

        try {
            mIam.registerTaskStackListener(listener);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
