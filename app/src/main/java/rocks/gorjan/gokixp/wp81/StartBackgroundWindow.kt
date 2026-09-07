package rocks.gorjan.gokixp.wp81

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Something on the Start screen that is a window onto the wallpaper behind it.
 *
 * The photograph is never drawn as the wall's own background: it is handed to each piece
 * of the wall, and each draws only the slice that lies behind its own position. So the
 * picture is only ever visible *through* the wall, and a tile stepping aside or a folder
 * opening is a hole appearing in it rather than a shape moving over a picture.
 *
 * A tile is the usual one. A folder's rules are the other - see
 * [StartScreenView.buildFolderBand] - and they exist because the alternative is a solid
 * bar sitting across a wall of windows.
 */
interface StartBackgroundWindow {

    /**
     * Points this piece at the shared crop.
     *
     * [src] is the region of [bitmap] to show, [dest] the area that region is stretched
     * across - the wall, zoomed for its own parallax - and [setBackgroundOffset] says
     * where in that area this piece sits. All three come from the wall, so every window
     * on it lines up into one continuous image.
     */
    fun setStartBackground(bitmap: Bitmap?, src: Rect?, dest: Rect)

    /** Where this piece sits inside [dest], in the wall's own coordinates. */
    fun setBackgroundOffset(x: Float, y: Float)
}
