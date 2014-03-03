/*
 * Copyright (C) 2014 SlimRoms Project
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
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.android.cards.internal.Card;
import com.android.cards.internal.CardHeader;
import com.android.cards.internal.CardThumbnail;

import com.android.systemui.R;

public class RecentCard extends Card {

    private RecentExpandedCard mExpandedCard;
    private CardHeader mHeader;
    private CardThumbnail mRecentIcon;

    public RecentCard(Context context, TaskDescription td) {
        this(context, R.layout.inner_base_main, td);
    }

    public RecentCard(Context context, int innerLayout, TaskDescription td) {
        super(context, innerLayout);

        constructBaseCard(context, td);
    }

    // Construct our card.
    private void constructBaseCard(Context context, final TaskDescription td) {

        // Construct card header view.
        mHeader = new CardHeader(mContext);
        // Set visible the expand/collapse button.
        mHeader.setButtonExpandVisible(true);

        // Construct app icon view.
        mRecentIcon = new CardThumbnail(context);

        // Construct expanded area view.
        mExpandedCard = new RecentExpandedCard(context);

        // Prepare and update the contents.
        updateCardContent(td);

        // Finally add header, icon and expanded area to our card.
        addCardHeader(mHeader);
        addCardThumbnail(mRecentIcon);
        addCardExpand(mExpandedCard);
    }

    // Update content of our card. This is either called during construct
    // or if RecentPanelView want to update the content.
    public void updateCardContent(final TaskDescription td) {
        if (mHeader != null) {
            // Set or update the header title.
            mHeader.setTitle((String) td.getLabel());
        }
        if (mRecentIcon != null) {
            // Set or update app icon via inbuild async task and LRU cache
            mRecentIcon.setCustomSource(new CardThumbnail.CustomSource() {
                @Override
                public String getTag() {
                    return td.packageName;
                }

                @Override
                public Bitmap getBitmap() {
                    PackageManager pm = mContext.getPackageManager();
                    Bitmap bitmap = null;
                    try {
                        bitmap = drawableToBitmap(pm.getApplicationIcon(getTag()));
                    } catch (PackageManager.NameNotFoundException e) {
                    }
                    return bitmap;
                }

                private Bitmap drawableToBitmap(Drawable drawable) {
                    if (drawable instanceof BitmapDrawable) {
                        return ((BitmapDrawable) drawable).getBitmap();
                    }

                    Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                            drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                    drawable.draw(canvas);

                    return bitmap;
                }
            });
        }
        if (mExpandedCard != null) {
            // Set or update app screenshot
            mExpandedCard.updateExpandedContent(td.persistentTaskId, td.getLabel());

            // Read flags and set accordingly initial expanded state.
            boolean isSystemExpanded =
                    (td.getExpandedState() & RecentPanelView.EXPANDED_STATE_BY_SYSTEM) != 0;

            boolean isUserExpanded =
                    (td.getExpandedState() & RecentPanelView.EXPANDED_STATE_EXPANDED) != 0;

            boolean isUserCollapsed =
                    (td.getExpandedState() & RecentPanelView.EXPANDED_STATE_COLLAPSED) != 0;

            setExpanded((isSystemExpanded && !isUserCollapsed) || isUserExpanded);
        }
    }

    @Override
    public void setupInnerViewElements(ViewGroup parent, View view) {
        if (view == null) {
            return;
        }
    }
}

