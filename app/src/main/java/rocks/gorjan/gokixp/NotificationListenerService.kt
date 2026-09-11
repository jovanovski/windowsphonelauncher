package rocks.gorjan.gokixp

import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationListenerService : NotificationListenerService() {

    /**
     * One notification, as a tile shows it.
     *
     * [image] is whatever the app put on its own notification - the photograph in a
     * post, the avatar of whoever is messaging - already scaled down to tile size. A
     * notification with none simply has none; most do not. [opening] is where it goes
     * when the tile showing it is tapped.
     */
    data class NotificationLine(
        val title: String,
        val text: String,
        val image: android.graphics.Bitmap? = null,
        val opening: Opening? = null
    )

    /**
     * Where a notification goes when it is opened, and how to be rid of it after.
     *
     * [intent] is the app's own content intent - the one the shade sends when the
     * notification is tapped there, which is what lands on the conversation a message
     * came from rather than on the app's front page. Some notifications carry none;
     * those have nowhere to go but the app itself.
     *
     * [key] names the notification to the listener, so a tile that has just opened one
     * can retire it exactly as the shade does - [autoCancel] is whether the app asked
     * for that. [ours] marks the notifications this launcher posted itself, which the
     * shell's own tiles are the only ones to follow. See MainActivity.
     */
    data class Opening(
        val key: String,
        val intent: android.app.PendingIntent,
        val autoCancel: Boolean,
        val ours: Boolean
    )

    /**
     * One notification as the Action Center lists it.
     *
     * A wider net than [NotificationLine] casts, and deliberately: a tile is a mark on the
     * Start screen that has to mean "something new is waiting", so the quiet ones and the
     * ones that never go away are filtered out of it - see [shouldShowNotification]. The
     * Action Center is a shade. What the phone's own shade would show, this shows, silent
     * and ongoing alike, because a list of notifications that leaves some of them out is
     * not a list of notifications.
     *
     * [postedAt] is the app's own idea of when this happened, which is what the right-hand
     * column of the list is written from, and [clearable] is whether the row can be swiped
     * away at all - a running download or a playing track cannot be, exactly as in the
     * shade.
     */
    data class ShadeEntry(
        val packageName: String,
        val key: String,
        val title: String,
        val text: String,
        /**
         * The whole of what the notification says, which is what a row shows once it has
         * been opened out. The same as [text] wherever the app had nothing longer to add.
         */
        val fullText: String,
        /**
         * How many separate things this one notification is carrying: messages in a
         * conversation, lines in a list. One for the ordinary kind.
         *
         * A row cannot say this for itself. An app with several things to show posts one
         * notification and puts the rest inside it, so two messages from the same contact
         * and one message from them are the same single row - and the only way to tell them
         * apart was to open it. See [fullText].
         */
        val messageCount: Int,
        /**
         * The picture the notification is *about* - the photograph in a post, the artwork
         * on a track - or null, which is most of them.
         *
         * Only `EXTRA_PICTURE`. The large icon is not this: it is whoever sent the message,
         * and blowing an avatar up to the width of the panel would be a portrait of nobody
         * where the content should be.
         */
        val image: android.graphics.Bitmap?,
        val postedAt: Long,
        val clearable: Boolean,
        /**
         * Whether this is something in progress rather than something that has happened -
         * a running download, a playing track, a foreground service saying it is there.
         *
         * The panel lists these; the status strip does not mark them. A mark up there means
         * "something is waiting for you", and a media player that has been running all
         * afternoon is not waiting for anybody.
         */
        val ongoing: Boolean,
        /**
         * Whether this is a player's own notification - the transport controls a media app
         * posts while it holds a session.
         *
         * The panel does not list these: what is playing is drawn as a player of its own
         * at the top, with the cover and the controls, and a row saying the same thing
         * underneath it would be the same track twice. See WP81ActionCenter.setMedia.
         */
        val media: Boolean,
        val opening: Opening?
    )

    companion object {
        private const val TAG = "NotificationListener"
        private var instance: NotificationListenerService? = null
        private val activeNotificationPackages = mutableSetOf<String>()

        /**
         * Notification small icons seen per package, for the Windows Phone 8.1 tiles.
         *
         * Android requires a small icon to be a flat monochrome silhouette, which makes
         * it a good source of tile art for apps that ship no Android 13 themed icon.
         * Cached rather than read live because a tile has to paint whether or not the
         * app currently has a notification up. Icons are small and bounded by the number
         * of installed apps, so this is not worth evicting.
         *
         * Concurrent because it is not only read where it is written: the app list resolves
         * its rows' artwork on a worker thread, and a notification can be posted onto the
         * main thread while it is doing so. See AppListView.
         */
        private val smallIcons =
            java.util.concurrent.ConcurrentHashMap<String, android.graphics.drawable.Icon>()

        /**
         * Live notification text per package, for the Windows Phone 8.1 tiles.
         *
         * Rebuilt from the currently posted notifications rather than accumulated, so a
         * tile never shows something the user has already dismissed. Ordered newest first.
         */
        private val notificationText = mutableMapOf<String, List<NotificationLine>>()

        /** Longest edge a notification picture is kept at. A tile is not a gallery. */
        private const val IMAGE_MAX_PX = 256

        /** The same, for the Action Center, where an opened row is the width of the panel. */
        private const val SHADE_IMAGE_MAX_PX = 512

        /**
         * The same lines with the ones that say the same thing collapsed into one.
         *
         * By what a line *shows* rather than by the whole of it: some apps re-post
         * identical content under several ids, and two of those are one thing to read even
         * though they are two notifications with two different intents behind them.
         */
        private fun List<NotificationLine>.distinctContent(): List<NotificationLine> =
            distinctBy { Triple(it.title, it.text, it.image) }

        /**
         * Retires a notification the user has just opened from a tile.
         *
         * The shade does this itself for anything posted with FLAG_AUTO_CANCEL; a tile
         * that sends the same intent has to do it by hand, or the mark stays on the tile
         * for something the user has already read.
         */
        fun dismiss(key: String) {
            try {
                instance?.cancelNotification(key)
            } catch (e: Exception) {
                Log.w(TAG, "Could not dismiss notification $key", e)
            }
        }

        /**
         * The missed calls the user has not dealt with, newest first.
         *
         * Read off the shade rather than off the call log, which is the difference between
         * "you have missed calls" and "you missed some calls at some point": the log keeps
         * every one of them forever, and the notification is the one that goes away when
         * the user has seen it. Which is what the Phone tile is asking about.
         *
         * Found by category rather than by package, because who posts these depends on
         * which app is the phone - the default dialler if it handles them, and Telecom
         * itself if it does not. The category is the one thing that is true either way.
         */
        private var missedCallLines: List<NotificationLine> = emptyList()

        fun missedCalls(): List<NotificationLine> = missedCallLines

        /**
         * Text messages waiting, for the Messaging tile and on the same terms.
         *
         * This app's own, and only its own. Once this launcher holds the messaging role it
         * is the one thing on the phone that announces an arriving text, so its
         * notifications are the whole of the answer - and matching by anything broader
         * would put every chat app on the phone onto a tile that is about this one. See
         * MessageNotifier.
         */
        private var messageLines: List<NotificationLine> = emptyList()

        fun messages(): List<NotificationLine> = messageLines

        /**
         * Everything currently posted, newest first, for the Action Center.
         *
         * Volatile because it is written on the listener's own thread and read on the
         * main one - an immutable list swapped in whole, so a reader either sees the old
         * snapshot or the new one and never a half-built list.
         */
        @Volatile
        private var shadeEntries: List<ShadeEntry> = emptyList()


        /** The shade as it stands, newest first. See [ShadeEntry]. */
        fun shade(): List<ShadeEntry> = shadeEntries

        /**
         * Retires everything the user is allowed to retire - the shade's "clear all".
         *
         * The platform decides what that covers: a notification the posting app marked
         * ongoing or no-clear stays exactly where it is, which is why the Action Center's
         * command is worded as the shade's is rather than promising an empty list.
         */
        fun clearAll() {
            try {
                instance?.cancelAllNotifications()
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear notifications", e)
            }
        }

        /** Notification lines for [packageName], newest first, or empty. */
        fun getNotificationLines(packageName: String): List<NotificationLine> =
            notificationText[packageName].orEmpty()

        /**
         * What one program has waiting, by the package its tile is filed under.
         *
         * Everything installed answers with its own notifications, which is what the
         * listener has them by. The shell's two telephone programs cannot: nothing on this
         * phone posts under "system.phone", and what they are about was left by somebody
         * else - so they are gathered by what they are rather than by who sent them.
         *
         * Both of these used to stand on the People tile, because People was the address
         * book, the dialler and the messaging app all at once, and one tile with two
         * unrelated numbers on it had to pick which of them to show. Two tiles each show
         * their own.
         */
        fun linesFor(packageName: String): List<NotificationLine> = when (packageName) {
            "system.phone" -> missedCallLines
            "system.messaging" -> messageLines
            else -> getNotificationLines(packageName)
        }

        /** Every package that currently has text worth showing on a tile. */
        fun packagesWithText(): Set<String> = notificationText.keys.toSet()

        fun getInstance(): NotificationListenerService? = instance

        fun getActiveNotificationPackages(): Set<String> = activeNotificationPackages.toSet()

        /**
         * The most recent notification small icon for [packageName], loaded as a
         * drawable, or null if this app has not posted a notification since boot.
         * See MonochromeIconProvider for how the WP8.1 tiles use it.
         */
        fun getSmallIcon(
            context: android.content.Context,
            packageName: String
        ): android.graphics.drawable.Drawable? = try {
            smallIcons[packageName]?.loadDrawable(context)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load small icon for $packageName", e)
            null
        }

        fun hasNotification(packageName: String): Boolean = activeNotificationPackages.contains(packageName)
    }
    
    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refreshActiveNotifications()
    }
    
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        activeNotificationPackages.clear()
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        val packageName = sbn.packageName

        // Keep the small icon for the WP8.1 Start tiles. Harvested from every posted
        // notification, including ongoing and silent ones that are filtered out below -
        // we want the artwork regardless of whether the notification itself counts.
        try {
            sbn.notification.smallIcon?.let { smallIcons[packageName] = it }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read small icon for $packageName", e)
        }

        // A missed call is read by what it is rather than by who sent it, and who sent it
        // is a system package - which the filter below drops, taking the refresh with it.
        // So it is answered here, before the filter has a chance to say this notification
        // is of no interest. See isMissedCall.
        if (isMissedCall(sbn)) notifyMainActivity()

        if (!shouldShowNotification(sbn)) {
            // It changes no tile - that filter is what a tile means by a notification -
            // but it is on the Action Center's list, and until this was here it was on no
            // list at all: the whole shade is rebuilt inside notifyMainActivity, and
            // returning before it meant an ongoing or a silent notification was never
            // read at all unless some *other* app happened to post while it was up. Which
            // is why the music player, the download and the navigation were missing.
            //
            // The text alone, without waking the wall behind it: a playing track re-posts
            // its notification every second or so, and handing every tile on Start a fresh
            // glyph at that rate is a repaint nobody asked for. The panel reads this list
            // on its own two-second tick. See WP81ActionCenter.setNotifications.
            refreshNotificationText()
            return
        }

        Log.d(TAG, "Active notification posted for: $packageName")

        activeNotificationPackages.add(packageName)

        notifyMainActivity()
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)

        val packageName = sbn.packageName
        Log.d(TAG, "Notification removed for: $packageName")

        // Check if there are still active notifications for this package
        // Only count non-ongoing notifications
        val stillHasNotifications = try {
            val notifications = getActiveNotifications().filter {
                it.packageName == packageName
            }

            // Log what we found for debugging
            Log.d(TAG, "Found ${notifications.size} notifications for $packageName")
            notifications.forEach { notif ->
                val isOngoing = notif.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0
                Log.d(TAG, "  - Notification ongoing=$isOngoing")
            }

            // Only count notifications that pass our filter (non-ongoing, non-system)
            notifications.any { shouldShowNotification(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking notifications for $packageName", e)
            false
        }

        if (!stillHasNotifications) {
            Log.d(TAG, "Removing $packageName from active notifications")
            activeNotificationPackages.remove(packageName)
            notifyMainActivity()
        } else {
            Log.d(TAG, "Keeping $packageName in active notifications")
            // The tile stays marked, so the wall has nothing to redraw - but one of this
            // app's notifications has just gone, and the Action Center lists them one by
            // one rather than one per app. Without this the row it withdrew stayed on the
            // panel until something else moved.
            refreshNotificationText()
        }
    }
    
    private fun refreshActiveNotifications() {
        try {
            activeNotificationPackages.clear()

            val notifications = getActiveNotifications()

            for (notification in notifications) {
                val isOngoing = notification.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0
                val shouldShow = shouldShowNotification(notification)

                if (shouldShow) {
                    activeNotificationPackages.add(notification.packageName)
                }
            }

            Log.d(TAG, "Refreshed active notifications: ${activeNotificationPackages.size} packages: $activeNotificationPackages")
            notifyMainActivity()

        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing active notifications", e)
        }
    }
    
    /**
     * Whether a notification is a call that went unanswered.
     *
     * [android.app.Notification.CATEGORY_MISSED_CALL] is the answer the platform documents
     * and not the one it gives: Telecom's own `MissedCallNotifierImpl` - which is what
     * posts these once the default phone app does not handle them itself, and so is what
     * posts them here - sets no category at all. So the channel is asked as well, and every
     * app that has one of these names it the same way: `TelecomMissedCalls`,
     * `phone_missed_call`, `missed_calls`. Both words are required, which is what keeps
     * "Missed alarms" out.
     */
    /** One of this app's own message notifications, arriving or failing to send. */
    private fun isOurMessage(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != packageName) return false
        val channel = sbn.notification.channelId?.lowercase() ?: return false
        return channel.contains("message")
    }

    private fun isMissedCall(sbn: StatusBarNotification): Boolean {
        if (sbn.notification.category == android.app.Notification.CATEGORY_MISSED_CALL) {
            return true
        }
        val channel = sbn.notification.channelId?.lowercase() ?: return false
        return channel.contains("missed") && channel.contains("call")
    }

    private fun shouldShowNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification

        // Skip ongoing notifications (like music players, timers, etc.)
        if (notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) {
            return false
        }

        // Skip system notifications
        if (sbn.packageName == "android" || sbn.packageName == "com.android.systemui") {
            return false
        }

        // Skip silent notifications (low/min importance channels, no sound or vibration)
        if (isSilentNotification(sbn)) {
            return false
        }

        return true
    }

    /**
     * A notification is considered silent when it is posted at an importance below
     * IMPORTANCE_DEFAULT, i.e. it never makes a sound, vibrates or peeks.
     */
    private fun isSilentNotification(sbn: StatusBarNotification): Boolean {
        try {
            val ranking = Ranking()
            if (currentRanking?.getRanking(sbn.key, ranking) == true) {
                val importance = ranking.importance
                if (importance != NotificationManager.IMPORTANCE_UNSPECIFIED) {
                    return importance < NotificationManager.IMPORTANCE_DEFAULT
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading ranking for ${sbn.packageName}", e)
        }

        // Fall back to the legacy priority for notifications posted without a channel
        @Suppress("DEPRECATION")
        return sbn.notification.priority < android.app.Notification.PRIORITY_DEFAULT
    }
    
    /**
     * Rebuilds the per-package notification text from what is currently posted.
     *
     * Reads the live set every time instead of tracking adds and removals: notifications
     * are updated in place as often as they are posted fresh, and reconciling that
     * incrementally is far easier to get wrong than simply re-reading it.
     */
    private fun refreshNotificationText() {
        try {
            val grouped = mutableMapOf<String, MutableList<NotificationLine>>()
            val missed = mutableListOf<NotificationLine>()
            val texts = mutableListOf<NotificationLine>()
            // Paired with what they were read from, because whether a summary is worth
            // keeping cannot be decided one notification at a time. See below.
            val shade = mutableListOf<Pair<StatusBarNotification, ShadeEntry>>()
            for (sbn in getActiveNotifications() ?: emptyArray()) {
                // Gathered here for the same reason the missed calls are: the People tile
                // has no package to read them from, and these are wanted whole rather than
                // as part of whatever this launcher's own package happens to be showing.
                if (isOurMessage(sbn)) {
                    val bundle = sbn.notification.extras
                    val who = bundle?.getCharSequence(android.app.Notification.EXTRA_TITLE)
                        ?.toString()?.trim().orEmpty()
                    val what = bundle?.getCharSequence(android.app.Notification.EXTRA_TEXT)
                        ?.toString()?.trim().orEmpty()
                    if (who.isNotEmpty() || what.isNotEmpty()) {
                        texts.add(NotificationLine(
                            who, if (what == who) "" else what, opening = openingOf(sbn)))
                    }
                }
                // Gathered before the filter below rather than after it: a missed call is
                // worth surfacing whoever posted it, and Telecom's own is a system
                // notification of exactly the kind that filter is there to drop.
                if (isMissedCall(sbn)) {
                    val bundle = sbn.notification.extras
                    val who = bundle?.getCharSequence(android.app.Notification.EXTRA_TITLE)
                        ?.toString()?.trim().orEmpty()
                    val what = bundle?.getCharSequence(android.app.Notification.EXTRA_TEXT)
                        ?.toString()?.trim().orEmpty()
                    if (who.isNotEmpty() || what.isNotEmpty()) {
                        // Telecom's own says "Missed call" in both lines. Repeating it
                        // under itself on a tile is a tile saying one thing twice.
                        missed.add(NotificationLine(
                            who, if (what == who) "" else what, opening = openingOf(sbn)))
                    }
                }
                // The Action Center's own list, taken before the filter below rather than
                // after it: what it leaves out is the quiet and the ongoing, and a shade
                // that hid those would be missing the download that is running and the
                // track that is playing. See [ShadeEntry].
                shadeEntryOf(sbn)?.let { shade.add(sbn to it) }

                if (!shouldShowNotification(sbn)) continue

                // Skip the group summary. Mail and messaging apps post one summary
                // alongside each real notification, so counting both reports two
                // notifications where the user only has one.
                val flags = sbn.notification.flags
                if (flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) continue

                val extras = sbn.notification.extras ?: continue
                val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
                    ?.toString()?.trim().orEmpty()
                val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
                    ?.toString()?.trim().orEmpty()
                if (title.isEmpty() && text.isEmpty()) continue
                grouped.getOrPut(sbn.packageName) { mutableListOf() }
                    .add(NotificationLine(
                        title, text, notificationImage(sbn.notification), openingOf(sbn)))
            }
            // A group summary is dropped only where the group it summarises is on the
            // list too. Mail and messaging apps post one alongside each real notification,
            // and listing both shows the user their inbox twice - but some apps post a
            // summary and nothing else, and dropping that one on sight lost the whole
            // notification. Reddit is one of them.
            val summarised = shade.mapNotNull { (sbn, _) ->
                if (isGroupSummary(sbn)) null else sbn.groupKey
            }.toSet()

            // Newest first, and one row per thing being said: the same content posted
            // under several ids is one notification to read, exactly as it is on a tile.
            shadeEntries = shade
                .filterNot { (sbn, _) -> isGroupSummary(sbn) && sbn.groupKey in summarised }
                .map { (_, entry) -> entry }
                .sortedByDescending { it.postedAt }
                .distinctBy { Triple(it.packageName, it.title, it.text) }
            missedCallLines = missed.asReversed().distinctContent()
            messageLines = texts.asReversed().distinctContent()
            notificationText.clear()
            for ((pkg, lines) in grouped) {
                // Some apps re-post the same content under several ids; identical lines
                // would otherwise show up as separate notifications to cycle through.
                notificationText[pkg] = lines.asReversed().distinctContent()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading notification text", e)
        }
    }

    /**
     * [sbn] as a row of the Action Center, or null if there is nothing to write there.
     *
     * Group summaries are kept here and weeded out by the caller, which is the only place
     * that can tell a summary standing in front of its group from one standing alone.
     *
     * The text is looked for in three places because apps differ about where they put it.
     * `EXTRA_TEXT` is the ordinary answer; a notification whose body is longer than one
     * line often carries `EXTRA_BIG_TEXT` and leaves the short one empty; and a few - the
     * media ones especially - say what they have to say in `EXTRA_SUB_TEXT`. Taking only
     * the first of those left a column of titles with nothing underneath them.
     */
    private fun shadeEntryOf(sbn: StatusBarNotification): ShadeEntry? {
        val notification = sbn.notification
        val extras = notification.extras ?: return null
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
            ?.toString()?.trim().orEmpty()
        val text = sequenceOf(
            android.app.Notification.EXTRA_TEXT,
            android.app.Notification.EXTRA_BIG_TEXT,
            android.app.Notification.EXTRA_SUB_TEXT
        ).mapNotNull { extras.getCharSequence(it)?.toString()?.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        if (title.isEmpty() && text.isEmpty()) return null
        val body = bodyOf(sbn.notification, extras, text)
        return ShadeEntry(
            packageName = sbn.packageName,
            key = sbn.key,
            title = title,
            text = text,
            fullText = body.text,
            messageCount = body.count,
            image = shadePicture(sbn.notification),
            postedAt = sbn.postTime,
            clearable = sbn.isClearable,
            ongoing = notification.flags and
                android.app.Notification.FLAG_ONGOING_EVENT != 0,
            media = isMediaNotification(extras),
            opening = openingOf(sbn)
        )
    }

    /**
     * Whether a notification is a player's transport controls.
     *
     * Two marks, because apps reach the same look by two routes: `MediaStyle` names itself
     * in `EXTRA_TEMPLATE`, and anything that hands the shade a session token - which is
     * what makes the platform draw media controls at all - carries `EXTRA_MEDIA_SESSION`.
     * Either is enough, and neither costs anything: the extras have already been unparcelled
     * for the title by the time this is asked.
     */
    private fun isMediaNotification(extras: android.os.Bundle): Boolean = try {
        extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) ||
            extras.getString(android.app.Notification.EXTRA_TEMPLATE)
                ?.contains("MediaStyle") == true
    } catch (e: Exception) {
        // A bundle that will not unparcel is a notification we simply know less about.
        false
    }

    /**
     * The whole of what a notification says, and how many separate things that is.
     *
     * Four places to look, because an app with more than one line to show does not post
     * more than one notification - it posts one and puts the rest inside it, and which
     * field it uses depends on which style it chose:
     *
     *  - **MessagingStyle** is a conversation, and every message in it is in there. This is
     *    the one that matters most: WhatsApp, Signal and the rest post *one* notification
     *    per thread and update it in place, so two messages from the same contact are one
     *    notification with two messages in it. Reading only the summary line showed the
     *    newest and silently dropped the rest, which looked like the older one having never
     *    arrived.
     *  - **InboxStyle** is a list - several mails, several missed calls - in EXTRA_TEXT_LINES.
     *  - **BigTextStyle** is one long passage the summary line was cut out of.
     *  - and failing all three, the summary line is the whole of it.
     *
     * The sender's name goes in front of a message only where the thread has more than one
     * of them: a conversation with one other person in it is already named by the row's own
     * title, and repeating it down the left of every line is a column of the same word.
     */
    private data class Body(val text: String, val count: Int)

    private fun bodyOf(
        notification: android.app.Notification,
        extras: android.os.Bundle,
        summary: String
    ): Body {
        try {
            androidx.core.app.NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(notification)
                ?.messages
                ?.takeIf { it.isNotEmpty() }
                ?.let { messages ->
                    val senders = messages.mapNotNull { it.person?.name?.toString() }.toSet()
                    return Body(
                        messages.joinToString("\n") { message ->
                            val who = message.person?.name?.toString()
                            val what = message.text?.toString().orEmpty()
                            if (who != null && senders.size > 1) "$who: $what" else what
                        }.trim(),
                        messages.size
                    )
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read a conversation out of a notification", e)
        }

        extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return Body(it.joinToString("\n"), it.size) }

        extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)
            ?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return Body(it, 1) }

        return Body(summary, 1)
    }

    /**
     * The picture a notification is carrying, for a row that has been opened out.
     *
     * Kept larger than the tile's copy because it is shown larger - a tile is two hundred
     * pixels across and an opened row is the width of the panel - but nowhere near the size
     * the app posted it at, which on a photograph is whatever came off the camera.
     *
     * Only where there is one, which is not often: most notifications carry no picture at
     * all, and this is read on every refresh, so what it does in the common case is return
     * null without touching anything.
     */
    private fun shadePicture(notification: android.app.Notification): android.graphics.Bitmap? =
        try {
            val extras = notification.extras
            // Through BundleCompat: the typed getParcelable is API 33, and calling it on
            // anything older throws NoSuchMethodError rather than falling back.
            extras?.let {
                androidx.core.os.BundleCompat.getParcelable(
                    it,
                    android.app.Notification.EXTRA_PICTURE,
                    android.graphics.Bitmap::class.java
                )
            }?.let { scaleLongestTo(it, SHADE_IMAGE_MAX_PX) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read a notification's picture", e)
            null
        }

    private fun isGroupSummary(sbn: StatusBarNotification): Boolean =
        sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0

    /**
     * Where [sbn] points, for a tile that is showing it.
     *
     * The content intent is the notification's own answer to "what is this about": a
     * messaging app puts the conversation there, a mail app the message, and tapping the
     * notification in the shade is how the user would normally get to it. A tile showing
     * that notification is showing the same thing and should go to the same place.
     */
    private fun openingOf(sbn: StatusBarNotification): Opening? {
        val intent = sbn.notification.contentIntent ?: return null
        // Somewhere to go, and not merely something to fire: a few apps hang a broadcast
        // off their notification and deal with the tap silently, which from a tile would
        // be the Start screen turning itself out for a launch that never comes.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            !intent.isActivity) {
            return null
        }
        return Opening(
            key = sbn.key,
            intent = intent,
            autoCancel =
                sbn.notification.flags and android.app.Notification.FLAG_AUTO_CANCEL != 0,
            ours = sbn.packageName == packageName
        )
    }

    /**
     * The picture a notification is carrying, if any.
     *
     * A big-picture style comes first - that is the post, the photo, the thing the
     * notification is *about* - and the large icon second, which on a messaging app is
     * whoever sent it. Both are scaled down here rather than at the tile: they arrive at
     * whatever size the app felt like, several of them are held at once, and a tile is
     * two hundred pixels across.
     */
    private fun notificationImage(notification: android.app.Notification): android.graphics.Bitmap? {
        try {
            val extras = notification.extras ?: return null
            // Through BundleCompat: the typed getParcelable is API 33, and calling it on
            // anything older throws NoSuchMethodError rather than falling back.
            val picture = androidx.core.os.BundleCompat.getParcelable(
                extras, android.app.Notification.EXTRA_PICTURE, android.graphics.Bitmap::class.java)
            if (picture != null) return scaleForTile(picture)

            val large = notification.getLargeIcon() ?: return null
            val drawable = large.loadDrawable(this) ?: return null
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: IMAGE_MAX_PX
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: IMAGE_MAX_PX
            val scale = (IMAGE_MAX_PX.toFloat() / maxOf(width, height)).coerceAtMost(1f)
            val bitmap = android.graphics.Bitmap.createBitmap(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Could not read a notification's picture", e)
            return null
        }
    }

    private fun scaleForTile(source: android.graphics.Bitmap): android.graphics.Bitmap =
        scaleLongestTo(source, IMAGE_MAX_PX)

    /** [source] with its longer side brought down to [longest], or as it is if smaller. */
    private fun scaleLongestTo(
        source: android.graphics.Bitmap,
        longest: Int
    ): android.graphics.Bitmap {
        val was = maxOf(source.width, source.height)
        if (was <= longest) return source
        val scale = longest.toFloat() / was
        return android.graphics.Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun notifyMainActivity() {
        refreshNotificationText()
        // Notify MainActivity to update notification dots
        MainActivity.getInstance()?.updateNotificationDots()
    }
}