
package com.android.systemui.nx.eyecandy;

import android.graphics.Rect;

public interface NxSurface {
    /**
     * @return Rect get NX canvas dimensions as a rect object
     */
    public Rect onGetSurfaceDimens();

    /**
     * invalidate to call onDraw for a fresh canvas
     */
    public void invalidate();

    /**
     * force setDiabledFlags for bar element view state
     */
    public void updateBar();
}
