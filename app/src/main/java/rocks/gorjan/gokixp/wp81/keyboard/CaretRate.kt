package rocks.gorjan.gokixp.wp81.keyboard

import kotlin.math.abs

/**
 * How fast a pushed joystick walks the caret, worked out with no Android in it.
 *
 * Pulled out of [JoystickView] for the same reason [alternatesBox] is pulled out of the popup
 * that uses it. This is the *feel* of the control - what a small push does, what a big one
 * does, what happens the instant it engages - and feel is exactly the kind of thing that comes
 * out slightly wrong and stays wrong, because a caret that runs a bit too fast still looks
 * like a caret running. As plain arithmetic it can be asked questions instead of being tested
 * by holding a thumb on a phone and squinting.
 *
 * It carries state, and the state is the point. A rate is characters per second and a frame is
 * about sixteen milliseconds, so most frames owe the caret a third of a character; rounding
 * each frame's share on its own would floor every one of them to zero and the caret would
 * never move at all. The remainder has to be carried, and where it is *dropped* - on engaging,
 * on returning to the middle, on the caret hitting the end of the text - is what stops a stick
 * paying out movement nobody asked for.
 */
internal class CaretRate {

    /** Fractions of a character owed to the caret, carried between frames. */
    private var carry = 0f

    /** Which way the stick was pushed last, to spot the moment it engages. */
    private var direction = 0

    /**
     * How many characters the caret should move for [seconds] at a push of [dp].
     *
     * @param dp how far the finger is from where it went down, signed: negative is leftward.
     * @return characters to move, negative for left, and zero for most frames of a slow push.
     */
    fun steps(dp: Float, seconds: Float): Int {
        val push = abs(dp)
        val way = if (dp < 0f) -1 else 1

        if (push < DEAD_DP) {
            // Back at rest. The remainder is dropped rather than kept, so a stick pushed,
            // returned, and pushed again does not pay out what it was owed from last time.
            direction = 0
            carry = 0f
            return 0
        }

        // The first character of a push, given at once and not waited for.
        //
        // Without this a nudge is worth nothing: at the bottom of the range the caret takes
        // half a second to move one character, so pushing and letting go - which is how
        // anybody asks for exactly one - would do nothing at all about half the time. It is
        // the same shape as a held key, which types once and only then begins to repeat, and
        // it is what makes one character and many characters the same gesture at two lengths.
        if (direction != way) {
            direction = way
            carry = 0f
            return way
        }

        carry += rate(push) * seconds * way
        val whole = carry.toInt()
        if (whole != 0) carry -= whole
        return whole
    }

    /**
     * The caret did not go where it was sent: it is against the end of the text.
     *
     * The remainder goes with it. A stick held against a wall would otherwise bank a second of
     * movement and spend it the instant the caret had somewhere to go again.
     */
    fun stalled() {
        carry = 0f
    }

    /** The finger has come off. */
    fun release() {
        carry = 0f
        direction = 0
    }

    /**
     * How fast the caret runs, in characters a second, for a push of [dp].
     *
     * Squared rather than straight, which is the whole feel of the control. A linear ramp
     * spends most of its travel in the middle of the range, so the slow end - where you are
     * placing the caret between two particular letters - is a sliver, and everything past
     * halfway is indistinguishable haste. Squaring gives most of the travel to the slow end
     * and puts the speed at the extreme, where the finger is deliberately far out.
     */
    fun rate(dp: Float): Float {
        val t = ((abs(dp) - DEAD_DP) / (FULL_DP - DEAD_DP)).coerceIn(0f, 1f)
        return MIN_RATE + (MAX_RATE - MIN_RATE) * t * t
    }

    /** Whether a push this far is still slow enough for each character to be worth feeling. */
    fun fine(dp: Float): Boolean = rate(dp) <= FINE_RATE

    companion object {

        /**
         * How far the stick has to be pushed before it is pushed at all.
         *
         * A thumb resting on a control is not asking for anything, and a caret that crept
         * whenever a hand was still would be unusable. Small, because everything past it is
         * the useful travel and there is not much of that on a phone.
         */
        const val DEAD_DP = 8f

        /**
         * And how far out is as far as it goes.
         *
         * About a thumb's comfortable reach from where it landed. Past this the rate stops
         * climbing rather than running away, so shoving the finger to the edge of the screen
         * is the same as pushing it far - which is what somebody in a hurry will do, and it
         * should not become a different, wilder control when they do.
         */
        const val FULL_DP = 72f

        /**
         * The slowest and the fastest the caret runs, in characters a second.
         *
         * Two at the bottom, which is a character every half second - deliberately slow enough
         * to stop on the one you meant. Thirty at the top, which crosses a line of text in a
         * little over a second and is about as fast as a caret can move while still being
         * watched rather than merely waited for.
         */
        const val MIN_RATE = 2f
        const val MAX_RATE = 30f

        /**
         * The rate below which each character is still worth feeling.
         *
         * Eight a second. Slower than that and the ticks are countable, which is exactly what
         * you want when placing a caret between two letters; faster and they run together into
         * a buzz that says nothing the screen is not already saying. The same reasoning as the
         * single tick that opens a held backspace - see [KeyView].
         */
        const val FINE_RATE = 8f
    }
}
