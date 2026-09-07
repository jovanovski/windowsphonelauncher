# Windows Phone Launcher for Android
[!["Buy Me A Coffee"](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://www.buymeacoffee.com/jovanovski)


![Windows Launcher Preview](https://i.imgur.com/cFgpsMM.jpeg)

This is a Windows Phone 8.1 inspired launcher for Android 10+, which tries to faithfully recreate the Metro shell - the wall of live tiles, the app list, the three keys along the bottom - on modern phones. Feel free to grab a pre-compiled APK from the Releases tab or download and build the project yourself.

It is the sibling of [Windows Launcher](https://github.com/jovanovski/windowslauncher), which does the desktop era - 95, 98, 2000/ME, XP and Vista. Windows Phone started life as a theme inside that app and is its own app now, with its own application id, so the two can be installed side by side. If the desktop launcher is still on the phone, this one imports your Start screen from it on first run - tiles and their order, sizes, colours, the accent, the background and any icons you picked by hand. Both builds have to be signed with the same key for that to work.

<!-- TODO: drop a preview GIF and a couple of screenshots in here -->

## Features

### Start screen
- A vertically scrolling wall of live tiles. Hold one to enter edit mode and drag it in the same gesture, exactly as the phone did; every tile then shows an unpin button and a resize chevron.
- Three footprints and then some: small, medium and wide, plus one-row strips two to four cells across and two-column blocks three or four rows deep.
- Live tiles the shell provides: clock, calendar, weather, air quality, news, your camera roll turning over one picture at a time, and a mosaic of your contacts' faces.
- App tiles carry notification counts and the latest line the app posted, if you give the launcher notification access.
- A colour per tile or one accent for all of them, folders made by holding one tile over another, and a hidden set for the tiles you want off the wall but not off the phone.
- Third-party apps get a flat white glyph where they ship a themed (monochrome) icon or a notification silhouette, and their own artwork where they don't - which is what a real WP8.1 Start screen looked like.

### Getting around
- Back, Start and search along the bottom, as the capacitive keys were. Holding Start opens the shell's own commands: settings, hidden tiles, refresh app list, about.
- Swipe left from Start for the alphabetical app list, with the letter jump grid behind the section headers. Search from the list, and a query that matches nothing installed is handed on to the web.
- Swipe down at the top of Start pulls the notification shade down; push up at the bottom to land in app search.

### Look
- Twenty accent colours and the dark/light background, both live settings.
- A photo behind the tiles: 32 Lumia wallpapers are bundled, or pick your own, with optional blur and a slow drift that follows how the phone is being held.

### Apps, all written from scratch on the shell's own furniture
- **People** - the hub and the Phone app as one panorama: favourites, call history, everybody, and text conversations. Make it the phone's default dialler and messaging app and it gets the in-call screen (over the lock screen, with hold, audio routing and "can't talk now" replies), missed calls and incoming texts too. Everything is read from and written back to the phone's own address book and call log, so nothing lives only inside the launcher.
- **Music** - Zune, laid out as the six-section panorama the phone had. Plays whatever MediaStore knows about, keeps playlists, and shows up as a browsable source in Android Auto.
- **Internet Explorer** - the phone's browser: the page gets the whole screen and everything else is on one dark strip at the bottom. Tabs restored between sessions, favourites, downloads, history. Can be made the phone's default browser so links open here.
- **Weather** - current conditions, the day, the week and other places, on one panorama. Fed by [Open-Meteo](https://open-meteo.com/); the Start tile, the app and the forecast panel all read the same fetch.
- **News** - the tile's stories opened out, a section per outlet. BBC, The Guardian, NPR, Al Jazeera, Deutsche Welle, Sky News, The Verge and Ars Technica, each one switchable in settings.
- **Alarms** - alarms, stopwatch and countdown on one panorama. Alarms survive a reboot and ring over the lock screen.
- **Files** - the plain file list WP8.1 finally got in 2014, with a select mode and a clipboard that survives closing the app.
- **Notepad** - notes and an archive as two sections of one panorama, with pictures from the camera or the gallery. The same notes the desktop launcher keeps, in the same place.
- **Calculator**, **Minesweeper** and **Solitaire** - the phone versions: no window, no chrome, the field or the keypad *is* the page.
- **Welcome** - what changed, fetched from the GitHub releases, and the nearest thing here to an about box.

### Keyboard
A full Windows Phone keyboard, and an input method for the whole phone rather than just for this app - turn it on in system settings and it comes up in every text box on the device.

- 22 layouts, Latin, Greek and Cyrillic. English ships with a word list, so it corrects and predicts out of the box; the other languages type perfectly well and simply make no suggestions yet.
- Suggestions and autocorrect from a trie speller, bigrams and the words it learns from you, with symbols on hold, an emoji panel and a clipboard.
- GIF search, which needs [your own GIPHY key](https://developers.giphy.com/dashboard/) pasted into the keyboard's settings - one key baked into a public repo would be one key answering for every install.
- Dictation that never leaves the phone, via Vosk: about 40 MB of model fetched on demand (Swedish is 303 MB and says so first), for the twelve languages Vosk publishes a small model for. Everything else falls back to the phone's own recogniser.

### Housekeeping
- Checks GitHub releases for a newer APK and offers to download it.
- New and removed apps are picked up as they are installed.

## Special Permissions
Most of what the launcher does needs nothing special. A few things do, and Android restricts them by default - especially for side-loaded apps:

- **Notification access** - the counts and lines on the tiles.
- **All files access** - the Files app.
- **Default phone / messaging app** - the People app's call screen and conversations. Offered as a prompt when you first open them; declining leaves People a perfectly good address book.
- **Default browser** - links opening in Internet Explorer instead of leaving the launcher.

Notification access is the one Android hides behind Restricted Settings on a side-loaded build. Two ways around it:

### Option 1 - Install via ADB
Apps installed with adb install aren't treated as the "untrusted sideload" case, so Restricted Settings doesn't trigger.
Just: `adb install -r app-release.apk`
Then go to Settings → Notification access and you should be able to toggle the service without the "Restricted setting" dialog.

### Option 2 - Allow restricted settings
1. Settings → Apps → See all apps → Windows Phone
2. Tap the ⋮ three-dot menu (top right)
3. Tap "Allow restricted settings", unlock with PIN if asked
4. Now go to Notification access and enable the service.

## Building
Open the project in Android Studio and run it, or `./gradlew assembleDebug` from the command line. minSdk 29 (Android 10), targetSdk 36, Java 11. Nothing else is needed - there are no keys or services to configure, apart from the GIPHY one above if you want GIFs on the keyboard.

## Notes
1) Claude Code was used to create most of this as a fun side-project of mine. Do **NOT** expect super clean code or great organization, it was never the goal. Feel free to refactor things that need refactoring, and submit a PR if something is bothering you.
2) I claim **NO** copyright over any of the assets used in this project. Most of them belong to Microsoft, and there is no goal to do anything illegal with them. This is just a nostalgia project for all us old Windows fans. The code is GPL-3.0; see [LICENSE](LICENSE), and [NOTICE](NOTICE) for what the fonts, wallpapers, icons and word lists are and who they belong to.
3) This used to be MIT. It went GPL-3.0 when Cortana learned to name a song: the recogniser is a port of [SongRec](https://github.com/marin-m/SongRec), which is GPL, and Shazam's signature format is only written down inside it. That seemed a fair trade for work somebody else did first and published. [NOTICE](NOTICE) explains it in full.
