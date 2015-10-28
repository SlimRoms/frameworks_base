// Version 1.3.3

// Changelog

// Version 1.3.3
//   - increased MAX_NO_ARGS to 10

// Version 1.3.2
// 	- bug setting app arg
//	- pulled provider column names out of function

// For usage examples see http://tasker.dinglisch.net/invoketasks.html

package com.android.internal.util.slim;

import java.util.List;
import java.util.Random;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

public class TaskerIntent extends Intent {

    public final static String TASKER_PACKAGE = "net.dinglisch.android.tasker";
    public final static String TASKER_PACKAGE_MARKET = TASKER_PACKAGE + "m";

    // URIs
    public final static String TASKER_TASKS_URI = "content://" + TASKER_PACKAGE + "/tasks";
    private final static String TASKER_PREFS_URI = "content://" + TASKER_PACKAGE + "/prefs";

    // Intent actions
    public final static String ACTION_TASK = TASKER_PACKAGE + ".ACTION_TASK";

    // Intent parameters
    public final static String EXTRA_TASK_NAME  = "task_name";

    // Content provider columns
    public static final String PROVIDER_COL_NAME_EXTERNAL_ACCESS = "ext_access";
    public static final String PROVIDER_COL_NAME_ENABLED = "enabled";

    // Content resolver columns
    public static final String COLUMN_NAME = "name";	

    // Misc
    private final static String PERMISSION_RUN_TASKS = TASKER_PACKAGE + ".PERMISSION_RUN_TASKS";

    // result values for TestSend
    // NotInstalled: Tasker package not found on device
    // NotEnabled: Tasker is not enabled
    // AccessBlocked: user prefs disallow external access
    // OK: you should be able to send a task to run. Still need to listen for result
    //     for e.g. task not found
    public static enum Status { NotInstalled, NotEnabled, AccessBlocked, OK };

// -------------------------- PRIVATE VARS ---------------------------- //

    private final static String TAG = "TaskerIntent";
    private final static String EXTRA_INTENT_VERSION_NUMBER = "version_number";
    private final static String INTENT_VERSION_NUMBER = "1.1";
    private static Random rand = new Random();

// -------------------------- PUBLIC METHODS ---------------------------- //

    // test we can send a TaskerIntent to Tasker
    // use *before* sending an intent
    // still need to test the *result after* sending intent
    public static Status testStatus(Context context) {
        Status result;
	
        if (!taskerInstalled(context))
            result = Status.NotInstalled;
        else if (!TaskerIntent.prefSet(context, PROVIDER_COL_NAME_ENABLED))
            result = Status.NotEnabled;
        else if (!TaskerIntent.prefSet(context, PROVIDER_COL_NAME_EXTERNAL_ACCESS))
            result = Status.AccessBlocked;
        else
            result = Status.OK;

        return result;
    }

    public static int getStatusStringRes(Status status) {
        switch (status) {
            case NotInstalled:
                return com.android.internal.R.string.tasker_not_installed;
            case NotEnabled:
                return com.android.internal.R.string.tasker_not_enabled;            
            case AccessBlocked:
                return com.android.internal.R.string.tasker_no_external_access;
        }
        return -1;
    }

// ------------------------------------- INSTANCE METHODS ----------------------------- //

    public TaskerIntent(String taskName) {
        super(ACTION_TASK);
        setRandomData();
        putMetaExtras(taskName);
    }

    public String getTaskName() {
        return getStringExtra(EXTRA_TASK_NAME);
    }

// -------------------- PRIVATE METHODS -------------------- //

    // Tasker has different package names for Play Store and non- versions
    // for historical reasons
    private static String getInstalledTaskerPackage(Context context) {
        String foundPackage = null;
        try {
            context.getPackageManager().getPackageInfo(TASKER_PACKAGE, 0);
            foundPackage = TASKER_PACKAGE;
        }
        catch (PackageManager.NameNotFoundException e) {
        }

        try {
            context.getPackageManager().getPackageInfo(TASKER_PACKAGE_MARKET, 0);
            foundPackage = TASKER_PACKAGE_MARKET;
        }
        catch (PackageManager.NameNotFoundException e) {
        }

        return foundPackage;
    }

    // Check if Tasker installed 
    private static boolean taskerInstalled(Context context) {
        return (getInstalledTaskerPackage(context) != null);
    }

    private String getRandomString() {
        return Long.toString(rand.nextLong());
    }

    // so that if multiple TaskerIntents are used in PendingIntents there's virtually no
    // clash chance
    private void setRandomData() {
        setData(Uri.parse("id:"+getRandomString()));
    }

    private void putMetaExtras(String taskName) {
        putExtra(EXTRA_INTENT_VERSION_NUMBER, INTENT_VERSION_NUMBER);
        putExtra(EXTRA_TASK_NAME, taskName);
    }

    // for testing that Tasker is enabled and external access is allowed
    private static boolean prefSet(Context context, String col) {
        String [] proj = new String [] { col };
        Cursor c = context.getContentResolver()
            .query(Uri.parse(TASKER_PREFS_URI), proj, null, null, null);

        boolean acceptingFlag = false;
        if (c == null)
            Log.w(TAG, "no cursor for " + TASKER_PREFS_URI);
        else {
            c.moveToFirst();

            if (Boolean.TRUE.toString().equals(c.getString(0)))
            acceptingFlag = true;
            c.close();
        }

        return acceptingFlag;
    }
}
