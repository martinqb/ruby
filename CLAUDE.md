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

**Goal:** every conversation is saved on the phone, searchable, and backed up to drives the user controls. The AI provider never keeps the archive.

1. **Save chats locally.** Store every message in a Room database (Android's SQLite library) encrypted with SQLCipher. Keep the encryption key in the Android Keystore.
2. **Add search.** Start with SQLite's built-in full-text search (FTS5). Later, add on-device embeddings so the app can find past chats by meaning, not just keywords.
3. **Send only what's relevant.** With each new message, the app finds the few most relevant past snippets and sends them along to Claude. This is called RAG (retrieval-augmented generation), and it's how "forever" memory fits in a limited context window.
4. **Summarize old chats.** Periodically condense older conversations into short summaries, so search stays fast and fewer tokens are sent.
5. **Encrypted backups.** Export the database, encrypted with a key only the user holds, to their PC, a home drive or NAS (Syncthing works well), or their own cloud storage. Run it automatically with WorkManager, for example nightly while charging on Wi-Fi.
6. **Restore and control.** Add a restore screen for a new phone, plus view, export and delete buttons so users can see and manage everything stored.

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
