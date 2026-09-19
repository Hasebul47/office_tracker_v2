# Office Tracker v2

Android app for field and office staff: employees start their workday, the phone records their
route and the places they visit, and administrators see the whole team live and export monthly
hours, distance and travel allowance.

## What changed from v1

| Area | v1 | v2 |
|---|---|---|
| Login | Passwords stored in plain text in Firestore; admin password hard-coded in the app | Firebase Authentication. No passwords in the database or the APK. |
| Access control | Firestore in "test mode" (anyone could read/write everything) | Real security rules (`firestore.rules`): employees see only their own data; admins see everyone. |
| Signing key | Keystore and password committed to the repo | Keystore only in GitHub Secrets. |
| Updates | Downloaded any APK from GitHub | Verifies SHA-256 checksum and the signing certificate before installing. |
| Distance | Every GPS wobble added metres (a phone on a desk "travelled") | Accuracy filter, jitter threshold and teleport rejection. |
| Visits | Fixed 75 m rule, split visits on GPS drift | Dwell-time detection (default 5 min / 80 m), back-dated arrival, drift-tolerant exit, admin-defined places and learned labels. |
| Battery | Constant high-rate GPS | Adaptive: 10 s while moving, 45 s while at a place. |
| Offline | Mixed direct writes | Offline-first: everything is saved on the phone, then uploaded in batches by WorkManager. |
| Cloud cost | One Firestore document per GPS point, written twice | 400 points per document, written once. About 100x fewer writes. |
| Reliability | Tracking stopped silently after reboot | Resumes after reboot and app update; warns about GPS off, battery optimisation and missing permissions. |
| Reports | Single-day CSV | Day, month and whole-team CSV reports, including allowance. |
| Code | Two screens of 1,500-2,300 lines | About 50 focused files (data / tracking / UI), with unit tests. |

## One-time Firebase setup

Use your existing project (`location-tracker-7ff7c`, whose `app/google-services.json` is already included) or a new one.

1. **Turn on Email/Password sign-in.** Firebase Console > Authentication > Sign-in method > Email/Password > Enable.
   Staff still log in with their **mobile number**. The app maps it to an internal login id, so no SMS costs are involved.
2. **Publish the security rules.** Firestore Database > Rules: paste the contents of `firestore.rules`, then **Publish**.
3. **Create the first administrator.** Open the app and tap **First time? Set up organisation**. Enter your name,
   number and a new strong password. This works only once. After that, admins add everyone else from **Team > Add employee**.

## Upgrading from v1

1. Complete the three setup steps above.
2. Sign in as the new administrator and go to **Profile > Import employees from v1**. Each old employee gets a secure
   login with their existing password, and the old record (with its plain-text password) is deleted.
   Anyone whose password is shorter than 6 characters is listed so you can add them manually.
3. In Firestore, delete the old top-level `workdays` collection. v2 stores history under `users/{uid}/days`.
4. **Rotate the signing key.** The old `officetracker.jks` and its password were public in the repository,
   so anyone could sign an APK that your phones would accept as an update. See the next section.
   Phones must uninstall v1 and install v2 **once**, because Android only accepts updates signed with the same key.
   After that, in-app updates work normally.

## Signing and releases (GitHub Actions)

Create a new key on your computer (keep the file and passwords safe and private):

```bash
keytool -genkeypair -v -keystore officetracker-release.jks -alias officetracker \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 officetracker-release.jks > keystore.b64   # macOS: base64 -i officetracker-release.jks > keystore.b64
```

Then add these under GitHub repository > Settings > Secrets and variables > Actions:

| Secret | Value |
|---|---|
| `OT_KEYSTORE_BASE64` | contents of `keystore.b64` |
| `OT_KEYSTORE_PASSWORD` | keystore password |
| `OT_KEY_ALIAS` | `officetracker` |
| `OT_KEY_PASSWORD` | key password |

Delete `keystore/officetracker.jks` from the repository and from its git history (`git filter-repo --path keystore --invert-paths`).

**Publishing an update:** bump `versionCode` and `versionName` in `app/build.gradle.kts`, commit, then
`git tag v2.0.1 && git push --tags`. The workflow runs the tests, builds a signed APK, and publishes a GitHub Release
with the APK and its `.sha256` file. Phones pick it up within 6 hours, or at once from **Profile > Check for updates**.

To build locally, put the same four values in `~/.gradle/gradle.properties` (`OT_KEYSTORE_FILE=/path/to/file.jks`, and so on).
Without them, the build falls back to the debug key.

## How tracking works

```
GPS fix -> LocationFilter -> StayDetector -> Room (phone) -> WorkManager -> Firestore
            | drop > 50 m accuracy   | visit = 5 min within 80 m          (batched)
            | drop impossible jumps  | arrival back-dated to first fix
            | ignore jitter          | leave = 2 fixes outside (or 1 far)
```

* **Distance** counts only movement larger than the combined GPS uncertainty, and never while inside a visit.
* **Visit names**, in order of preference: an admin-defined **Place** (Places tab), then a label the employee gave
  that spot before, then the street address from the phone's geocoder.
* **Pause** stops GPS completely. Time and travel during a pause are not counted.
* **Live map**: each phone updates `live/{uid}` at most once a minute, plus a heartbeat every 3 minutes that carries
  battery and GPS state. An employee on duty who has not reported for 15 minutes shows as **No signal**.
* Fake-GPS (mock location) fixes are flagged and shown to the admin and in reports.
* Visit radius, minimum visit time, accuracy cut-off and allowance rate per km are set by the admin in **Profile > Organisation settings**.

## Project layout

```
app/src/main/java/com/officetracker/
  core/        models, timeline builder, date/phone/format helpers (pure Kotlin)
  data/        Room database, Firestore mappers, repositories, WorkManager upload
  tracking/    LocationFilter, StayDetector, TrackingProcessor, foreground service, boot receiver
  report/      CSV reports
  update/      GitHub Releases updater (checksum + signature check)
  ui/          Jetpack Compose screens: auth, today, history, day, profile, admin (team, employee, places)
app/src/test/  unit tests for the filter, stay detection, timeline and helpers
firestore.rules
```

## Known limits

* An admin cannot reset another person's password from the phone, because that needs the Firebase Admin SDK.
  Instead, delete the login in Firebase Console > Authentication, then add the employee again with a new password.
  Employees can change their own password in Profile.
* On some phones (Xiaomi, Oppo, Vivo, Realme), also allow **Autostart** for the app in the phone's settings,
  or the system may still stop background tracking.
* Map tiles come from OpenStreetMap's free servers and are cached on the phone. For a very large team,
  switch to a commercial tile provider in `OsmMap.kt`.
