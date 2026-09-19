# Office Tracker v2 (multi-company SaaS)

Android app for field and office staff, sold to many companies from one installation. Employees
start their workday, the phone records their route and visits, company admins manage their team,
and **you, the platform owner (super admin)**, manage companies, plans and subscriptions. Any change
you make reaches every affected phone within seconds.

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
