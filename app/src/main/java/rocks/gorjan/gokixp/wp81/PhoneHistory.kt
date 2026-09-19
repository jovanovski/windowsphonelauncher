package rocks.gorjan.gokixp.wp81

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.util.Log
import java.util.concurrent.Executors

/**
 * The call log, as Windows Phone's history page read it.
 *
 * The provider keeps one row per call. The phone showed one row per *conversation*: three
 * calls to the same person in an afternoon were one line saying so, with the count beside
 * the name. That is the whole of what this does beyond querying - collapse a run of calls
 * to the same number into a single entry, keep the most recent one's time and direction,
 * and count how many were folded in.
 *
 * Runs of them, not totals. Two calls this morning and one last Tuesday are two entries,
 * because the history is a diary and the Tuesday call happened on Tuesday. Only calls that
 * were *next to each other* in the log are folded, which is exactly what the phone did.
 */
object PhoneHistory {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    enum class Direction { INCOMING, OUTGOING, MISSED }

    /** A run of calls to or from one number, newest first in the list. */
    data class Entry(
        val number: String,
        /** The name the log had for them, or empty for a number that is nobody yet. */
        val name: String,
        val direction: Direction,
        /** When the most recent of the run happened. */
        val at: Long,
        /** How many calls were folded together. One for most rows. */
        val count: Int,
        /** How long the most recent of them lasted, in seconds. Zero if it never connected. */
        val seconds: Long,
        /**
         * Whoever owns the number: somebody in the book, or somebody a directory knows.
         * Null for a number that is nobody yet, which is the one a row offers to save.
         */
        val contact: PeopleStore.Contact?
    ) {
        /** What to put on the row: their name if the phone knows it, the number if not. */
        val title: String get() = name.ifBlank { number }
    }

    /**
     * Both halves of the call log, asked for together.
     *
     * Writing is not a second favour being asked of the user - they are one permission
     * group and one dialog - and without it the history is a page that can show the calls
     * and do nothing about them: "delete all" silently fails, and a missed call can never
     * be marked as seen, which is what leaves Telecom counting them up across reboots.
     * See [markMissedRead].
     */
    fun permissions(): Array<String> = arrayOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG
    )

    fun hasAccess(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Whether the log can be written to as well as read.
     *
     * Nearly always true wherever [hasAccess] is: they are one group, so granting the read
     * grants this with it. Worth asking before a command that needs it all the same, so
     * "delete all" on the phone where it is missing says so rather than doing nothing.
     */
    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The most recent calls, folded and newest first.
     *
     * [limit] is on the rows read rather than on the entries returned: it is a cap on the
     * work, and a phone that called one person twenty times over lunch should not have its
     * history cut short by that.
     *
     * [onReady] is called once with what the phone itself can answer, and then a second
     * time if a directory turns out to know somebody the phone does not. That is the whole
     * reason this is in two parts. A directory is a question put to the network, a log can
     * hold a dozen numbers nobody has ever saved, and this used to hold the finished list
     * back until every one of them had been asked about - so opening the history for the
     * first time after a restart sat on an empty page for as long as the slowest answer
     * took. The names a directory has already given up cost nothing and come in the first
     * pass with the rest; only the ones that have never been asked wait for the second.
     */
    fun recent(context: Context, limit: Int = LIMIT, onReady: (Result) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val calls = try {
                nameLocally(app, read(app, limit))
            } catch (e: Exception) {
                // Said out loud rather than folded into an empty list. A log that cannot be
                // read and a phone that has made no calls are different facts, and showing
                // the second when the first is true is how a broken query hides: the page
                // says "no calls yet" and looks like it is working.
                Log.w(TAG, "Could not read the call log", e)
                main.post { onReady(Result(emptyList(), failed = true)) }
                return@execute
            }
            // Folded here and not in the post: folding walks every call and puts each
            // number into its international form, and the main thread is where the list
            // is being drawn, not where it should be worked out.
            val near = Result(fold(app, calls), failed = false)
            main.post { onReady(near) }

            // The page is up; now the slow half. Nothing is posted unless a directory
            // actually named somebody, so the common phone - where every caller is either
            // in the book or in nobody's - redraws once and stays still.
            val deep = try {
                nameFromDirectories(app, calls)
            } catch (e: Exception) {
                Log.w(TAG, "No directory could name the calls", e)
                null
            } ?: return@execute
            val far = Result(fold(app, deep), failed = false)
            main.post { onReady(far) }
        }
    }

    /** What a read came back with, and whether it came back at all. */
    data class Result(val entries: List<Entry>, val failed: Boolean)

    private data class Call(
        val number: String,
        val name: String,
        val direction: Direction,
        val at: Long,
        val seconds: Long,
        val contact: PeopleStore.Contact?
    )

    private fun read(context: Context, limit: Int): List<Call> {
        val calls = mutableListOf<Call>()
        context.contentResolver.query(
            // The cap goes on the URI, not on the sort order.
            //
            // `ORDER BY date DESC LIMIT 400` is valid SQL and was how everybody wrote this
            // for years, but the providers now build their queries strictly and throw the
            // whole statement out for having a keyword in the sort clause - which came back
            // here as a history that was simply empty. The call log has a query parameter
            // for exactly this, and it is the only form that is guaranteed to survive.
            CallLog.Calls.CONTENT_URI.buildUpon()
                .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
                .build(),
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(0)?.trim().orEmpty()
                // A withheld number has nothing to call back and nothing to file under.
                if (number.isEmpty() || number == "-1" || number == "-2") continue
                calls.add(
                    Call(
                        number = number,
                        name = cursor.getString(1)?.trim().orEmpty(),
                        direction = when (cursor.getInt(2)) {
                            CallLog.Calls.OUTGOING_TYPE -> Direction.OUTGOING
                            // A rejected or blocked call is one that came in and was not
                            // answered, which is what missed means to the person reading.
                            CallLog.Calls.INCOMING_TYPE -> Direction.INCOMING
                            else -> Direction.MISSED
                        },
                        at = cursor.getLong(3),
                        seconds = cursor.getLong(4),
                        contact = null
                    )
                )
            }
        }
        return calls
    }

    /**
     * Attaches the contact behind each number, out of what this phone can answer alone.
     *
     * The log's own `CACHED_NAME` is a copy taken when the call happened, so it is right
     * about the name and says nothing about who to open when the row is tapped - and it is
     * stale for anyone renamed since. Looked up once per distinct number rather than once
     * per call, because a history of forty calls is rarely forty people.
     *
     * Through PeopleStore rather than a PhoneLookup of its own, which is what this used to
     * be. One resolver for the whole app: the calls page, the conversations and the
     * missed-call notification all have the same question to ask, and three answers to it
     * meant a person could be named in one place and a bare number in another. See
     * PeopleStore.lookupNow, which reads every account there is.
     *
     * Nothing here goes to the network. A directory that has already answered about a
     * number is read back from what it said - free, and the reason a second opening of the
     * page is named in full straight away - but one that has never been asked is left for
     * [nameFromDirectories].
     */
    private fun nameLocally(context: Context, calls: List<Call>): List<Call> {
        if (calls.isEmpty()) return calls
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return calls

        val byNumber = HashMap<String, PeopleStore.Contact>()
        for (number in calls.map { it.number }.distinct()) {
            val found = PeopleStore.lookupNow(context, number)
                ?: PeopleStore.rememberedName(context, number)
            if (found != null) byNumber[number] = found
        }
        return attach(calls, byNumber)
    }

    /**
     * The same again for whoever is still nameless, asking the directories this time.
     *
     * Null when there is nothing new to say - nobody left nameless, no contact access, or
     * no directory that knew any of them - so the page above can leave the list it has
     * alone rather than rebuilding it to look exactly the same.
     */
    private fun nameFromDirectories(context: Context, calls: List<Call>): List<Call>? {
        if (calls.isEmpty()) return null
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return null

        val nameless = calls.filter { it.contact == null }.map { it.number }.distinct()
        if (nameless.isEmpty()) return null
        // The budget and the remembering both live in nameNumbers; this hands it only the
        // numbers the book has already failed on, which is what that budget is for.
        val byNumber = PeopleStore.nameNumbers(context, nameless)
        if (byNumber.isEmpty()) return null
        return attach(calls, byNumber)
    }

    /**
     * Puts the people found onto the calls they belong to.
     *
     * Somebody out of a directory is kept as well as somebody in the book. This used to
     * throw them away for having no row on the phone, which made a colleague the phone
     * could name into a number it offered to save - they have a card, it is simply the
     * directory's. See PeopleStore.directoryDetail.
     */
    private fun attach(
        calls: List<Call>,
        byNumber: Map<String, PeopleStore.Contact>
    ): List<Call> = calls.map { call ->
        val found = byNumber[call.number]
        if (found == null) call
        else call.copy(contact = found, name = found.name.ifBlank { call.name })
    }

    /** Collapses each run of calls to the same number into one entry. See the class note. */
    private fun fold(context: Context, calls: List<Call>): List<Entry> {
        val entries = mutableListOf<Entry>()
        for (call in calls) {
            val last = entries.lastOrNull()
            // The same person and the same kind of call. A missed call followed by them
            // ringing back is two things that happened, not one thing twice.
            if (last != null &&
                PeopleStore.normalise(context, last.number) ==
                PeopleStore.normalise(context, call.number) &&
                last.direction == call.direction
            ) {
                entries[entries.size - 1] = last.copy(count = last.count + 1)
                continue
            }
            entries.add(
                Entry(
                    number = call.number,
                    name = call.name,
                    direction = call.direction,
                    at = call.at,
                    count = 1,
                    seconds = call.seconds,
                    contact = call.contact
                )
            )
        }
        return entries
    }

    /**
     * Every call to or from one number, newest first and not folded.
     *
     * The history page folds a run of calls into one line, which is the right summary and
     * the wrong thing entirely when the question is "when did we speak, and for how long".
     * So this is the same log read again without the folding, narrowed to one number.
     *
     * Matched on the normalised number rather than by asking the provider for it: the log
     * holds whatever form each call arrived in - with a country code, without, spaced,
     * punctuated - and a `WHERE number = ?` against one of those forms finds a fraction of
     * the calls with the same person. See [PeopleStore.normalise].
     */
    fun forNumber(context: Context, number: String, onReady: (List<Entry>) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val entries = try {
                val wanted = PeopleStore.normalise(app, number)
                // Filtered before anybody is named, not after. Naming is a query per
                // distinct number and all but one of these is about to be thrown away.
                val mine = read(app, DEEP_LIMIT)
                    .filter { PeopleStore.normalise(app, it.number) == wanted }
                nameLocally(app, mine)
                    .map {
                        Entry(
                            number = it.number,
                            name = it.name,
                            direction = it.direction,
                            at = it.at,
                            count = 1,
                            seconds = it.seconds,
                            contact = it.contact
                        )
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read one number's calls", e)
                emptyList()
            }
            main.post { onReady(entries) }
        }
    }

    /**
     * The missed calls, marked as seen in the log itself.
     *
     * This is the half of "dealt with" that survives a restart. Telecom does not count
     * missed calls in memory - it asks the call log, on every boot, for the calls still
     * marked new, and sends the phone app that many. So an app that only takes its own
     * notification down has changed nothing: the rows are still new, and the next restart
     * says "4 missed calls", then five, then six, for calls the user read weeks ago.
     *
     * Telecom is asked to do this too - see MissedCallReceiver.clear - and would, if the
     * launcher held the phone role at that moment and the call went through. This is the
     * same thing said directly, so it holds whether or not either of those is true.
     *
     * Needs WRITE_CALL_LOG, which is why [permissions] asks for it. Silent without it.
     */
    fun markMissedRead(context: Context) {
        val app = context.applicationContext
        if (app.checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        executor.execute {
            try {
                val seen = ContentValues().apply {
                    put(CallLog.Calls.NEW, 0)
                    put(CallLog.Calls.IS_READ, 1)
                }
                // Only the ones still new, so a log full of old calls is not rewritten
                // every time the app is opened.
                app.contentResolver.update(
                    CallLog.Calls.CONTENT_URI,
                    seen,
                    "${CallLog.Calls.NEW} = 1 AND ${CallLog.Calls.TYPE} = ?",
                    arrayOf(CallLog.Calls.MISSED_TYPE.toString())
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not mark the missed calls as seen", e)
            }
        }
    }

    /**
     * Says when the call log changes, which is how a call arrives here.
     *
     * The same arrangement the messages use, and for the same reason: a page holding one
     * of these re-reads and redraws, so a call missed while the history is open appears on
     * it. Without one the list was whatever the log said at the moment the window opened,
     * and a phone left on the history page overnight showed yesterday's calls.
     */
    fun watch(context: Context, onWrite: () -> Unit): ContentObserver? {
        if (!hasAccess(context)) return null
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = onWrite()
        }
        return try {
            context.applicationContext.contentResolver
                .registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            observer
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch the call log", e)
            null
        }
    }

    fun unwatch(context: Context, observer: ContentObserver?) {
        if (observer == null) return
        try {
            context.applicationContext.contentResolver.unregisterContentObserver(observer)
        } catch (e: Exception) {
            Log.d(TAG, "The call log watch was already gone", e)
        }
    }

    /** Empties the log. Behind a confirmation wherever it is offered. */
    fun clear(context: Context, onDone: () -> Unit) {
        val app = context.applicationContext
        executor.execute {
            try {
                app.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear the call log", e)
            }
            main.post { onDone() }
        }
    }

    /**
     * How many rows are read. Deep enough that scrolling the history reaches last month,
     * shallow enough that opening the app is one short query.
     */
    private const val LIMIT = 400

    /**
     * How deep one person's own history reads.
     *
     * Further than the main list, because it is being sifted for one number and most of
     * what it reads will be thrown away - a page that showed three calls with somebody
     * because the fourth was four hundred calls ago would be worse than useless.
     */
    private const val DEEP_LIMIT = 2000

    private const val TAG = "WP81Phone"
}
