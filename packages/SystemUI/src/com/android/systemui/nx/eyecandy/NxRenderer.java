
package com.android.systemui.nx.eyecandy;

import android.graphics.Bitmap;
import android.graphics.Canvas;

public interface NxRenderer {
    /**
     * @param surface give renderer a NX surface to call back on
     */
    public void onSetNxSurface(NxSurface surface);

    /**
     * @param canvas NX canvas to render
     * @return return the rendered Bitmap for chaining
     */
    public Canvas onDrawNx(Canvas canvas);

}
