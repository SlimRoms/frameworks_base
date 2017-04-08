/*
 *Copyright (c) 2015-2016, The Linux Foundation. All rights reserved.
 *
 *Redistribution and use in source and binary forms, with or without
 *modification, are permitted provided that the following conditions are
 *met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *   * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 *THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 *WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 *MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 *ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 *BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 *CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 *SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 *BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 *WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 *OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 *IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.server;

import dalvik.system.PathClassLoader;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import android.net.Network;
import android.net.NetworkStats;
import android.os.Environment;
import android.util.Slog;
import android.util.Log;

public class NetPluginDelegate {

    private static final String TAG = "ConnectivityExtension";
    private static final boolean LOGV = false;

    private static Class tetherExtensionClass = null;
    private static Object tetherExtensionObj = null;

    public static void getTetherStats(NetworkStats uidStats, NetworkStats devStats,
            NetworkStats xtStats) {
        if (LOGV) Slog.v(TAG, "getTetherStats() E");
        if(!loadTetherExtJar()) return;
        try {
            tetherExtensionClass.getMethod("getTetherStats", NetworkStats.class,
                    NetworkStats.class, NetworkStats.class).invoke(tetherExtensionObj, uidStats,
                    devStats, xtStats);
        } catch (InvocationTargetException | SecurityException | NoSuchMethodException e) {
            e.printStackTrace();
            Log.w(TAG, "Failed to invoke getTetherStats()");
        } catch (Exception e) {
            e.printStackTrace();
            Log.w(TAG, "Error calling getTetherStats Method on extension jar");
        }
        if (LOGV) Slog.v(TAG, "getTetherStats() X");
    }

    public static NetworkStats peekTetherStats() {
        if (LOGV) Slog.v(TAG, "peekTetherStats() E");
        NetworkStats ret_val = null;
        if(!loadTetherExtJar()) return ret_val;
        try {
            ret_val = (NetworkStats) tetherExtensionClass.getMethod("peekTetherStats")
                    .invoke(tetherExtensionObj);
        } catch (InvocationTargetException | SecurityException | NoSuchMethodException e) {
            e.printStackTrace();
            Log.w(TAG, "Failed to invoke peekTetherStats()");
        } catch (Exception e) {
            e.printStackTrace();
            Log.w(TAG, "Error calling peekTetherStats Method on extension jar");
        }
        if (LOGV) Slog.v(TAG, "peekTetherStats() X");
        return ret_val;
    }

    public static void natStarted(String intIface, String extIface) {
        if (LOGV) Slog.v(TAG, "natStarted() E");
        if(!loadTetherExtJar()) return;
        try {
            tetherExtensionClass.getMethod("natStarted", String.class, String.class).invoke(
                    tetherExtensionObj, intIface, extIface);
        } catch (InvocationTargetException | SecurityException | NoSuchMethodException e) {
            e.printStackTrace();
            Log.w(TAG, "Failed to invoke natStarted()");
        } catch (Exception e) {
            e.printStackTrace();
            Log.w(TAG, "Error calling natStarted Method on extension jar");
        }
        if (LOGV) Slog.v(TAG, "natStarted() X");
    }

    public static void natStopped(String intIface, String extIface) {
        if (LOGV) Slog.v(TAG, "natStopped() E");
        if(!loadTetherExtJar()) return;
        try {
            tetherExtensionClass.getMethod("natStopped", String.class, String.class).invoke(
                    tetherExtensionObj, intIface, extIface);
        } catch (InvocationTargetException | SecurityException | NoSuchMethodException e) {
            e.printStackTrace();
            Log.w(TAG, "Failed to invoke natStopped()");
        } catch (Exception e) {
            e.printStackTrace();
            Log.w(TAG, "Error calling natStopped Method on extension jar");
        }
        if (LOGV) Slog.v(TAG, "natStopped() X");
    }

    public static void setQuota(String iface, long quota) {
        if (LOGV) Slog.v(TAG, "setQuota(" + iface + ", " + quota + ") E");
        if(!loadTetherExtJar()) return;
        try {
            tetherExtensionClass.getMethod("setQuota", String.class, long.class).invoke(
                    tetherExtensionObj, iface, quota);
        } catch (InvocationTargetException | SecurityException | NoSuchMethodException e) {
            e.printStackTrace();
            Log.w(TAG, "Failed to invoke setQuota()");
        } catch (Exception e) {
            e.printStackTrace();
            Log.w(TAG, "Error calling setQuota Method on extension jar");
        }
        if (LOGV) Slog.v(TAG, "setQuota(" + iface + ", " + quota + ") X");
    }

    public static void setUpstream(Network net) {
        if (LOGV) Slog.v(TAG, "setUpstream(" + net + ") E");
        if(!loadTetherExtJar()) return;
        try {
            tetherExtensionClass.getMethod("setUpstream", Network.class).invoke(
                    tetherExtensionObj, net);
        } catch (InvocationTargetException | SecurityException | NoSuchMethodException e) {
            e.printStackTrace();
            Log.w(TAG, "Failed to invoke setUpstream()");
        } catch (Exception e) {
            e.printStackTrace();
            Log.w(TAG, "Error calling setUpstream Method on extension jar");
        }
        if (LOGV) Slog.v(TAG, "setUpstream(" + net + ") X");
    }


    private static synchronized boolean loadTetherExtJar() {
        final String realProvider = "com.qualcomm.qti.tetherstatsextension.TetherStatsReporting";
        final String realProviderPath = Environment.getRootDirectory().getAbsolutePath()
                + "/framework/ConnectivityExt.jar";
        if (tetherExtensionClass != null && tetherExtensionObj != null) {
            return true;
        }
        boolean pathExists = new File(realProviderPath).exists();
        if (!pathExists) {
            Log.w(TAG, "ConnectivityExt jar file not present");
            return false;
        }

        if (tetherExtensionClass == null && tetherExtensionObj == null) {
            if (LOGV) Slog.v(TAG, "loading ConnectivityExt jar");
            try {
                PathClassLoader classLoader = new PathClassLoader(realProviderPath,
                        ClassLoader.getSystemClassLoader());

                tetherExtensionClass = classLoader.loadClass(realProvider);
                tetherExtensionObj = tetherExtensionClass.newInstance();
                if (LOGV) Slog.v(TAG, "ConnectivityExt jar loaded");
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
                e.printStackTrace();
                Log.w(TAG, "Failed to find, instantiate or access ConnectivityExt jar ");
                return false;
            } catch (Exception e) {
                e.printStackTrace();
                Log.w(TAG, "unable to load ConnectivityExt jar");
                return false;
            }
        }
        return true;
    }
}
