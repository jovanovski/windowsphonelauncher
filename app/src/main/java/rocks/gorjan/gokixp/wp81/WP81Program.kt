package rocks.gorjan.gokixp.wp81

import android.view.View

/**
 * A program the shell hosts in a window, and can hand a new theme to.
 *
 * Leaving a program minimises its window rather than closing it, so that the alarms go on
 * counting and the music goes on playing - see `MainActivity.minimiseWP81Windows`. That is
 * also what makes this necessary: a program the user walked away from is still standing
 * there built out of the palette that was current when it opened, and it comes back in
 * that palette however many times the theme has changed since. A light music app on a dark
 * phone is what that looks like.
 *
 * Rebuilt rather than repainted. A program takes a colour from the palette in fifty or a
 * hundred places, most of them inside pages that are built when they are opened and thrown
 * away when they are left, so there is no set of live views to walk - but each program
 * already has a method that builds all of it from the palette, and running that again is
 * the program's own answer to the question.
 *
 * What a rebuild costs is the place the user was in: a game in progress, a half-typed note,
 * the record that was open over the player. Programs that own something a rebuild would
 * orphan - a picture decoder, a page's WebView - put it down first rather than leaking it,
 * and the one that owns something that must *not* stop - the player - keeps it and binds
 * the new transport to it afterwards.
 */
interface WP81Program {

    /**
     * Rebuilds this program in [palette], and hands back the view to put in the window.
     *
     * The caller owns the window and swaps the content itself: a program does not know
     * which frame it is in, and two of them are opened into windows that are not the
     * usual one.
     */
    fun applyPalette(palette: WP81Palette): View
}
