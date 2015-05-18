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

package android.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Utility class for handling for communicating between bearer-specific
 * code and ConnectivityService.
 *
 * A bearer may have more than one NetworkAgent if it can simultaneously
 * support separate networks (IMS / Internet / MMS Apns on cellular, or
 * perhaps connections with different SSID or P2P for Wi-Fi).
 *
 * @hide
 */
public abstract class NetworkAgent extends Handler {
    private volatile AsyncChannel mAsyncChannel;
    private final String LOG_TAG;
    private static final boolean DBG = true;
    private static final boolean VDBG = false;
    private final Context mContext;
    private final ArrayList<Message>mPreConnectedQueue = new ArrayList<Message>();

    private static final int BASE = Protocol.BASE_NETWORK_AGENT;

    /**
     * Sent by ConnectivityService to the NetworkAgent to inform it of
     * suspected connectivity problems on its network.  The NetworkAgent
     * should take steps to verify and correct connectivity.
     */
    public static final int CMD_SUSPECT_BAD = BASE;

    /**
     * Sent by the NetworkAgent (note the EVENT vs CMD prefix) to
     * ConnectivityService to pass the current NetworkInfo (connection state).
     * Sent when the NetworkInfo changes, mainly due to change of state.
     * obj = NetworkInfo
     */
    public static final int EVENT_NETWORK_INFO_CHANGED = BASE + 1;

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * NetworkCapabilties.
     * obj = NetworkCapabilities
     */
    public static final int EVENT_NETWORK_CAPABILITIES_CHANGED = BASE + 2;

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * NetworkProperties.
     * obj = NetworkProperties
     */
    public static final int EVENT_NETWORK_PROPERTIES_CHANGED = BASE + 3;

    /* centralize place where base network score, and network score scaling, will be
     * stored, so as we can consistently compare apple and oranges, or wifi, ethernet and LTE
     */
    public static final int WIFI_BASE_SCORE = 60;

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * network score.
     * obj = network score Integer
     */
    public static final int EVENT_NETWORK_SCORE_CHANGED = BASE + 4;

    /**
     * Sent by the NetworkAgent to ConnectivityService to add new UID ranges
     * to be forced into this Network.  For VPNs only.
     * obj = UidRange[] to forward
     */
    public static final int EVENT_UID_RANGES_ADDED = BASE + 5;

    /**
     * Sent by the NetworkAgent to ConnectivityService to remove UID ranges
     * from being forced into this Network.  For VPNs only.
     * obj = UidRange[] to stop forwarding
     */
    public static final int EVENT_UID_RANGES_REMOVED = BASE + 6;

    /**
     * Sent by ConnectivitySerice to the NetworkAgent to inform the agent of the
     * networks status - whether we could use the network or could not, due to
     * either a bad network configuration (no internet link) or captive portal.
     *
     * arg1 = either {@code VALID_NETWORK} or {@code INVALID_NETWORK}
     */
    public static final int CMD_REPORT_NETWORK_STATUS = BASE + 7;

    public static final int VALID_NETWORK = 1;
    public static final int INVALID_NETWORK = 2;

     /**
     * Sent by the NetworkAgent to ConnectivityService to indicate this network was
     * explicitly selected.  This should be sent before the NetworkInfo is marked
     * CONNECTED so it can be given special treatment at that time.
     */
    public static final int EVENT_SET_EXPLICITLY_SELECTED = BASE + 8;

    public NetworkAgent(Looper looper, Context context, String logTag, NetworkInfo ni,
            NetworkCapabilities nc, LinkProperties lp, int score) {
        this(looper, context, logTag, ni, nc, lp, score, null);
    }

    public NetworkAgent(Looper looper, Context context, String logTag, NetworkInfo ni,
            NetworkCapabilities nc, LinkProperties lp, int score, NetworkMisc misc) {
        super(looper);
        LOG_TAG = logTag;
        mContext = context;
        if (ni == null || nc == null || lp == null) {
            throw new IllegalArgumentException();
        }

        if (VDBG) log("Registering NetworkAgent");
        ConnectivityManager cm = (ConnectivityManager)mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        cm.registerNetworkAgent(new Messenger(this), new NetworkInfo(ni),
                new LinkProperties(lp), new NetworkCapabilities(nc), score, misc);
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                if (mAsyncChannel != null) {
                    log("Received new connection while already connected!");
                } else {
                    if (VDBG) log("NetworkAgent fully connected");
                    AsyncChannel ac = new AsyncChannel();
                    ac.connected(null, this, msg.replyTo);
                    ac.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                            AsyncChannel.STATUS_SUCCESSFUL);
                    synchronized (mPreConnectedQueue) {
                        mAsyncChannel = ac;
                        for (Message m : mPreConnectedQueue) {
                            ac.sendMessage(m);
                        }
                        mPreConnectedQueue.clear();
                    }
                }
                break;
            }
            case AsyncChannel.CMD_CHANNEL_DISCONNECT: {
                if (VDBG) log("CMD_CHANNEL_DISCONNECT");
                if (mAsyncChannel != null) mAsyncChannel.disconnect();
                break;
            }
            case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                if (DBG) log("NetworkAgent channel lost");
                // let the client know CS is done with us.
                unwanted();
                synchronized (mPreConnectedQueue) {
                    mAsyncChannel = null;
                }
                break;
            }
            case CMD_SUSPECT_BAD: {
                log("Unhandled Message " + msg);
                break;
            }
            case CMD_REPORT_NETWORK_STATUS: {
                if (VDBG) {
                    log("CMD_REPORT_NETWORK_STATUS("
                            (msg.arg1 == VALID_NETWORK ? "VALID)" : "INVALID)"));
                }
                networkStatus(msg.arg1);
                break;
            }
        }
    }

    private void queueOrSendMessage(int what, Object obj) {
        synchronized (mPreConnectedQueue) {
            if (mAsyncChannel != null) {
                mAsyncChannel.sendMessage(what, obj);
            } else {
                Message msg = Message.obtain();
                msg.what = what;
                msg.obj = obj;
                mPreConnectedQueue.add(msg);
            }
        }
    }

    /**
     * Called by the bearer code when it has new LinkProperties data.
     */
    public void sendLinkProperties(LinkProperties linkProperties) {
        queueOrSendMessage(EVENT_NETWORK_PROPERTIES_CHANGED, new LinkProperties(linkProperties));
    }

    /**
     * Called by the bearer code when it has new NetworkInfo data.
     */
    public void sendNetworkInfo(NetworkInfo networkInfo) {
        queueOrSendMessage(EVENT_NETWORK_INFO_CHANGED, new NetworkInfo(networkInfo));
    }

    /**
     * Called by the bearer code when it has new NetworkCapabilities data.
     */
    public void sendNetworkCapabilities(NetworkCapabilities networkCapabilities) {
        queueOrSendMessage(EVENT_NETWORK_CAPABILITIES_CHANGED,
                new NetworkCapabilities(networkCapabilities));
    }

    /**
     * Called by the bearer code when it has a new score for this network.
     */
    public void sendNetworkScore(int score) {
        if (score < 0) {
            throw new IllegalArgumentException("Score must be >= 0");
        }
        queueOrSendMessage(EVENT_NETWORK_SCORE_CHANGED, new Integer(score));
    }

    /**
     * Called by the VPN code when it wants to add ranges of UIDs to be routed
     * through the VPN network.
     */
    public void addUidRanges(UidRange[] ranges) {
        queueOrSendMessage(EVENT_UID_RANGES_ADDED, ranges);
    }

    /**
     * Called by the VPN code when it wants to remove ranges of UIDs from being routed
     * through the VPN network.
     */
    public void removeUidRanges(UidRange[] ranges) {
        queueOrSendMessage(EVENT_UID_RANGES_REMOVED, ranges);
    }

    /**
     * Called by the bearer to indicate this network was manually selected by the user.
     * This should be called before the NetworkInfo is marked CONNECTED so that this
     * Network can be given special treatment at that time.
     */
    public void explicitlySelected() {
        queueOrSendMessage(EVENT_SET_EXPLICITLY_SELECTED, 0);
    }

    /**
     * Called when ConnectivityService has indicated they no longer want this network.
     * The parent factory should (previously) have received indication of the change
     * as well, either canceling NetworkRequests or altering their score such that this
     * network won't be immediately requested again.
     */
    abstract protected void unwanted();

    /**
     * Called when the system determines the usefulness of this network.
     *
     * Networks claiming internet connectivity will have their internet
     * connectivity verified.
     *
     * Currently there are two possible values:
     * {@code VALID_NETWORK} if the system is happy with the connection,
     * {@code INVALID_NETWORK} if the system is not happy.
     * TODO - add indications of captive portal-ness and related success/failure,
     * ie, CAPTIVE_SUCCESS_NETWORK, CAPTIVE_NETWORK for successful login and detection
     *
     * This may be called multiple times as the network status changes and may
     * generate false negatives if we lose ip connectivity before the link is torn down.
     */
    protected void networkStatus(int status) {
    }

    protected void log(String s) {
        Log.d(LOG_TAG, "NetworkAgent: " + s);
    }
}
