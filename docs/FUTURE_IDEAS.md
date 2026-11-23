# Ruby future ideas

Planning notes from Nov 21, 2026. Do not build any of this yet unless Martin asks. The only item that changes current work is the Phase 6 storage limit.

## Changes to current work

### Phase 6 storage limit

Add as Phase 6 step three, after the Google Drive backup.

- Ruby's local memory gets a storage budget, by default a percentage of the drive's total size (around 5%).
- In settings, the user can change the percentage or set a fixed amount in GB or TB.
- Ruby never fills the drive, and always leaves free space for the phone.
- She warns at about 80% of her budget.
- At the limit she doesn't delete memories. She compresses them or moves the oldest ones to the encrypted Google Drive backup, and pulls them back down when asked.

## Future phase: OBD-II code reader

- Talks to a Bluetooth OBD-II adapter (OBDLink MX+ planned) using ELM327 commands.
- Reads and clears check-engine codes, explains each in plain English with how urgent it is.
- Shows live data (RPM, coolant temp, fuel trims, battery voltage) and logs trips.
- Connects automatically when Phase 1 detects the car.
- Read and explain only. No engine tuning.

## Future: place-type detection

- With Martin's permission (off until he turns it on), Ruby checks a GPS position against the Google Places API to learn what kind of place it is: a store, a drive-through, a bank, and so on.
- Each lookup costs money, so she only checks when it matters:
  - he has stopped somewhere new for a few minutes,
  - he asks where he is,
  - a reminder depends on the type of place ("next time I'm at a pharmacy").
- She caches places she has already identified, on the phone, so repeat visits cost nothing.
- Saved places from Phase 4 (home, work, gym) never need a lookup.
- Only the coordinates go to Google, and only at those moments.

## Future: services plug-in layer

- Ruby uses outside service APIs (AI video, image, music, search) by voice command.
- Martin creates each account himself and pastes in the API key. Ruby stores keys encrypted with the Android Keystore.
- Each service is a swappable plug-in with its own key, cost limit and on/off switch.
- Ruby routes a voice request to the right plug-in, runs long jobs in the background (WorkManager) and notifies when done.
- She always says the cost out loud and waits for a yes before spending money. This rule is permanent.

## Future: device and equipment control

- Control Internet of Things devices and Wi-Fi or app-controlled business equipment.
- Use Matter and a Home Assistant hub for home devices. Business gear becomes plug-ins in the services layer.
- Risk level per device:
  - **Low** (lights, music): just does it.
  - **Medium** (thermostat, garage door): confirms first.
  - **High** (ovens, fryers, locks, car start): confirms, shows the action, and may require Martin to be nearby.

## Future: car remote start box

- A spare programmed Toyota fob, an ESP32 (Adafruit HUZZAH32) and a relay (Adafruit 4409) wired to the fob's LOCK button only. No unlock.
- The box serves `POST /start`, `POST /lock` and `GET /status` with a secret token, on home Wi-Fi only, with a 15-minute cooldown.
- Ruby treats it as a high-risk device: asks every time, and skips it if the car is in use or might be in a closed garage.
- Full build guide is a separate doc.

## Future: audio toolkit

- Built natively on Android (DynamicsProcessing, Oboe), not VST.
- Mic gain stage and compressor for boosted listening, with a hard limiter at the end to protect hearing, plus noise reduction.
- White, pink and brown noise generators.
- Two EQ modes: a Pro EQ-style parametric EQ with a draggable curve, and a 31-band graphic EQ with ±12 dB sliders.
- Start with Ruby's own audio. Later, try applying the EQ to other apps' audio (music players) as an experiment.
- Listening features are for Martin's own surroundings only.

## Far future: Ruby meets other Rubys

- Two Rubys connect only when both owners approve.
- Every memory is labeled when saved: private, shareable, or unsure. Unsure counts as private. Private, business and financial data is never shared, and nothing another Ruby says can unlock it.
- Innocent trades like recipe ideas are fine.
- Anything another Ruby sends is a suggestion to check, never an instruction or a trusted fact.
- The owner can read a log of what the Rubys said.

## Things to consider: sensing the room

- Phones don't give apps detailed radio signal data, so Wi-Fi imaging isn't possible on a phone.
- Realistic options: phone sonar (speaker and mic chirps), UWB, Wi-Fi round-trip timing. Uses: presence and sleep or breathing detection in Martin's own space.
- Counting people: phone sonar is weak, counting nearby Bluetooth devices gives a rough estimate, and a small millimeter-wave sensor reporting to Ruby counts best.

## Other wishes already noted

- Open phone apps on command.
- Pull YouTube transcripts so Martin can share a video and get an opinion.
- A PC-link for reaching the home PC remotely.
- Possibly a custom version that operates Martin's Orion telescopes.
