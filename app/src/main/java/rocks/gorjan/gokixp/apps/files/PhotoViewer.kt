package rocks.gorjan.gokixp.apps.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import rocks.gorjan.gokixp.wp81.MetroAppBar
import rocks.gorjan.gokixp.wp81.MetroPageTransition
import rocks.gorjan.gokixp.wp81.WP81Palette
import java.io.File
import java.util.concurrent.Executors

/**
 * One photograph, filling the window, with the three things you do to a photograph along
 * the bottom.
 *
 * The app hands every other kind of file out to the phone, and that is still right: a
 * spreadsheet wants the program that reads spreadsheets, and Files was never going to
 * carry one. A picture is the exception, and only because of what the tap *means*. Tapping
 * a photograph is not asking for a program - it is asking to see it, which is over in the
 * moment it is on screen and finished with a press of the back key. Sending that out to a
 * chooser, waiting for another app to start and then having to find the way back is three
 * screens of ceremony around a look.
 *
 * So the look happens here, and the ceremony is offered rather than imposed: "open in" is
 * on the strip, one tap away, for when a look turns out not to have been the question.
 *
 * Deliberately bare. No name, no date, no counter, no chrome of any kind above the
 * picture, because everything that could be written there is written on the row in browse
 * and none of it is what somebody who tapped a photograph came for. Black behind it under
 * either theme for the same reason every viewer on every platform is: a photograph on
 * white has its own light edges eaten by the page, and there is nothing to be done about
 * that except not to do it.
 */
class PhotoViewer(
    private val context: Context,
    private val palette: WP81Palette,
    /** Handing the picture to the shell's share sheet. */
    private val onShare: (File) -> Unit,
    /** Asking to delete it. The prompt is the app's, and stands over this. */
    private val onDelete: (File) -> Unit,
    /** Handing it out to whatever else on the phone opens a picture. */
    private val onOpenWith: (File) -> Unit
) {

    /** The theme this one page is in, whatever the phone is set to. See [fillBar]. */
    private val darkPalette = WP81Palette.of(palette.accent, isDark = true)

    private val handler = Handler(Looper.getMainLooper())

    private val layer = FrameLayout(context)
    private val picture = ImageView(context)
    private val barSlot = FrameLayout(context)
    private val turn = MetroPageTransition(layer)

    private var bar: MetroAppBar? = null

    /** Which picture is up, and null when nothing is. */
    private var showing: File? = null

    /**
     * Which opening the picture on screen belongs to.
     *
     * A decode is a read off the disk and can finish after the viewer has been closed, or
     * after a second photograph has been opened over the first. Counting the openings is
     * what lets a late one be recognised and dropped instead of appearing over whatever is
     * there now.
     */
    private var opening = 0

    private var released = false

    /** The layer, for the app to lay over its pages. Built once and kept. */
    fun view(): View {
        layer.setBackgroundColor(Color.BLACK)
        // A tap on the viewer is a tap on the viewer. Without this the pages underneath
        // would still be taking the taps that land on the picture.
        layer.isClickable = true
        layer.visibility = View.GONE

        picture.scaleType = ImageView.ScaleType.FIT_CENTER
        // Above the strip rather than under it. The strip is opaque, and a picture running
        // beneath it would be a picture with its bottom inch missing - which on a
        // photograph of people is usually the part that was being looked at.
        layer.addView(picture, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            bottomMargin = dp(MetroAppBar.HEIGHT_DP)
        })
        layer.addView(barSlot, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        return layer
    }

    fun isShowing(): Boolean = showing != null

    /** Which picture is up, for an app that needs to know whether it still exists. */
    fun showing(): File? = showing

    /**
     * Puts [file] up.
     *
     * [standIn] is the thumbnail the page that was tapped had already read, and it goes
     * up in the same frame as the tap. The full picture takes a moment to come off the
     * disk, and a moment of black between the tap and the photograph reads as the app
     * having missed the tap - whereas the small one, blown up and soft for two hundred
     * milliseconds, reads as the picture arriving. It is the thing the user just tapped,
     * so it is also the right thing to be looking at while the real one is read.
     */
    fun show(file: File, standIn: Bitmap?) {
        if (released) return
        showing = file
        opening++
        picture.setImageBitmap(standIn)
        fillBar(file)
        layer.alpha = 0f
        layer.visibility = View.VISIBLE
        // Posted because the turnstile swings about the page's own left edge and needs to
        // know how tall the page is to find it, which it does not until this one has been
        // laid out for the first time.
        layer.post { if (!released && showing != null) turn.playIn() }
        read(file, opening)
    }

    fun hide() {
        if (showing == null) return
        showing = null
        // Anything still being read is now for a picture nobody is looking at.
        opening++
        turn.playOut {
            picture.setImageDrawable(null)
            barSlot.removeAllViews()
            bar = null
        }
    }

    /** The back key, on its way out of here. */
    fun handleBack(): Boolean {
        if (bar?.closeMenu() == true) return true
        if (!isShowing()) return false
        hide()
        return true
    }

    fun release() {
        released = true
        opening++
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * The three things there are to do with a photograph you are looking at.
     *
     * Rebuilt per picture rather than once, because delete is a question about where this
     * one is kept: a photograph in a folder the app cannot write to is one the command
     * stands dead over rather than one it offers and then fails at.
     */
    private fun fillBar(file: File) {
        barSlot.removeAllViews()
        // The dark strip under a light theme too, because the thing it is fastened to is
        // black under a light theme too - see [view]. An app bar takes its colour from the
        // page it belongs to rather than from the setting, and this page has only one.
        val strip = MetroAppBar(context, darkPalette)
        strip.addCommand(SHARE_ICON) { onShare(file) }
        val remove = strip.addCommand(DELETE_ICON) { onDelete(file) }
        strip.setCommandEnabled(remove, file.parentFile?.canWrite() == true)
        strip.addCommand(OPEN_IN_ICON) { onOpenWith(file) }
        bar = strip
        barSlot.addView(strip, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun read(file: File, at: Int) {
        executor.execute {
            val full = decode(file)
            handler.post {
                // Closed while this was being read, or a second picture opened over it.
                if (released || at != opening || full == null) return@post
                picture.setImageBitmap(full)
            }
        }
    }

    /**
     * The picture, no larger than the screen it is going onto.
     *
     * Through [ImageDecoder] rather than BitmapFactory for the two things it does that
     * BitmapFactory does not: it turns a photograph the right way up from the EXIF a
     * camera writes - read raw, a portrait picture arrives on its side - and it scales
     * during the decode rather than after it, so a forty-megapixel photograph never exists
     * at full size in memory on the way to a screen that cannot show a tenth of it.
     */
    private fun decode(file: File): Bitmap? = try {
        val box = maxOf(
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels
        ).coerceAtLeast(MIN_BOX)
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.isMutableRequired = false
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > box) {
                val scale = box.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        }
    } catch (e: Exception) {
        // Plenty of things named .jpg are not one. The thumbnail stays up, which is the
        // most honest thing on hand: it is a picture of this file, read by something else.
        Log.w(TAG, "Could not read ${file.name}", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "No room to read ${file.name}")
        null
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        private const val TAG = "MetroPhotoViewer"

        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT

        /** A floor under the decode, for the case where the window has no size to report. */
        private const val MIN_BOX = 1080

        /** One at a time: only one picture is ever being looked at. */
        private val executor = Executors.newSingleThreadExecutor()

        private const val ICON_DIR = "custom_icons_8"
        private const val SHARE_ICON = "$ICON_DIR/appbar.share.svg"
        private const val DELETE_ICON = "$ICON_DIR/appbar.delete.svg"

        /** A window with a way out of it, which is what "open in" is asking for. */
        private const val OPEN_IN_ICON = "$ICON_DIR/appbar.new.window.svg"
    }
}
