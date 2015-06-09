/*
 * Copyright (C) 2014 SlimRoms Project
 * Author: Lars Greiss - email: kufikugel@googlemail.com
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

package com.android.systemui.slimrecent;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;

import com.android.cards.internal.Card;

import com.android.systemui.R;

/**
 * This class handles our base card view.
 */
public class RecentCard extends Card {

    private RecentHeader mHeader;
    private RecentAppIcon mRecentIcon;
    private RecentExpandedCard mExpandedCard;

    private int mPersistentTaskId;

    private int defaultCardBg = mContext.getResources().getColor(
                R.color.recents_task_bar_default_background_color);
    private int cardColor = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.RECENT_CARD_BG_COLOR,
                defaultCardBg, UserHandle.USER_CURRENT);

    public RecentCard(Context context, TaskDescription td, float scaleFactor) {
        this(context, R.layout.inner_base_main, td, scaleFactor);
    }

    public RecentCard(Context context, int innerLayout, TaskDescription td, float scaleFactor) {
        super(context, innerLayout);

        constructBaseCard(context, td, scaleFactor);
    }

    // Construct our card.
    private void constructBaseCard(Context context,
            final TaskDescription td, float scaleFactor) {

        // Construct card header view.
        mHeader = new RecentHeader(mContext, td, scaleFactor);

        // Construct app icon view.
        mRecentIcon = new RecentAppIcon(
                context, td.resolveInfo, td.identifier, scaleFactor, td.getIsFavorite());
        mRecentIcon.setExternalUsage(true);

        // Construct expanded area view.
        mExpandedCard = new RecentExpandedCard(context, td, scaleFactor);
        initExpandedState(td);

        // set custom background
        if (cardColor != 0x00ffffff) {
            this.setBackgroundResource(new ColorDrawable(cardColor));
        } else {
            this.setBackgroundResource(new ColorDrawable(getDefaultCardColorBg(td)));
        }

        // Finally add header, icon and expanded area to our card.
        addCardHeader(mHeader);
        addCardThumbnail(mRecentIcon);
        addCardExpand(mExpandedCard);

        mPersistentTaskId = td.persistentTaskId;
    }

    /** Returns the activity's primary color. */
    public int getDefaultCardColorBg(TaskDescription td) {
        if (td != null && td.cardColor != 0) {
            return td.cardColor;
        }
        return defaultCardBg;
    }

    // Update content of our card.
    public void updateCardContent(final TaskDescription td, float scaleFactor) {
        if (mHeader != null) {
            // Set or update the header title.
            mHeader.updateHeader(td, scaleFactor);
        }
        if (mRecentIcon != null) {
            mRecentIcon.updateIcon(
                    td.resolveInfo, td.identifier, scaleFactor, td.getIsFavorite());
        }
        if (mExpandedCard != null) {
            // Set expanded state.
            initExpandedState(td);
            // Update app screenshot.
            mExpandedCard.updateExpandedContent(td, scaleFactor);
        }
        mPersistentTaskId = td.persistentTaskId;

        // set custom background
        if (cardColor != 0x00ffffff) {
            this.setBackgroundResource(new ColorDrawable(cardColor));
        } else {
            this.setBackgroundResource(new ColorDrawable(getDefaultCardColorBg(td)));
        }
    }

    // Set initial expanded state of our card.
    private void initExpandedState(TaskDescription td) {
        // Read flags and set accordingly initial expanded state.
        final boolean isTopTask =
                (td.getExpandedState() & RecentPanelView.EXPANDED_STATE_TOPTASK) != 0;

        final boolean isSystemExpanded =
                (td.getExpandedState() & RecentPanelView.EXPANDED_STATE_BY_SYSTEM) != 0;

        final boolean isUserExpanded =
                (td.getExpandedState() & RecentPanelView.EXPANDED_STATE_EXPANDED) != 0;

        final boolean isUserCollapsed =
                (td.getExpandedState() & RecentPanelView.EXPANDED_STATE_COLLAPSED) != 0;

        final boolean isExpanded =
                ((isSystemExpanded && !isUserCollapsed) || isUserExpanded) && !isTopTask;

        if (mHeader != null) {
            // Set visible the expand/collapse button.
            mHeader.setButtonExpandVisible(!isTopTask);
        }

        setExpanded(isExpanded);
    }

    @Override
    public void setupInnerViewElements(ViewGroup parent, View view) {
        // Nothing to do here.
        return;
    }

    public int getPersistentTaskId() {
        return mPersistentTaskId;
    }

}
