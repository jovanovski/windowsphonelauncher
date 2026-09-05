package rocks.gorjan.gokixp

import android.graphics.drawable.Drawable

data class WallpaperItem(
    val name: String,
    /**
     * A preview of the picture, decoded small - or null where nothing is showing one.
     *
     * Null by default, and the phone's Settings page is the reason it stays that way: it
     * shows a strip of thumbnails, not the whole library at once. Decoding every wallpaper
     * at full resolution to fill a list is what this default exists to avoid.
     */
    val drawable: Drawable? = null,
    val isCurrent: Boolean = false,
    val filePath: String? = null,
    val isBuiltIn: Boolean = false
)