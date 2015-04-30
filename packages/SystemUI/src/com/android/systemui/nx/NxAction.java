
package com.android.systemui.nx;

import com.android.internal.util.actions.ActionHandler;

import android.os.Handler;
import android.text.TextUtils;

public class NxAction {
    public interface ActionReceiver {
        public void onActionDispatched(NxAction actionEvent, String task);
    }

    private String mAction = "";
    private ActionReceiver mActionReceiver;
    private Handler mHandler;

    private final String mUri;

    private Runnable mActionThread = new Runnable() {
        @Override
        public void run() {
            mActionReceiver.onActionDispatched(NxAction.this, mAction);
        }
    };

    public NxAction(String uri, ActionReceiver receiver, Handler h, String action) {
        this.mUri = uri;
        this.mActionReceiver = receiver;
        this.mHandler = h;
        this.mAction = action;
    }

    public String getUri() {
        return mUri;
    }

    public void setAction(String action) {
        this.mAction = action;
    }

    public String getAction() {
        return mAction;
    }

    public void fireAction() {
        mHandler.post(mActionThread);
    }

    public void cancelAction() {
        mHandler.removeCallbacks(mActionThread);
    }

    public boolean isEnabled() {
        return !isActionEmpty(mAction);
    }

    private boolean isActionEmpty(String action) {
        if (TextUtils.isEmpty(action)) {
            action = ActionHandler.SYSTEMUI_TASK_NO_ACTION;
        }
        return ActionHandler.SYSTEMUI_TASK_NO_ACTION.equals(action);
    }
}
