# Office Tracker v2 (multi-company SaaS)

Android app for field and office staff, sold to many companies from one installation. Employees
start their workday, the phone records their route and visits, company admins manage their team,
and **you, the platform owner (super admin)**, manage companies, plans and subscriptions. Any change
you make reaches every affected phone within seconds.

## New in 2.1

* **Tracking fixes.**
  * Distance is re-measured from the stored GPS route when a day ends, so it can no longer show 0 m for a real trip.
  * Start and end always appear on the map: if the phone had no fix at that moment, the first or last GPS point is used.
  * A day left open is closed at its last recorded point, not with a 6-second duration.
  * Opening History repairs days recorded wrongly by older versions.
* **Watchdog.** Every 15 minutes it checks that tracking is still running during an active day, restarts it if the phone
  (Xiaomi, Oppo, Vivo…) killed it, and alerts the employee after 30 minutes without GPS.
* **Interactive map.**
  * The route is coloured by speed (walk, city, fast), with dashed lines where the signal was lost.
  * Tap the line to see the time, speed and distance at that point.
  * Full-screen playback with a slider and 2x, 8x and 32x speeds.
  * Zoom and fit buttons, and Standard, Light and Dark map styles.
* **One device per account.** The super admin switches it per company (Features > "One device per account").
  Signing in on a new phone signs the old one out: its day is paused and its data uploaded first.
  Admins can also sign a person out remotely from their page.
* **Work schedule with automatic start and end.**
  * **Company schedule:** the admin sets it in Profile > Company work schedule (days, start and end times, late-after minutes).
  * **Personal override:** set from the employee's page, under ⋮ > Work schedule.
  * **Automatic start and end:** phones start and end the workday at those times using exact alarms, and pick up changes within seconds.
  * **Late marking:** late and not-started badges on the Team list, a late mark on each day, a History calendar
    (worked / late / missed), and "Late (min)" columns in reports.
  * **Requirements:** for automatic start, the employee must allow location "All the time". On Xiaomi phones,
    also enable Autostart for the app.
* **History.** A month calendar, start and end places on every row, and cloud and phone copies merged by whichever is newer.

**After updating:** publish the new `firestore.rules`. Phones need it to record their device.

## Roles

| Role | Created by | Can do |
|---|---|---|
| **Super admin** | First-run setup (only once, only one) | Create companies and their admins. Define plans (trial / monthly / quarterly / yearly / custom / lifetime). Change any company's plan, expiry, price, user and admin limits, and features. Record payments, suspend or re-activate, delete. Platform switches: maintenance mode, announcement banner, minimum app version, grace period, support contacts. Overview of revenue and renewals. |
| **Company admin** | Super admin (or another admin of the same company) | Add, edit, deactivate and delete employees within the plan's seat limit. Live team map, places, company settings (allowance rate, visit rules), reports, own subscription status. |
| **Employee** | Company admin | Start, pause and end the workday. Own history and reports. |

## How subscriptions work

* Each company has a **plan**, an **expiry date**, a **grace period** (platform setting, default 3 days),
  **limits** (max users, max admins) and **feature switches** (live map, places, reports, travel allowance, fake-GPS alerts).
* **Trial / Active**: everything the plan includes works.
* **Payment due (grace)**: still works; admins see a red banner.
* **Expired** or **Suspended**: every phone in that company shows a lock screen straight away. An active workday is
  paused and GPS stops. The data is kept; renewing unlocks the phones instantly.
* Renewing before expiry adds days after the current expiry, so the customer never loses paid days.
  Renewing after expiry starts from today.
* Limits and expiry are enforced by **Firestore security rules on the server**, not only in the app.
  An expired company cannot upload tracking data, and an admin cannot add more users than the plan allows.
* Payments are recorded manually by the super admin (bKash / Nagad / bank / cash). Recording a payment can extend the expiry
  in the same step. Automatic online payment (e.g. SSLCommerz or the bKash API) needs a server and is not included.

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
   Everyone logs in with their **mobile number**. The app maps it to an internal login id, so no SMS costs are involved.
2. **Publish the security rules.** Firestore Database > Rules: paste `firestore.rules`, then **Publish**.
3. **Create the super admin.** Open the app and tap **First time? Set up the platform**. This creates your super admin
   account, the platform settings and five starter plans (Free trial, Starter, Business monthly and yearly, Enterprise).
   The option disappears for good once it has been used.
4. In the app: **Platform** tab: set the support phone and email. **Plans** tab: set your prices.
   **Companies** tab: **New company**, which creates the company and its first admin login in one step.

If Firestore shows an error with an index link the first time you open a list, click the link to create the index.

## Upgrading from v1

1. Complete the setup steps above and create a company for your existing organisation.
2. Open that company in the **Companies** tab, then **⋮ > Import v1 employees**. Every old account gets a secure login
   in that company with its existing password, and the old plain-text record is deleted. Anyone whose password is
   shorter than 6 characters is listed so you can add them manually.
3. In Firestore, delete the old top-level `workdays` collection. v2 stores history under `users/{uid}/days`.
4. **Rotate the signing key.** The old `officetracker.jks` and its password were public in the repository,
   so anyone could sign an APK that your phones would accept as an update. See the next section.
   Phones must uninstall v1 and install v2 **once**, because Android only accepts updates signed with the same key.
   After that, in-app updates work normally.

## Signing, releases and in-app updates

**How updates reach phones.** Every push to `main` runs GitHub Actions, which:

1. Tests, then builds a release APK with version `2.1.<run number>`. The version code goes up with every build.
2. Signs it with **your** key (from GitHub secrets).
3. Publishes a GitHub Release with the APK and its SHA-256 checksum.

Installed apps check for a new release every time they are opened or brought back, at most every 30 minutes.
When one exists they show **Update available → Download → Install**. The download is checked against its checksum
and signing key, and Android installs it over the old app: same package, same data, and the user stays signed in.

**Signing: no setup needed.** Every build is signed with `keystore/officetracker.jks`, which is committed to the repository.
This is the same key v1 used. Because the key never changes, a new APK always installs over the old one and never shows
"App not installed as package conflicts with an existing package". Keep the repository **private**:
anyone with the key file can sign an APK that phones accept as an update.

**One-time exception.** Phones that installed a v2 test build made before this change were signed with a random,
throwaway key. They must uninstall once and install the latest release. Phones still on v1 update in place.

(Optional: to switch to a private key later, add the secrets `OT_KEYSTORE_BASE64`, `OT_KEYSTORE_PASSWORD`, `OT_KEY_ALIAS` and
`OT_KEY_PASSWORD`. Changing the key again needs one more reinstall on every phone.)

### If the repository is private

Phones can't read releases of a private repository without a login. Use a separate **public** repository only for releases:

1. Create an empty public repository, e.g. `Hasebul47/office-tracker-releases`, with a README so it has one commit.
2. Create a fine-grained personal access token with **Contents: Read and write** on that repository only.
3. In the private app repository, add:
   - the secret `OT_RELEASE_TOKEN` = the token;
   - the variable (Settings > Secrets and variables > Actions > **Variables**) `OT_RELEASE_REPO` = `Hasebul47/office-tracker-releases`.

Builds then publish there, and the app checks there automatically. If the repository is public, skip this: the app checks
the repository that built it.

### Forcing everyone onto a new version

Super admin > Platform > **Minimum app version code**: set it to the new build's code (100 + the run number, shown in the
Actions log and the release title). Older phones then show a blocking "Update required" screen until they update.

To build locally, put the same values in `~/.gradle/gradle.properties` (`OT_KEYSTORE_FILE=/path/to/file.jks`, `OT_KEYSTORE_PASSWORD=…`, and so on).
Without them, a local build uses the debug key.

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
* Visit radius, minimum visit time, accuracy cut-off and allowance rate per km are set per company by its admin
  in **Profile > Organisation settings**.

## Firestore layout

```
platform/config            maintenance, announcement, min version, grace days, support contacts
platform/setup             marks that the super admin exists (first-run lock)
plans/{planId}             sellable packages
companies/{cid}            plan, expiry, limits, features, settings, seat counters
companies/{cid}/places     known offices and sites
companies/{cid}/live       latest position of each employee
companies/{cid}/payments   payment log
users/{uid}                name, phone, role, companyId, disabled
users/{uid}/days/{date}    workday, /stays (visits), /tracks (GPS, 400 points per document)
```

## Project layout

```
app/src/main/java/com/officetracker/
  core/        models, timeline builder, date/phone/format helpers (pure Kotlin)
  data/        Room database, Firestore mappers, repositories, WorkManager upload
  tracking/    LocationFilter, StayDetector, TrackingProcessor, foreground service, boot receiver
  report/      CSV reports
  update/      GitHub Releases updater (checksum + signature check)
  ui/          Jetpack Compose screens: auth, today, history, day, profile, admin (team, employee, places),
               tenant (lock screen, subscription), superadmin (overview, companies, plans, platform)
app/src/test/  unit tests for the filter, stay detection, timeline and helpers
firestore.rules
```

## Known limits

* Online self sign-up for new companies and automatic card or mobile-wallet billing are not included:
  both need a small server (Cloud Functions). The data model is ready for them.
* Seat counters are kept by the app together with each add or remove. A technically skilled admin could lower the counter
  directly through the Firestore API; **Companies > ⋮ > Recount users** corrects it. A Cloud Function would close this fully.
* An admin cannot reset another person's password from the phone, because that needs the Firebase Admin SDK.
  Instead, delete the login in Firebase Console > Authentication, then add the employee again with a new password.
  Employees can change their own password in Profile.
* On some phones (Xiaomi, Oppo, Vivo, Realme), also allow **Autostart** for the app in the phone's settings,
  or the system may still stop background tracking.
* Map tiles come from OpenStreetMap's free servers and are cached on the phone. For a very large team,
  switch to a commercial tile provider in `OsmMap.kt`.
