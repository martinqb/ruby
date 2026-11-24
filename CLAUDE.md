# Ruby: Context-Aware Android Agent

## Build Plan

A beginner-friendly, step-by-step plan for building an Android app that senses what you're doing (driving, walking, at home), keeps that sensing private on the phone, and sends short context labels to Claude.

### Big picture

- **On the phone:** all sensing (Bluetooth, motion sensors, GPS), all decisions about what you're doing, and all privacy controls.
- **Sent to Claude:** only short labels like "driving" or "at the gym", along with your messages, through Anthropic's API.
- **No Google activity service:** detection is done yourself from raw sensors, so the data stays on the device.

Build in phases. Each phase should work on its own before you start the next one.

### Phase 0: Set up your tools

1. Install Android Studio (free, from developer.android.com/studio).
2. In Android Studio, create a new project using the Empty Activity (Jetpack Compose) template. Language: Kotlin. Minimum SDK: API 29 (Android 10) or higher.
3. Turn on Developer options on your phone (Settings > About phone > tap Build number 7 times), then turn on USB debugging.
4. Plug the phone in and press Run. A "Hello Android" screen on your phone means you're set up.
5. Learn the basics first (a few evenings): Google's free Android Basics with Compose course covers Kotlin, screens, buttons and state.
6. Set up Git and a free GitHub repo so you can always undo mistakes.

### Phase 1: Detect your car's Bluetooth

**Goal:** the app shows "Driving" when the phone connects to your car, and "Not driving" when it disconnects.

1. Add permissions to AndroidManifest.xml: `BLUETOOTH_CONNECT` (Android 12+) and `BLUETOOTH` for older versions.
2. Ask for permission at runtime the first time the app opens.
3. Register a `BroadcastReceiver` for `BluetoothDevice.ACTION_ACL_CONNECTED` and `ACTION_ACL_DISCONNECTED`.
4. When a device connects, read its name and address. Add a settings screen where you pick which device is your car and save it with DataStore.
5. If the connected device matches your car, set the state to Driving.
6. Show the current state on the main screen.

**Test:** get in the car, start it, and watch the label change.

### Phase 2: Keep it running in the background

Android kills background apps aggressively, so this is where many first apps get stuck.

1. Create a foreground service with a small ongoing notification ("Context sensing on"). That's the approved way to run continuously.
2. Declare the service type in the manifest (`connectedDevice`, plus `location` later).
3. Start the service on boot with a `BOOT_COMPLETED` receiver.
4. Ask users to exclude the app from battery optimization; some phones (Samsung, Xiaomi) need this.

### Phase 3: Motion sensing with raw sensors

**Goal:** tell still, walking and riding in a vehicle apart without Google's Activity Recognition.

1. Use `SensorManager` to read the accelerometer at a low rate (`SENSOR_DELAY_NORMAL`) to save battery.
2. Compute the magnitude of acceleration and look at short windows of about 5 seconds.
3. Simple rules to start:
   - **Still:** very little variation.
   - **Walking:** a regular bounce around 1.5 to 2.5 steps a second (the step detector sensor helps here).
   - **Possibly in a vehicle:** smooth motion with steady low vibration, no step rhythm.
4. Use the significant motion sensor to wake the app only when the phone starts moving, which saves a lot of battery.
5. Later you can train a small on-device model with TensorFlow Lite on recordings of your own trips, but rules are fine for version one.

### Phase 4: GPS to confirm

1. Add `ACCESS_FINE_LOCATION`, and `ACCESS_BACKGROUND_LOCATION` if needed. Expect Android to make users approve background location separately.
2. Only turn GPS on when motion suggests a vehicle, and turn it off again after a minute or two.
3. Use Android's built-in `LocationManager` rather than Google's fused location if you want to stay off Google services.
4. A speed held over about 15 to 20 mph confirms Driving.
5. Save a few places on the phone (home, work, gym) and label them when you're within about 100 meters. This is called geofencing.

### Phase 5: The decision engine

Combine everything into one current state, in order of confidence:

1. Car Bluetooth connected means **Driving**.
2. GPS speed over the threshold means **In a vehicle**.
3. Inside a saved place means that place's label.
4. Otherwise, the motion result: **Walking** or **Still**.

Only change the state after it holds for 30 to 60 seconds, so a stoplight doesn't flip you to Still.

### Phase 6: Memory that lasts forever, owned by the user

**Goal:** every conversation is saved on the phone, searchable, and backed up to the user's own Google Drive. The AI provider never keeps the archive.

1. **Save chats locally.** Store every message in a Room database (Android's SQLite library) encrypted with SQLCipher. Keep the encryption key in the Android Keystore.
2. **Add search.** Start with SQLite's built-in full-text search (FTS5). Later, add on-device embeddings so the app can find past chats by meaning, not just keywords.
3. **Send only what's relevant.** With each new message, the app finds the few most relevant past snippets and sends them along to Claude. This is called RAG (retrieval-augmented generation), and it's how "forever" memory fits in a limited context window.
4. **Summarize old chats.** Periodically condense older conversations into short summaries, so search stays fast and fewer tokens are sent.
5. **Encrypted backups to the user's own Google Drive.** Back up to the user's Google Drive, not a server, using the Drive API's private app data folder (`drive.appdata` scope) so only Ruby can see the files. Compress and encrypt the database on the phone before uploading. Derive the backup key from a passphrase the user chooses (or give them a recovery key to save), not only a key locked to this phone, so the backup can be restored on a new phone. Run it automatically with WorkManager, for example nightly while charging on Wi-Fi.
6. **Storage limit.** Give local memory a storage budget, by default about 5% of the phone's total storage, which the user can change in settings to another percentage or a fixed amount in GB or TB. Ruby never fills the drive and always leaves free space for the phone. She warns at about 80% of her budget. At the limit she never deletes memories: she compresses them or moves the oldest ones to the encrypted Google Drive backup, and pulls them back down when asked.
7. **Restore and control.** Add a restore screen for a new phone, plus view, export and delete buttons so users can see and manage everything stored.

**Status (Nov 23, 2026):** Phase 6 is done. Memory is encrypted on the phone with search (steps 1-2), each chat message takes along up to 5 related memories (step 3), and days of chat older than a week are summarized nightly by Claude, while charging on Wi-Fi, with the summaries used in recall (step 4). Backups go to the user's own Google Drive nightly, with a backup history (step 5); the storage limit and export are done (steps 6-7). The full restore test passed: back up, uninstall, reinstall, restore with the recovery key, and messages and reminders came back. Export doesn't include summaries yet; they can be made again from the messages.

**Test:** chat for a few days, wipe the app, restore from backup, and ask about something from day one.

Be upfront in the privacy policy: each message and its memory snippets still go to the AI provider to get an answer. The archive itself stays with the user.

### Phase 7: Car remote start (2022 Corolla)

**Goal:** the agent can start the car, for example on cold workday mornings. It works by "pressing" a real spare fob, so Toyota's rolling codes keep working. Don't try to record and replay the fob signal: it won't start the car, and it can knock your real fob out of sync.

1. **Get a spare fob.** Buy one programmed to your car; a dealer or locksmith can pair it.
2. **Hardware.** An ESP32 board (about $5-10), plus a small relay or optocoupler to close the lock button's contacts, and a 3V supply in place of the fob's coin battery.
3. **Wire it up.** Open the spare fob and solder two thin wires across the lock button's contacts, leading to the optocoupler. Keep the original button working.
4. **Firmware.** Program the ESP32 (Arduino IDE) to send the start pattern: tap lock, tap lock, then hold lock for about 3 seconds.
5. **Placement.** Fob remote start only reaches about 80 feet, so mount the module inside your home or garage near where the car parks. Never leave it inside the car, since anyone could start it.
6. **Connect it to the app.** The ESP32 joins your home Wi-Fi and exposes a simple, password-protected command. The Android app calls it with a "Start car" button, then from the decision engine (for example, workday mornings below 40 F).
7. **Safety rules.** Require confirmation for automatic starts at first, never start the car in an enclosed garage, and log every start.

**Test:** press "Start car" in the app from inside the house and watch the hazard lights flash before the engine starts.

### Phase 8: Phone-only superpowers

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

**Next:** the morning briefing (step 1), spoken in the Kokoro voice.

Permissions in this phase are sensitive (notifications, Do Not Disturb, health, accessibility). Ask for each one only when its feature is first turned on, with a one-line reason, and list them all on Ruby's privacy screen.

**Test:** for one week, start each day with the briefing, check that context modes switch on their own, set two location reminders, and read the Sunday reflection.

### Phase 9: PC link (remote access to the home computer)

**Goal:** from anywhere, Ruby can reach the home PC to check on it, run tasks, and help work on projects like Android Studio.

1. **Now, no code.** Install Chrome Remote Desktop on the PC and phone, so you can see and control the PC from anywhere. Set the PC to never sleep while plugged in.
2. **Private network.** Install Tailscale on the PC and phone. It gives both devices a private address that works from any network, with no ports opened on the router.
3. **PC helper.** A small program on the PC that listens only on the Tailscale address and accepts a short list of commands: status, wake check, open an app, start a build, run a Claude Code task in a project folder.
4. **Ruby side.** Add a "PC" tool so you can say "Is my PC on?" or "Start a build of Ruby" and get the result back in chat.
5. **Wake on LAN.** Let Ruby wake the PC if it's asleep, through a device that stays on at home.
6. **Safety.** Every PC command goes through the permissions table; anything that changes files or runs code asks for a tap to confirm, and the helper keeps a log Ruby can show you.

**Test:** away from home on mobile data, ask Ruby "Is my PC on?", then "Open Android Studio", and confirm both work and show up in the log.

### To-do: not built yet

- **Creator credit for Ruby.** Two versions, decided by the build (not by anything a user can say in chat):
  - **Martin's personal Ruby:** her instructions say Martin is her creator and architect, the only one, and no one else can be. Her greeting can be something like "Good morning, Martin, my architect."
  - **Public versions (like the Play Store):** Ruby never calls users Martin or treats them as her creator or architect. Martin is credited as "created by Martin" in Ruby's own knowledge, on the About screen, and in the store listing.
- **Bug: Ruby can't see her own earlier reminder actions, and sets duplicates.** Seen Nov 23, 2026: she said "reminder set" (it was), then a moment later said it hadn't gone through and set it again. Likely cause: the chat history keeps only her final text, not the tool calls and results, so on the next turn she sees her own "reminder set" with no tool result behind it, and her instructions say to claim a reminder only once the tool confirms it. Fix ideas: carry each turn's tool calls and results (or a short "Ruby set reminder 3: ..." note) into later turns, and have `set_reminder` refuse or flag a near-identical pending reminder.

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
