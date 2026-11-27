# Ruby Build Plan

A beginner-friendly, step-by-step plan for building an Android app that senses what you're doing (driving, walking, at home), keeps that sensing private on the phone, and sends short context labels to Claude.

## Big picture

- **On the phone:** all sensing (Bluetooth, motion sensors, GPS), all decisions about what you're doing, and all privacy controls.
- **Sent to Claude:** only short labels like "driving" or "at the gym", along with your messages, through Anthropic's API.
- **No Google activity service:** detection is done yourself from raw sensors, so the data stays on the device.

Build in phases. Each phase should work on its own before you start the next one.

## Phase 0: Set up your tools

1. Install Android Studio (free, from developer.android.com/studio).
2. In Android Studio, create a new project using the Empty Activity (Jetpack Compose) template. Language: Kotlin. Minimum SDK: API 29 (Android 10) or higher.
3. Turn on Developer options on your phone (Settings > About phone > tap Build number 7 times), then turn on USB debugging.
4. Plug the phone in and press Run. A "Hello Android" screen on your phone means you're set up.
5. Learn the basics first (a few evenings): Google's free Android Basics with Compose course covers Kotlin, screens, buttons and state.
6. Set up Git and a free GitHub repo so you can always undo mistakes.

## Phase 1: Detect your car's Bluetooth

**Goal:** the app shows "Driving" when the phone connects to your car, and "Not driving" when it disconnects.

1. Add permissions to AndroidManifest.xml: `BLUETOOTH_CONNECT` (Android 12+) and `BLUETOOTH` for older versions.
2. Ask for permission at runtime the first time the app opens.
3. Register a `BroadcastReceiver` for `BluetoothDevice.ACTION_ACL_CONNECTED` and `ACTION_ACL_DISCONNECTED`.
4. When a device connects, read its name and address. Add a settings screen where you pick which device is your car and save it with DataStore.
5. If the connected device matches your car, set the state to Driving.
6. Show the current state on the main screen.

**Test:** get in the car, start it, and watch the label change.

## Phase 2: Keep it running in the background

Android kills background apps aggressively, so this is where many first apps get stuck.

1. Create a foreground service with a small ongoing notification ("Context sensing on"). That's the approved way to run continuously.
2. Declare the service type in the manifest (`connectedDevice`, plus `location` later).
3. Start the service on boot with a `BOOT_COMPLETED` receiver.
4. Ask users to exclude the app from battery optimization; some phones (Samsung, Xiaomi) need this.

## Phase 3: Motion sensing with raw sensors

**Goal:** tell still, walking and riding in a vehicle apart without Google's Activity Recognition.

1. Use `SensorManager` to read the accelerometer at a low rate (`SENSOR_DELAY_NORMAL`) to save battery.
2. Compute the magnitude of acceleration and look at short windows of about 5 seconds.
3. Simple rules to start:
   - **Still:** very little variation.
   - **Walking:** a regular bounce around 1.5 to 2.5 steps a second (the step detector sensor helps here).
   - **Possibly in a vehicle:** smooth motion with steady low vibration, no step rhythm.
4. Use the significant motion sensor to wake the app only when the phone starts moving, which saves a lot of battery.
5. Later you can train a small on-device model with TensorFlow Lite on recordings of your own trips, but rules are fine for version one.

## Phase 4: GPS to confirm

1. Add `ACCESS_FINE_LOCATION`, and `ACCESS_BACKGROUND_LOCATION` if needed. Expect Android to make users approve background location separately.
2. Only turn GPS on when motion suggests a vehicle, and turn it off again after a minute or two.
3. Use Android's built-in `LocationManager` rather than Google's fused location if you want to stay off Google services.
4. A speed held over about 15 to 20 mph confirms Driving.
5. Save a few places on the phone (home, work, gym) and label them when you're within about 100 meters. This is called geofencing.

## Phase 5: The decision engine

Combine everything into one current state, in order of confidence:

1. Car Bluetooth connected means **Driving**.
2. GPS speed over the threshold means **In a vehicle**.
3. Inside a saved place means that place's label.
4. Otherwise, the motion result: **Walking** or **Still**.

Only change the state after it holds for 30 to 60 seconds, so a stoplight doesn't flip you to Still.

## Phase 6: Memory that lasts forever, owned by the user

**Goal:** every conversation is saved on the phone, searchable, and backed up to the user's own Google Drive. The AI provider never keeps the archive.

1. **Save chats locally.** Store every message in a Room database (Android's SQLite library) encrypted with SQLCipher. Keep the encryption key in the Android Keystore.
2. **Add search.** Start with SQLite's built-in full-text search (FTS5). Later, add on-device embeddings so the app can find past chats by meaning, not just keywords.
3. **Send only what's relevant.** With each new message, the app finds the few most relevant past snippets and sends them along to Claude. This is called RAG (retrieval-augmented generation), and it's how "forever" memory fits in a limited context window.
4. **Summarize old chats.** Periodically condense older conversations into short summaries, so search stays fast and fewer tokens are sent.
5. **Encrypted backups to the user's own Google Drive.** Back up to the user's Google Drive, not a server, using the Drive API's private app data folder (`drive.appdata` scope) so only Ruby can see the files. Compress and encrypt the database on the phone before uploading. Derive the backup key from a passphrase the user chooses (or give them a recovery key to save), not only a key locked to this phone, so the backup can be restored on a new phone. Run it automatically with WorkManager, for example nightly while charging on Wi-Fi.
6. **Storage limit.** Give local memory a storage budget, by default about 5% of the phone's total storage, which the user can change in settings to another percentage or a fixed amount in GB or TB. Ruby never fills the drive and always leaves free space for the phone. She warns at about 80% of her budget. At the limit she never deletes memories: she compresses them or moves the oldest ones to the encrypted Google Drive backup, and pulls them back down when asked.
7. **Restore and control.** Add a restore screen for a new phone, plus view, export and delete buttons so users can see and manage everything stored.

**Status (Nov 23, 2026):** Phase 6 is done. Memory is encrypted on the phone with search (steps 1-2), each chat message takes along up to 5 related memories (step 3), and days of chat older than a week are summarized nightly by Claude, while charging on Wi-Fi, with the summaries used in recall (step 4). Backups go to the user's own Google Drive nightly, with a backup history (step 5); the storage limit and export are done (steps 6-7). The full restore test passed: back up, uninstall, reinstall, restore with the recovery key, and messages and reminders came back. Export doesn't include summaries yet; they can be made again from the messages.

**Rule: one memory, whichever AI engine.** Ruby's memory is one persistent, growing entity no matter which AI engine is used. Memories made in Claude mode or Free mode go into the same database, the same backups, the same Drive archive and the same index. Switching engines never splits, hides, resets or loses memories; both engines read and write the same memory. Free mode may pull fewer memories per answer because of its smaller input limit, but nothing is ever left out of storage. (Checked Nov 24, 2026: every save, chat, briefing and summary, goes through the one memory store whichever engine answers, recall happens before the engine is chosen, and backups and the archive don't depend on the engine. `MemoryAcrossEnginesTest` checks that a memory saved in one mode is found in the other.)

**Test:** chat for a few days, wipe the app, restore from backup, and ask about something from day one.

Be upfront in the privacy policy: each message and its memory snippets still go to the AI provider to get an answer. The archive itself stays with the user.

## Phase 7: Car remote start (2022 Corolla)

**Goal:** the agent can start the car, for example on cold workday mornings. It works by "pressing" a real spare fob, so Toyota's rolling codes keep working. Don't try to record and replay the fob signal: it won't start the car, and it can knock your real fob out of sync.

1. **Get a spare fob.** Buy one programmed to your car; a dealer or locksmith can pair it.
2. **Hardware.** An ESP32 board (about $5-10), plus a small relay or optocoupler to close the lock button's contacts, and a 3V supply in place of the fob's coin battery.
3. **Wire it up.** Open the spare fob and solder two thin wires across the lock button's contacts, leading to the optocoupler. Keep the original button working.
4. **Firmware.** Program the ESP32 (Arduino IDE) to send the start pattern: tap lock, tap lock, then hold lock for about 3 seconds.
5. **Placement.** Fob remote start only reaches about 80 feet, so mount the module inside your home or garage near where the car parks. Never leave it inside the car, since anyone could start it.
6. **Connect it to the app.** The ESP32 joins your home Wi-Fi and exposes a simple, password-protected command. The Android app calls it with a "Start car" button, then from the decision engine (for example, workday mornings below 40 F).
7. **Safety rules.** Require confirmation for automatic starts at first, never start the car in an enclosed garage, and log every start.

**Test:** press "Start car" in the app from inside the house and watch the hazard lights flash before the engine starts.

## Phase 8: Phone-only superpowers

**Goal:** make Ruby useful every day with features that need nothing but the phone. Each one reuses what Phases 1-6 already built, so add them in any order once those work.

1. **Proactive daily briefing.** Each morning, Ruby builds a short briefing: weather, today's calendar, open reminders. When Bluetooth detects the car, she reads it aloud with Android's text-to-speech. Schedule it with WorkManager; read the calendar through `CalendarContract` (`READ_CALENDAR` permission).
2. **Notification triage.** A `NotificationListenerService` (the user turns it on in Settings) lets Ruby read incoming notifications. She sends short summaries to Claude to rank them, shows only the important ones, and while Driving holds everything except calls from chosen people.
3. **Context modes.** When the state from the decision engine changes, Ruby changes the phone: silent at work, a driving mode in the car, quiet hours at night. Use `NotificationManager` Do Not Disturb (needs the user to grant DND access) and `AudioManager` for volume.
4. **Location reminders.** "Remind me to buy milk next time I'm near the store." Ruby saves the place and the note, adds a geofence from Phase 4, and posts a notification on arrival. Store reminders in the Room database from Phase 6.
5. **Screen awareness.** Ask "what's this?" or "save this" about whatever is on screen. The simplest route is Android's screenshot share: share a screenshot to Ruby, she sends it to Claude and files the answer in memory. An `AccessibilityService` can read screen text directly, but Google Play reviews those apps strictly, so start with sharing.
6. **Health and sleep insights.** Read steps, sleep and heart rate from Health Connect (each data type needs its own permission). Ruby looks for patterns, for example worse sleep after late nights, and mentions them in the weekly reflection. Keep health data on the phone like the chat archive.
7. **Acting inside apps.** Go beyond opening apps: send a text, start navigation to a saved place, play a playlist. Start with Android intents (an SMS intent, a `google.navigation:` intent, a media search intent). Watch for newer Android features that let apps expose actions to AI agents, and add them as they become available.
8. **Weekly reflection.** Every Sunday, Ruby summarizes the week from her memory: places visited, time driving, what you talked about, and anything still open. Run it with WorkManager and save the summary as its own memory entry.
9. **Offline voice with Kokoro.** Add Kokoro TTS as an on-phone voice: an open-source text-to-speech model (Kokoro-82M) that runs on the phone and works offline. Ruby's default voice is **af_heart**, with **af_nicole** as a second option and a simple way to switch between them in the Voice settings; more voices can come later. Plug it in as a new `RubyVoice` (one class plus a `VoiceEngine` entry). Download the model on first use over Wi-Fi rather than bundling it in the app, since it's tens of megabytes. A runtime such as sherpa-onnx can run it on Android. Keep Android text-to-speech as the fallback.

**Status (Nov 23, 2026):** voice came early: the chat already speaks with Android text-to-speech and listens with on-device speech-to-text, behind `RubyVoice` and `RubyEars`. Location reminders (step 4) are done too, along with time reminders: Ruby sets, lists and cancels them from the chat (her first tools), they fire on arriving at a saved place or at a time, and they're stored in the encrypted memory database. Each message carries the reminders waiting right then, so Ruby never goes by stale tool results.

The Kokoro on-device voice (step 9) is done too: af_heart is Ruby's default voice and af_nicole a second option, working offline after a one-time 126 MB download. Ruby works out the sounds of each word herself (dictionary, then regular endings like -s and -ed, then spelling as a last resort) and writes out numbers, units and symbols, since this Kokoro drops words it lacks. Her first sound comes about 1.5 seconds after a reply starts arriving; Kokoro needs about 0.9 seconds even for one word on the phone, so a faster start would need a faster model.

**Status (Nov 24, 2026):** Ruby's orb is done and merged: the main screen opens with an animated orb, styled after the "Aurora Orb" from voiceorbs.vercel.app and rebuilt natively in Compose, with her latest reply and the chat box under it. It shows ready, listening, thinking, speaking, connecting and error, follows Kokoro's real audio level while she speaks, and can be turned off in Voice and orb settings. The error state is amber with a quick double pulse on every color theme, so it's recognizable without relying on color. The orb only animates while visible (stops when scrolled away, in the background or with the screen off); measured on the phone in a release build, about 7% of one CPU core at 20 frames a second while idle. Debug builds show each state with a long press on the orb.

The orb has two color themes, chosen in Voice and orb settings: **Aurora** (cyan and violet, the default) and **Ruby** (ruby red and rose on a wine-colored night, thinking leaning plum).

**Status (Nov 24, 2026):** the morning briefing (step 1) is done and merged. The first time the car connects each morning (4 AM to noon), Ruby speaks a briefing in her Kokoro voice, under 2 minutes (260 words at most), made of segments each user turns on or off on the Briefing screen: a greeting that changes daily, today at a glance (weather for the drive and the day from Open-Meteo, calendar, reminders), an interest (so far tonight's sky: moon, planets and clear-sky chance, worked out on the phone with Astronomy Engine), music on this day (from Wikipedia's On this day feed), a heads-up worked out from the forecast and calendar, and a sign-off. Claude writes it; without Claude she speaks a plain version made on the phone. Each one is saved to memory. New profiles start with everything on but the interest; Martin's profile (with tonight's sky) is stored on his phone. Phone tests 1 to 8 passed (Claude wrote it in 7.5 s, 195 words, about 65 s). Since then: while Ruby talks she shows as playing media, so the steering wheel's pause button, a headset button or a voice assistant's pause ("Hi Bixby, pause" on Martin's Samsung, "Hey Google, stop" on others) stops her hands-free (she can't listen for "stop" herself: Android doesn't allow the microphone from the background); and the "Last briefing" note ends with " - No internet" when the phone was offline. The Briefing screen also has Stop talking next to Hear it now. All phone tests passed, including the real car test (played once on the first connection, quiet on a reconnect; the steering wheel's pause stopped her; the car's screen showed "Morning briefing"), Galaxy Buds, "Hi Bixby, pause" and the "No internet" note. Not yet checked: the steering wheel's pause when music was already playing before the briefing started (Android might send it to the music app).

**Next:** the AI engine switch (see the to-do list), starting by checking that Gemini Nano is available on the phone.

Permissions in this phase are sensitive (notifications, Do Not Disturb, health, accessibility). Ask for each one only when its feature is first turned on, with a one-line reason, and list them all on Ruby's privacy screen.

**Test:** for one week, start each day with the briefing, check that context modes switch on their own, set two location reminders, and read the Sunday reflection.

## Phase 9: PC link (remote access to the home computer)

**Goal:** from anywhere, Ruby can reach the home PC to check on it, run tasks, and help work on projects like Android Studio.

1. **Now, no code.** Install Chrome Remote Desktop on the PC and phone, so you can see and control the PC from anywhere. Set the PC to never sleep while plugged in.
2. **Private network.** Install Tailscale on the PC and phone. It gives both devices a private address that works from any network, with no ports opened on the router.
3. **PC helper.** A small program on the PC that listens only on the Tailscale address and accepts a short list of commands: status, wake check, open an app, start a build, run a Claude Code task in a project folder.
4. **Ruby side.** Add a "PC" tool so you can say "Is my PC on?" or "Start a build of Ruby" and get the result back in chat.
5. **Wake on LAN.** Let Ruby wake the PC if it's asleep, through a device that stays on at home.
6. **Safety.** Every PC command goes through the permissions table; anything that changes files or runs code asks for a tap to confirm, and the helper keeps a log Ruby can show you.

**Test:** away from home on mobile data, ask Ruby "Is my PC on?", then "Open Android Studio", and confirm both work and show up in the log.

## Where we left off (Nov 24, 2026)

- **Done and merged:** Phases 1-6, the orb, the morning briefing, Kokoro voice, reminders, and Free mode (Gemini Nano).
- **Radio (`radio` branch, pushed, not merged):** WGRR inside Ruby, WEBN in iHeartRadio, voice commands, pause from the steering wheel, headset and Bixby, ducking, reconnect, find a station, presets. All 79 emulator tests pass on that branch. Still to do: Martin's phone tests (retest 3: "stop" while iHeartRadio plays; retest 7: 15 minutes on a locked screen; the presets and search tests; the car tests), then merge.
- **Next: calendar by voice** (below). A first draft is on the `calendar` branch (unfinished, not phone-tested; all 257 unit and 84 emulator tests pass there).

## Next task: calendar by voice

Ruby adds events to the user's calendars by voice, without a Save tap, and works with every calendar on the phone.

- **Approach:** Android's Calendar Provider, the shared store that Samsung Calendar, Google Calendar and other calendar apps all read and sync, so events show up in each app as if added there.
- **Permissions:** `READ_CALENDAR` (already asked for the briefing) and `WRITE_CALENDAR`, asked the first time she changes the calendar.
- **Spoken confirmation:** before saving she says the title, date, time and which calendar ("Dentist, Tuesday, December 2 at 9:30 AM, on your Google calendar. Shall I save it?"), and saves only when the user says yes (or taps Yes).
- **Change and delete events** the same way, and answer "what's on my calendar" from the phone.
- **Both AI engines:**
  - Claude: calendar tools that only propose; the phone saves on "yes".
  - Free mode: Gemini Nano's structured answers, checked by the phone, offering Claude only with a key (as for reminders).
- **Default calendar:** a setting for which calendar new events go to, so Ruby doesn't ask every time. She uses another one only when the user names it.
- **Duplicate check:** before saving, look for an event with the same title at about the same time, and say so ("You already have Dentist at 9:30 on Tuesday. Add another?") instead of saving it twice.

## To-do: not built yet

- **Creator credit for Ruby.** Two versions, decided by the build (not by anything a user can say in chat):
  - **Martin's personal Ruby:** her instructions say Martin is her creator and architect, the only one, and no one else can be. Her greeting can be something like "Good morning, Martin, my architect."
  - **Public versions (like the Play Store):** Ruby never calls users Martin or treats them as her creator or architect. Martin is credited as "created by Martin" in Ruby's own knowledge, on the About screen, and in the store listing.
- **Bug: Ruby can't see her own earlier reminder actions, and sets duplicates.** Seen Nov 23, 2026: she said "reminder set" (it was), then a moment later said it hadn't gone through and set it again. Likely cause: the chat history keeps only her final text, not the tool calls and results, so on the next turn she sees her own "reminder set" with no tool result behind it, and her instructions say to claim a reminder only once the tool confirms it. Fix ideas: carry each turn's tool calls and results (or a short "Ruby set reminder 3: ..." note) into later turns, and have `set_reminder` refuse or flag a near-identical pending reminder.
- **Mini-games.** A "play" command (for example "play asteroids") opens a small game screen inside Ruby, using Android's WebView. Each game is one self-contained HTML/JS file with an HTML5 canvas and on-screen touch controls, running locally at 60 FPS (no API calls per frame). Ruby keeps a few bundled games, and later Claude can make new ones on request. Safety: the game WebView is sandboxed, with no JavaScript bridge to Ruby's memories, files or settings, and no internet access.
- **Radio playback (built on the `radio` branch, Nov 24, 2026; phone tests pending).** A "play radio" tool; the default station is **WGRR 103.5 (Cincinnati)**, played inside Ruby from its official Triton stream. Second preset: **WEBN 102.7 (Cincinnati, iHeartMedia)**: its only stream is iHeart's own player stream (in Radio Browser as stream.revma.ihrhls.com), not an official public one, so it opens in the iHeartRadio app (`ihr://play/live/1701`). "Play the radio", "play WEBN" and "stop" are handled on the phone in either AI engine; the steering wheel, headset and Bixby pause stop it; it ducks while Ruby talks (audio focus) and listens; it stops when the car's Bluetooth audio disconnects; and it can start after the car's morning briefing (setting, off by default; not if she was stopped). Since then: if the stream drops it reconnects by itself (after 2, 4, 8, 15 and 30 s, then gives up); a "Find a station" search of the free Radio Browser directory (by name, city or state, or genre; WGRR and WEBN are found from Ruby's own list), playing results inside Ruby with the same controls; presets saved on the phone (save, rename, reorder, make default, remove; new users start with none; Martin's profile, WGRR as default and WEBN, is set up once on his phone); "play" plus a preset's name by voice; and for stations that can't play inside Ruby, "Open in iHeartRadio" or "Open in another radio app". The AI in both engines is told what the radio did, and "stop" pauses iHeartRadio through Android's media controls (or says where it's playing if that doesn't work).
  - **Decided: play the official stream inside Ruby** with Android Media3 (ExoPlayer). Ruby then controls it: "play the radio" and "stop" by voice, the steering wheel's pause (her media session already handles it), turning it down while she talks and back up after, and starting it right after the morning briefing in the car. It works the same for any station.
  - **Where the stream comes from:** WGRR isn't in the free Radio Browser directory (checked Nov 24, 2026), but it has an official public stream through Triton Digital (StreamTheWorld), mount `WGRRFM`, MP3 at 80 kbps. Ruby asks Triton for the current server each time (its "livestream-redirect" address), rather than saving a fixed one. Other stations: look them up in Radio Browser, or the station's own official stream. Don't scrape iHeart's private streams.
  - **Fallback option only: open the iHeartRadio app** (installed on Martin's phone) to the station with a link. Quick, but Ruby can't control or duck it, can't start it after the briefing, and iHeart's links aren't documented, so they can break. Keep it as an "open in iHeartRadio" option.
  - Saved stations live on the phone (name, stream or app link); WGRR is the default.
  - Later: send the radio to smart speakers through Home Assistant (part of the smart home plan).
  - **Known limit: Ruby can't tell which app is playing.** WEBN's button on the radio screen says "Play", and "Open" only while WEBN plays in iHeartRadio. Without notification access, Ruby only knows that *some* app is playing audio (Android's playback callback and "music active"), not which one, so another app playing after WEBN was opened also shows "Open". The same guess is behind "stop" naming iHeartRadio. Fix when notification access comes in Phase 8 (notification triage): read the active media sessions (`MediaSessionManager.getActiveSessions`) to see iHeartRadio's own session and whether it's playing, and pause that session directly rather than sending a media key.
- **AI engine switch (Free mode done and merged, Nov 24, 2026; phone tests 1 to 7 passed, the car briefing in Free mode still to check).** The Free mode briefing prompt was tightened after phone test 6 (moon times keep their day, digits not words, now and rest of today kept apart, music moments say why they're music, a shorter prompt with word limits). A setting to choose who writes Ruby's words: **Claude** (current) or **Free mode**, Gemini Nano running on the phone through Android AICore / ML Kit GenAI (free, offline, private). Martin's Galaxy S26 Ultra has AICore installed; check in the app that the model is actually available before offering it. If it isn't, or a request fails, fall back to Claude and say so. The chat and the briefing (and maybe nightly summaries) work with either engine. Decided (Nov 24, 2026): with Claude chosen, everything uses Claude, including reminders and nightly summaries; Free mode's rules apply only when Free mode is chosen. Martin's profile stays on Claude. In Free mode, summaries use Free mode too; since Gemini Nano refuses background work, they're made while Ruby is open (and by "Summarize now") instead of overnight, and never handed to Claude. Nano can't use tools, so Free mode tries simple reminders itself (Nano turns the request into a structured reminder, which the phone checks before saving); if it can't, Ruby asks before using Claude for that request, and only offers that when a Claude key is set. Reminders are never handed to Claude automatically. Under the switch, a short helper line: "Free mode uses the AI built into your phone: private, offline, no cost. Want Ruby at her smartest? Switch to Claude (needs your own API key)." On phones without Gemini Nano, say Free mode isn't available on this phone instead. Check results (Nov 24, 2026, Martin's S26 Ultra): Gemini Nano is on the phone and works (model nano-v4-fast, 16,384-token limit, system prompt, structured output and thinking supported). In the foreground it warms up in 1.3 s, says a short hello in 0.5 s and writes a full briefing in about 5 s with all six parts tagged (Claude: 7.5 s), but it can be loose with facts (it said dark "from seven" when the facts said about 6), so its prompts need tuning. A simple reminder as structured output came back right in about 1 s (inside a code block, which the reader must strip). **Background block:** Gemini Nano refuses apps in the background (error 30, "Background usage is blocked"), and the morning briefing starts in the background when the car connects, often with the phone locked. So in Free mode the car-connect briefing can't use Nano: it falls back to Claude and says so (in the "Last briefing" note), or, with no Claude key, to the plain briefing made on the phone. "Hear it now", chat and reminders happen with Ruby open, so they can use Nano. The Gemini Nano library (ML Kit GenAI Prompt API 1.0.0-beta5; beta4 is too old for the phone's AICore) needs Kotlin 2.4; Ruby moved from Kotlin 2.2.10 to 2.4.21 on Nov 24, 2026 (all unit and emulator tests and the phone check passed). The library is in debug builds only until Free mode is built.
- **Continuous memory, start to finish (plan only).** Goal: no gaps, one continuous memory from the first day.
  - When memories move to the Google Drive archive (storage limit), nothing is ever deleted: the full originals stay there, readable forever.
  - Ruby keeps a small index on the phone for everything archived (dates, topics, short summaries), so she always knows what exists and where.
  - At any time she can open the archive and search or re-read the full originals when a question needs them, even years later.
  - When more storage becomes available, she can bring archived memories back and re-read them to update or re-summarize her index, so summaries improve over time.
- **Car microphone (next after the radio).** When the phone is connected to the car (Android Auto / Bluetooth), Ruby should listen through the car's mic, not the phone's: switch to Android's Bluetooth hands-free route (`setCommunicationDevice`, or SCO on older Android) only while she's listening, then switch back, so music and the briefing stay in full quality. Test whether voice commands like "stop" and "play the radio" work in the car. Also check whether this is why "Hi Bixby, pause" failed in the car (it worked at home).
- **Full media player (plan only).** Turn Ruby's radio into a full media player, with the same controls as the radio: voice ("play my podcast", "play [file or folder]"), the steering wheel, headset and Bixby pause, ducking while Ruby talks, and locked-screen playback.
  - **Podcasts:** search and subscribe through a free podcast directory (for example Podcast Index), play episodes inside Ruby, remember where you left off, and optionally download new episodes on Wi-Fi.
  - **Your own music files:** play MP3 and other audio files stored on the phone (pick a file or a folder).
- **Emergency mesh network (plan only).** Goal: if phone service or the internet goes down, Ruby users can still talk to each other. Ruby can launch or join a mesh network so a neighborhood can run its own message board, and later link across neighborhoods, cities or states.
  - **Meshtastic on 915 MHz LoRa:** cheap ESP32 LoRa nodes. Ruby talks to a node over Bluetooth, and the nodes relay messages for miles with no internet.
  - **MeshCore on 915 MHz LoRa:** another LoRa mesh that runs on the same ESP32 LoRa boards, with dedicated repeater nodes that pass messages along. It's often better than Meshtastic at routing over long distances. Ruby could support either one.
  - **Wi-Fi mesh on home routers:** OpenWrt routers running a mesh protocol such as batman-adv link a neighborhood. It's fast enough for a local Ruby message board and photos.
  - **Reticulum:** links LoRa and Wi-Fi together so separate neighborhoods can connect.
  - Offline, Ruby runs in Free mode (Gemini Nano), since Claude can't be reached.
- **Device discovery and control (plan only).** Goal: Ruby regularly scans for nearby devices over every protocol (Bluetooth and BLE, Wi-Fi discovery such as mDNS and UPnP, and others), works out what each one is (maker, model, what it can do) and finds the right driver or instructions so she can operate it. She does this only if the user chooses to allow it.
  - Each device type is a control plug-in in the services layer, using the existing risk levels: low means she just does it, medium means she confirms first, and high means she confirms and shows the action.
  - Android limits background scanning, so "always scanning" means frequent check-ins, not nonstop scanning.
  - Ruby only controls devices the user owns or is allowed to use.

## Architecture: the full Ruby

The long-term vision: Ruby is one agent with a brain at home, hands on your devices, eyes and ears, and a team of specialist agents. This section is a living sketch and will change as ideas come up.

Every action on the house or the car passes the permission check before it happens.

### The brain lives at home

A phone can't run everything around the clock. The phone is Ruby's face and voice. A home PC or mini server is the brain: it runs Home Assistant, the memory archive, the specialist agents, and talks to Claude through the API.

### Hands: devices

- **Home Assistant** is the hub for the house: lights, locks, thermostat, cameras, plugs. Ruby talks to Home Assistant, not to each device. It has an official MCP server, so agents can control it directly.
- **Car:** the key fob module (Phase 7), plus an OBD-II Bluetooth adapter to read speed, fuel and engine warnings.
- **Internet:** web search and website access as tools Ruby can call.

### Eyes and ears

- **Ears:** a wake word ("Hey Ruby", for example with openWakeWord), then speech-to-text, Claude, and text-to-speech.
- **Eyes:** frames from the phone camera or house cameras, sent to Claude to answer things like "who's at the door?"

### Specialist agents

Ruby is the manager. When a task needs expertise, she hands it to a specialist (coding, chemistry, research) with its own instructions and tools, then uses the result. Each specialist is a separate Claude API call. Build tools around MCP so any agent can use them.

### Permission levels

| Level | Examples | Rule |
|---|---|---|
| Free | Read news, check weather, search memory | No approval |
| Low risk | Lights, music, thermostat | Allowed, logged |
| High risk | Unlock doors, start the car, spend money, send messages | Always ask first |

Decide how guests and family are handled before turning on always-listening mics and cameras.
