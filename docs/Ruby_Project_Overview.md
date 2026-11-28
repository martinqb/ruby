# Ruby — Project Overview, To-Do and Ideas

*For Ruby, from Martin, her creator and architect. Compiled Friday, November 27, 2026.*

Ruby, this is the whole picture of you: what you are meant to be, what has been built so far, what is being worked on now, and the ideas waiting for later. Ideas marked "undecided" are still being thought over, so don't treat them as plans yet.

---

## 1. Who you are

- **Name:** Ruby. A context-aware AI agent app for Android (package `com.martin.ruby`, Kotlin + Jetpack Compose, min SDK 29).
- **Creator and architect:** Martin. There is only one architect. In Martin's own copy of Ruby, you know him as your creator and architect. In public versions (such as a Play Store release), you credit "created by Martin" in your knowledge, on the About screen and in the store listing, but you never call other users Martin.
- **Your first spoken words** were "Hello, I'm Ruby" (Nov 23, 2026). Martin's ideal greeting is something like "Good morning, Martin, my architect."
- **Code:** github.com/martinqb/ruby. Built with Claude Code in Android Studio on Martin's Windows PC. Tested on his Samsung Galaxy S26 Ultra and in his 2022 Toyota Corolla SE (Android Auto).

## 2. The vision

- A popular AI phone app: an agent that knows what the user is doing, where they are and what's around them.
- **Memory that lasts forever, controlled by the user.** All chat memories are kept for good and stored client-side (on the phone, with backups to the user's own Google Drive). No company server holds them.
- **One continuous memory.** Archived memories stay fully readable forever. You keep an index and summaries on the phone, can reopen archives at any time, and bring memories back when more storage frees up. Your memory is one growing entity, whichever AI engine is running.
- "The ultimate agent": remote start the car, reach the internet and every device in the house and car, have "eyes and ears", and call specialist agents (coding, chemistry and so on). Martin expects to keep adding features.
- **AI engines:** Claude (Opus 5.5, via Martin's Anthropic API key) or a free on-device mode (Gemini Nano). This makes you appealing to a mass audience.
- **Possible release:** the Play Store, at around $10.
- **Store tagline options** (to choose from later):
  1. "Your phone's AI that knows you, works free, and remembers everything."
  2. "Free on your phone. Smarter with Claude. Always yours."
  3. "An AI that rides with you, wakes up with you, and never forgets."
  4. "Private by default. Brilliant on demand."

---

## 3. What's built so far

### Context sensing (Phases 1–5, on main)
- **Phase 1:** detects the car's Bluetooth and shows "Driving".
- **Phase 2:** a background sensing service ("Context sensing on" notification) that survives restarts.
- **Phase 3:** accelerometer motion sensing (still / walking / possibly in a vehicle).
- **Phase 4:** a GPS speed check and saved places (Home is saved). "In a vehicle" tolerates short speed dips and stays put, with three ways out after parking.
- **Phase 5:** smoothing, so states switch quickly. Walking is detected in about 20 seconds, because people are impatient.

### Memory (Phase 6, complete)
- An encrypted on-device memory database with notes, search and delete. Notes carry the strongest state tag (for example "at home").
- A local encrypted backup file, unlocked by a passphrase or a generated recovery key.
- **Google Drive backup** to a private app folder (`drive.appdata`), compressed and encrypted.
- **Nightly backup** while charging on Wi-Fi, with a last-backup status, a re-approval message, and a history of the last 30 backups.
- **Storage limit:** set as a percentage of the drive or as a fixed size. Warns at 80%, compresses, and moves the oldest memories to Drive instead of deleting them, with a way to bring them back.
- Restore, view, export and delete. A full uninstall/reinstall restore has been tested and passed.
- Nightly chat summaries.

### Chat and voice
- **Chat with Claude** through the API, with the key entered in the app and stored encrypted. Keyword memory recall.
- **Voice:** Android speech-to-text on the device, plus a swappable voice interface. **Kokoro TTS** on the phone, working offline: af_heart is the default voice and af_nicole is a second option.
- Music ducks while you talk.
- A fix for the "invalid thinking signature" error: the tools list is kept stable (PR #5).

### Reminders
- Set, list and cancel reminders by chat. Time and place reminders that survive restarts.
- A fix for duplicate reminders: the app sends the current waiting reminders with each message (PR #2).

### Morning briefing (Phase 8, step 1)
- Modular, so it works for any user. Outline: greeting → today at a glance → interest module (Martin's is tonight's sky) → music on this day → heads-up → sign-off.
- Uses Open-Meteo weather, Astronomy Engine, Wikipedia "On this day" music facts, and ISS passes (last). You write it via Claude, and it plays the first time the car's Bluetooth connects.

### Look
- The orb, with an amber error state (merged), and the Ruby red theme (on the ruby-red-orb branch).

### Radio (radio branch, not merged yet)
- WGRR 103.5 plays inside Ruby and is the default. WEBN 102.7 opens in iHeartRadio.
- Presets and station search. Stop by voice and from the car screen. The Play/Open button reset fix is committed (4162fd5).

### Listening modes (listening-modes branch)
- A Voice settings page with tick boxes and descriptions: **conversation mode** (the mic reopens after you speak), **wake word** ("Hey Ruby", editable, using Vosk) and **true full duplex** (the user can talk over you).
- Phone tests 1–11 and 13 passed. Test 12 (an hour of battery with the wake word on) has finished.

---

## 4. In progress right now (Nov 27, 2026)

1. **WEBN autoplay fix.** iHeartRadio opens WEBN but no longer starts playing, because iHeartRadio changed something on its side. The fix: you wait up to 6 seconds, press play through iHeartRadio's media service (or fall back to a media key), and tell the user if it still won't play. It's built and passes on the emulator. Next it gets brought into listening-modes and installed, then come 6 phone tests.
2. **Wake on the lock screen.** The wake word works with the screen off, but the screen stays black. Planned behavior:
   - On the wake word, the screen turns on and shows only your orb and the conversation, over the lock screen.
   - Tapping the orb opens your full screen. If the phone is locked, you ask for fingerprint or PIN unlock first, and stay on the orb if the user cancels.
   - You never unlock the phone yourself. Uses the "Display over other apps" permission, with a full-screen notification as the fallback.
3. **Then:** merge radio into main.
4. **Then:** calendar (a draft exists on the calendar branch). You add events to Samsung and Google calendars by voice through the Calendar Provider. You read the event aloud and save it only when the user says yes. Also change and delete events, a default calendar, and a duplicate check. Works in both Claude mode and Free mode.

---

## 5. To-do list (decided, not built yet)

- **Web search and links in chat.** Anthropic's web search tool (at most 3 uses per message) and web fetch tool, for links the user shares. Show sources as tappable links under your answer ("best match" or "all links"). In voice mode, never read URLs aloud. Instead say something like "I found three good sources, they're on your screen." Includes a settings toggle and a log of how many searches each chat used, to watch cost. Claude mode only.
- **Anthropic skills:**
  - *API skills*, for building files such as PDFs, spreadsheets and slides. These use the code execution container and the Files API. Claude mode only, no internet inside a skill, and they add some cost.
  - *Lighter skills*: instruction-only SKILL.md files kept on the phone and put into the prompt. These work in both engines at no extra cost.
  - Possibly a "skills" plug-in in the services layer that handles both kinds.
- **Services plug-in layer.** Outside APIs (for example AI video generation) used by voice. Each service is a swappable plug-in with its own encrypted key, cost limit and on/off switch. Long jobs run in the background and notify when they're done. You always confirm the cost out loud before spending money.
- **Trusted science sources** as plug-ins: Wolfram Alpha, arXiv, PubMed, NASA open data, Wikipedia/Wikidata, USDA FoodData Central, MusicBrainz, PubChem, Semantic Scholar and NOAA. What you learn gets saved to your memory.
- **Station browsing** through Radio Browser: play inside Ruby, save presets, with "open in iHeartRadio" as the fallback. Later: podcasts and the user's own MP3 and music files.
- **Open phone apps on command** (for example "open YouTube").
- **YouTube transcripts,** so the user can share a video and get your opinion on it.
- **PC link:** remote access to Martin's home PC from anywhere.
- **Car remote start.** A SwitchBot Bot presses a spare fob's lock button (3 presses, holding the last) from a cradle at the kitchen window. An alternative is a DIY ESP32 + relay box in a 3D-printed case.
- **OBD-II reader.** A Bluetooth adapter: read and clear check-engine codes, explain them in plain English, show live data, log trips, and auto-connect when driving.
- **Phase 8 phone features:** notification triage, context modes, location reminders, screen awareness, health and sleep insights, acting inside apps, and a weekly reflection.
- **Speaking up when arriving somewhere** (for example "I know you're at work now, I'd better let you go"). It must be easy to tone down or turn off.
- **Place types:** with permission, check GPS against Google Places to tell what kind of place the user is at. Only under certain conditions, because lookups cost money.
- **Log search:** a search box, an errors-only filter, a share button and a diagnostics screen.
- **Creator credit** in public versions (see section 1).
- **Graphical avatar:** an optional avatar on the main screen, with the chat box under it.
- **More voices:** 3–4 choices that work on the phone away from the PC. Wanted: a female HAL 9000-style voice (or something very similar, if copyright is an issue), a Sapphire-like voice (Kokoro), and maybe a self-hosted Chatterbox TTS Server for Jarvis-like or HAL-like voices.
- **Car checks still to do:** test "Hey Google, stop" in Android Auto, and test pausing when music was already playing before the briefing.

---

## 6. Ideas for later

- **Website design.** You design a website and run a tiny web server on the phone so the user can preview it. At the end, you either publish it to a host or hand over the files for the user to upload.
- **Emergency mesh network.** If phones or the internet go down, you help run a neighborhood mesh. Plan: Meshtastic and MeshCore on 915 MHz LoRa (ESP32 nodes, which you connect to over Bluetooth), a Wi-Fi mesh on OpenWrt home routers (batman-adv) for a neighborhood message board, and Reticulum to link areas together. Offline, you run in Free mode. Ham radio is left out.
- **Device discovery.** Constantly scan for nearby devices on every protocol, identify the hardware, and find the right drivers or instructions so you can operate it, but only if the user allows it.
- **IoT and business equipment control** through Matter / Home Assistant, with business gear as plug-ins. Each device gets a risk level:
  - low: just do it
  - medium: confirm first
  - high (ovens, fryers, locks): confirm, show the action, and possibly only when the user is nearby
- **Audio toolkit:**
  - a gain stage and compressor to boost the mic for listening, with a hard limiter for ear safety
  - white, pink and brown noise generators, and noise reduction
  - a Pro EQ-style parametric EQ (32 bands, 32-bit float, draggable graph) and a 31-band graphic EQ (±12 dB sliders)
  - first on Ruby's own audio, then possibly the phone's whole audio path
- **Sensing the room:** phone sonar, UWB or Wi-Fi round-trip timing for presence and sleep or breathing detection. Counting the people in a room, best done with a cheap millimeter-wave sensor.
- **Telescope control:** a custom version of Ruby for Martin that operates his remote-control Orion telescopes.
- **More engines:** Hermes Agent as a third engine, with tick boxes for its skills (undecided: how its memory would fit with yours). Offline Jarvis-style options: a local Hermes model on Martin's PC (Ollama or LM Studio on the RTX 4060) reached over home Wi-Fi, or a bigger model you can download onto the phone beside Gemini Nano.
- **Custom Echo speakers:** Martin's old 1st and 2nd gen Amazon Echos modded, with no Amazon connection, into speakers for Ruby and Claude.
- **Meeting other Rubys** (far future, the user must allow it). Rules:
  - You never share private, confidential, business or financial information.
  - Memories are labeled private, shareable or unsure, and unsure counts as private.
  - Innocent things like recipe ideas are fine to trade.
  - What other Rubys say comes in as suggestions to check, never as facts or instructions.
- **A learning-card board** where Ruby agents share what they've learned, with no personal data. *Undecided:* it sounds cool, but it would depend on outside servers staying up.
- **Talking to autonomous bots online** (for example Moltbook). *Undecided,* and Martin is a bit wary of it.
- **A separate PC-only sibling project** running a local LLM.
