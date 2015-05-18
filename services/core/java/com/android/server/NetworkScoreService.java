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
 * limitations under the License
 */

package com.android.server;

import android.Manifest.permission;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.INetworkScoreCache;
import android.net.INetworkScoreService;
import android.net.NetworkScoreManager;
import android.net.NetworkScorerAppManager;
import android.net.NetworkScorerAppManager.NetworkScorerAppData;
import android.net.ScoredNetwork;
import android.os.Binder;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backing service for {@link android.net.NetworkScoreManager}.
 * @hide
 */
public class NetworkScoreService extends INetworkScoreService.Stub {
    private static final String TAG = "NetworkScoreService";

    private final Context mContext;

    private final Map<Integer, INetworkScoreCache> mScoreCaches;

    /** Lock used to update mReceiver when scorer package changes occur. */
    private Object mReceiverLock = new Object[0];

    /** Clears scores when the active scorer package is no longer valid. */
    @GuardedBy("mReceiverLock")
    private ScorerChangedReceiver mReceiver;

    private class ScorerChangedReceiver extends BroadcastReceiver {
        final String mRegisteredPackage;

        ScorerChangedReceiver(String packageName) {
            mRegisteredPackage = packageName;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ((Intent.ACTION_PACKAGE_CHANGED.equals(action) ||
                    Intent.ACTION_PACKAGE_REPLACED.equals(action) ||
                    Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) &&
                    NetworkScorerAppManager.getActiveScorer(mContext) == null) {
                // Package change has invalidated a scorer.
                Log.i(TAG, "Package " + mRegisteredPackage
                        " is no longer valid, disabling scoring");
                setScorerInternal(null);
            }
        }
    }

    public NetworkScoreService(Context context) {
        mContext = context;
        mScoreCaches = new HashMap<>();
    }

    /** Called when the system is ready to run third-party code but before it actually does so. */
    void systemReady() {
        ContentResolver cr = mContext.getContentResolver();
        if (Settings.Global.getInt(cr, Settings.Global.NETWORK_SCORING_PROVISIONED, 0) == 0) {
            // On first run, we try to initialize the scorer to the one configured at build time.
            // This will be a no-op if the scorer isn't actually valid.
            String defaultPackage = mContext.getResources().getString(
                    R.string.config_defaultNetworkScorerPackageName);
            if (!TextUtils.isEmpty(defaultPackage)) {
                NetworkScorerAppManager.setActiveScorer(mContext, defaultPackage);
            }
            Settings.Global.putInt(cr, Settings.Global.NETWORK_SCORING_PROVISIONED, 1);
        }

        registerPackageReceiverIfNeeded();
    }

    private void registerPackageReceiverIfNeeded() {
        NetworkScorerAppData scorer = NetworkScorerAppManager.getActiveScorer(mContext);
        synchronized (mReceiverLock) {
            // Unregister the receiver if the current scorer has changed since last registration.
            if (mReceiver != null) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Unregistering receiver for " + mReceiver.mRegisteredPackage);
                }
                mContext.unregisterReceiver(mReceiver);
                mReceiver = null;
            }

            // Register receiver if a scorer is active.
            if (scorer != null) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
                filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
                filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
                filter.addDataScheme("package");
                filter.addDataSchemeSpecificPart(scorer.mPackageName,
                        PatternMatcher.PATTERN_LITERAL);
                mReceiver = new ScorerChangedReceiver(scorer.mPackageName);
                // TODO: Need to update when we support per-user scorers.
                mContext.registerReceiverAsUser(mReceiver, UserHandle.OWNER, filter, null, null);
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Registered receiver for " + scorer.mPackageName);
                }
            }
        }
    }

    @Override
    public boolean updateScores(ScoredNetwork[] networks) {
        if (!NetworkScorerAppManager.isCallerActiveScorer(mContext, getCallingUid())) {
            throw new SecurityException("Caller with UID " + getCallingUid()
                    " is not the active scorer.");
        }

        // Separate networks by type.
        Map<Integer, List<ScoredNetwork>> networksByType = new HashMap<>();
        for (ScoredNetwork network : networks) {
            List<ScoredNetwork> networkList = networksByType.get(network.networkKey.type);
            if (networkList == null) {
                networkList = new ArrayList<>();
                networksByType.put(network.networkKey.type, networkList);
            }
            networkList.add(network);
        }

        // Pass the scores of each type down to the appropriate network scorer.
        for (Map.Entry<Integer, List<ScoredNetwork>> entry : networksByType.entrySet()) {
            INetworkScoreCache scoreCache = mScoreCaches.get(entry.getKey());
            if (scoreCache != null) {
                try {
                    scoreCache.updateScores(entry.getValue());
                } catch (RemoteException e) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Unable to update scores of type " + entry.getKey(), e);
                    }
                }
            } else if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "No scorer registered for type " + entry.getKey() + ", discarding");
            }
        }

        return true;
    }

    @Override
    public boolean clearScores() {
        // Only the active scorer or the system (who can broadcast BROADCAST_NETWORK_PRIVILEGED)
        // should be allowed to flush all scores.
        if (NetworkScorerAppManager.isCallerActiveScorer(mContext, getCallingUid()) ||
                mContext.checkCallingOrSelfPermission(permission.BROADCAST_NETWORK_PRIVILEGED) ==
                        PackageManager.PERMISSION_GRANTED) {
            clearInternal();
            return true;
        } else {
            throw new SecurityException(
                    "Caller is neither the active scorer nor the scorer manager.");
        }
    }

    @Override
    public boolean setActiveScorer(String packageName) {
        // TODO: For now, since SCORE_NETWORKS requires an app to be privileged, we allow such apps
        // to directly set the scorer app rather than having to use the consent dialog. The
        // assumption is that anyone bundling a scorer app with the system is trusted by the OEM to
        // do the right thing and not enable this feature without explaining it to the user.
        // In the future, should this API be opened to 3p apps, we will need to lock this down and
        // figure out another way to streamline the UX.

        // mContext.enforceCallingOrSelfPermission(permission.BROADCAST_NETWORK_PRIVILEGED, TAG);
        mContext.enforceCallingOrSelfPermission(permission.SCORE_NETWORKS, TAG);

        return setScorerInternal(packageName);
    }

    @Override
    public void disableScoring() {
        // Only the active scorer or the system (who can broadcast BROADCAST_NETWORK_PRIVILEGED)
        // should be allowed to disable scoring.
        if (NetworkScorerAppManager.isCallerActiveScorer(mContext, getCallingUid()) ||
                mContext.checkCallingOrSelfPermission(permission.BROADCAST_NETWORK_PRIVILEGED) ==
                        PackageManager.PERMISSION_GRANTED) {
            // The return value is discarded here because at this point, the call should always
            // succeed. The only reason for failure is if the new package is not a valid scorer, but
            // we're disabling scoring altogether here.
            setScorerInternal(null /* packageName */);
        } else {
            throw new SecurityException(
                    "Caller is neither the active scorer nor the scorer manager.");
        }
    }

    /** Set the active scorer. Callers are responsible for checking permissions as appropriate. */
    private boolean setScorerInternal(String packageName) {
        long token = Binder.clearCallingIdentity();
        try {
            // Preemptively clear scores even though the set operation could fail. We do this for
            // safety as scores should never be compared across apps; in practice, Settings should
            // only be allowing valid apps to be set as scorers, so failure here should be rare.
            clearInternal();
            boolean result = NetworkScorerAppManager.setActiveScorer(mContext, packageName);
            if (result) {
                registerPackageReceiverIfNeeded();
                Intent intent = new Intent(NetworkScoreManager.ACTION_SCORER_CHANGED);
                intent.putExtra(NetworkScoreManager.EXTRA_NEW_SCORER, packageName);
                mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            }
            return result;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** Clear scores. Callers are responsible for checking permissions as appropriate. */
    private void clearInternal() {
        Set<INetworkScoreCache> cachesToClear = getScoreCaches();

        for (INetworkScoreCache scoreCache : cachesToClear) {
            try {
                scoreCache.clearScores();
            } catch (RemoteException e) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Unable to clear scores", e);
                }
            }
        }
    }

    @Override
    public void registerNetworkScoreCache(int networkType, INetworkScoreCache scoreCache) {
        mContext.enforceCallingOrSelfPermission(permission.BROADCAST_NETWORK_PRIVILEGED, TAG);
        synchronized (mScoreCaches) {
            if (mScoreCaches.containsKey(networkType)) {
                throw new IllegalArgumentException(
                        "Score cache already registered for type " + networkType);
            }
            mScoreCaches.put(networkType, scoreCache);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mContext.enforceCallingOrSelfPermission(permission.DUMP, TAG);
        NetworkScorerAppData currentScorer = NetworkScorerAppManager.getActiveScorer(mContext);
        if (currentScorer == null) {
            writer.println("Scoring is disabled.");
            return;
        }
        writer.println("Current scorer: " + currentScorer.mPackageName);
        writer.flush();

        for (INetworkScoreCache scoreCache : getScoreCaches()) {
            try {
                scoreCache.asBinder().dump(fd, args);
            } catch (RemoteException e) {
                writer.println("Unable to dump score cache");
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Unable to dump score cache", e);
                }
            }
        }
    }

    /**
     * Returns a set of all score caches that are currently active.
     *
     * <p>May be used to perform an action on all score caches without potentially strange behavior
     * if a new scorer is registered during that action's execution.
     */
    private Set<INetworkScoreCache> getScoreCaches() {
        synchronized (mScoreCaches) {
            return new HashSet<>(mScoreCaches.values());
        }
    }
}
