# FLAVIUM Arena

An Android app for running a college sports festival: sports and categories, branch
registrations, fixtures, live scoring, standings, medals and downloadable reports.

Written in Java with XML layouts. Data is stored in SQLite on the device.
**The app is not online yet:** nothing is hosted or synchronised between phones.
Firebase is planned as the next stage (see [Not built yet](#not-built-yet)).

## What it does

- **Public browsing without an account.** Anyone can open the app and follow sports,
  fixtures, live scores, standings and the medal table.
- **Five organiser roles**, each with its own login card (see [Permissions](#permissions)).
- **Fourteen sport templates** plus a custom one. A category is created from a template
  and stores its own rules, so a new sport needs data, not new screens.
- **Six scoring engines**: one score each (football, basketball), sets with deuce and
  win-by-two (badminton, volleyball, table tennis, squash, lawn tennis), cricket innings,
  win/draw/loss (chess), timed races with heats and finals (athletics, swimming), and
  measured throws with attempts and fouls (discus, shot put).
- **Standings** worked out from official results: a league table, the rounds of a
  knockout, or the placings of a final.
- **Medal table** by branch: gold, silver, bronze and total. There are no medal points.
- **Downloads**: CSV and PDF reports that contain only what the person may see.
- **Cricket**: a fixture can carry a CricHeroes link that opens in the browser. Scores
  are not copied from CricHeroes.

## Project structure

```
app/src/main/java/com/example/flaviumarena/
  model/      Plain data classes: User, Category, Match, Registration, MedalRow ...
  auth/       AccessControl (every permission rule) and Session (who is logged in here)
  scoring/    The rules of the games, in pure Java: sets, tennis games, race and throw
              ranking, standings, medals, and the wording of a match or event's state
              (MatchSummary, EventSummary). No Android, no database.
  data/       Everything that touches the database (see below)
  export/     CSV and PDF downloads
  sync/       Sharing data between devices: change capture, the engine that sends and
              applies changes, and the interface a server has to offer. No server is
              connected yet (see Not built yet).
  util/       Passwords, dates, team logos
  ui/
    common/         BaseActivity, BaseFragment, RowAdapter, FormDialog, Ui helpers
    main/           Home, Discover, Matches and More tabs, onboarding
    auth/           Login and first-Faculty set-up
    admin/          Accounts, settings, reports, permission self-test
    sports/         Sports, the template catalogue, categories and their rule form
    registrations/  Teams and participants
    branches/       Branch list and the branch page
    matches/        Fixtures and the match screen
    events/         Heats and finals of races and throws
    standings/      Standings and the medal table

app/src/debug/     Development accounts, sample data and the permission self-test.
                   Compiled into debug builds only.
app/src/release/   Empty twins of the debug classes, so a release build contains none of it.
app/src/test/      Unit tests (run on the computer)
app/src/androidTest/  Tests that need Android and a real SQLite database
```

### How the layers depend on each other

```
ui  -->  ArenaRepository (interface)  -->  SqliteArenaRepository  -->  SQLite
 |              ^                              |
 |              |                              +-- AccountStore, CategoryStore, RegistrationStore,
 |              |                              |   MatchStore, EventStore, SessionStore
 +--> AccessControl, scoring, export           +-- DbHelper (schema and migrations)
```

- A screen never opens the database. It calls the `ArenaRepository` interface that
  `RepositoryProvider` hands out. That one class is the place to switch the app to an
  online database later.
- `scoring/` and `AccessControl` are pure Java with no Android imports, which is why
  they can be unit tested on the computer.
- Screens use `AccessControl` only to decide which buttons to show. The repository
  checks every permission again before it changes anything.
- A screen runs a change through `BaseActivity.attempt()`, which shows a refusal or a
  database error as a message. Work that can take a moment goes through
  `BaseActivity.runBusy()`: it runs in the background, shows a "please wait" message
  and reports errors the same way. Downloads use it today; online calls will too.

### The data layer

| Class | Responsibility |
|---|---|
| `ArenaRepository` | The interface every screen uses |
| `SqliteArenaRepository` | The single entry point. Keeps branches, settings, sports, standings and medals, and hands everything else on to the stores below |
| `AccountStore` | Logging in, passwords and lockout, accounts, a Coach's sports |
| `CategoryStore` | Categories and their rules |
| `RegistrationStore` | Registrations: who may see what, category limits, approval |
| `MatchStore` | Fixtures, live scoring, finalising, score history |
| `EventStore` | Heats and finals, entrants, times, attempts, positions |
| `SessionStore` | Opening, checking and ending login sessions |
| `DbHelper` | Table definitions and every migration |
| `Sql` | Reading columns and rows |
| `TemplateStore`, `TemplateRules`, `CategoryRules` | The sport catalogue and category rules |

Every method that changes data follows the same four steps: re-read the acting user
from the database, check the permission, validate the input, write. Failures are
reported with three exceptions: `PermissionDeniedException`, `ValidationException` and
`ConflictException` (someone else changed the record first).

## Database

SQLite, file `flavium_arena.db`, schema version 8. Ids are UUID text. Upgrades run in a
transaction: if a step fails, the database stays on its old version with its data
untouched and the app shows a message instead of crashing.

| Version | What it added |
|---|---|
| 1 | Users, sports, categories, registrations, matches, score history |
| 2 | Four roles (the Viewer role was removed), branches, settings, administrative log |
| 3 | Registrations belong to a branch and year; login attempt counter and lockout |
| 4 | Sport template catalogue; categories store their rules and scoring engine |
| 5 | Sets and innings, match events, the PAUSED status, CricHeroes link |
| 6 | Heats, finals, entrants, attempts and their history |
| 7 | Login sessions, the Coach role and their sports, one active Branch CR per branch |
| 8 | Bookkeeping for sharing data between devices; idle until a server is connected |

Standings and medals are never stored. They are worked out from the official results
each time, so they cannot disagree with them.

## Authentication and sessions

- Passwords are stored as PBKDF2 hashes (120,000 iterations) with a salt per account.
  Five wrong passwords lock an account for five minutes.
- The login screen asks for a role, but the role always comes from the stored account.
  Choosing a role the account does not have is refused.
- **One live session per account.** Logging in creates a session row and the phone
  keeps only a random session id. A second login is refused unless the person chooses
  to sign the other session out. A unique index in the database also refuses a second
  live session, so two logins at the same moment cannot both succeed.
- Every protected call checks that the session is still live. A session that was signed
  out, taken over, or unused for seven days is treated as the public.
- **Limit of the local version:** each phone has its own database, so this only holds
  inside one device. Cross-device enforcement needs the online backend.

## Permissions

| | Faculty | Core | Coach | Asoo Core | Branch CR | Public |
|---|---|---|---|---|---|---|
| Accounts | all roles | Asoo Core; Branch CR if allowed | no | no | no | no |
| Settings, admin log | yes | log: no | no | no | no | no |
| Branches (add, rename, deactivate) | yes | no | no | no | no | view |
| Sports | yes | yes | no | no | no | view |
| Categories | yes | yes | own sports | no | no | view |
| Registrations | yes | yes | own sports | view | own branch | approved only |
| Fixtures, heats, finals | yes | yes | own sports | no | no | view |
| Scoring | yes | yes | own sports | assigned only | no | no |
| Make results official | yes | if allowed | own sports | no | no | no |
| Phone numbers, pending entries | yes | yes | own sports | yes | own branch | no |

- A **Coach** is assigned to one or more sports by Faculty and administers only those.
- A **Branch CR** manages the registrations of one branch. A branch has one active
  Branch CR. (In the code and the database this role is still called `CAPTAIN`.)
- "If allowed" refers to two switches on the Faculty settings screen.

All of these rules are in `auth/AccessControl.java`.

## Configuration

| Setting | Where | Notes |
|---|---|---|
| First Faculty email | `app/src/main/res/values/config.xml` | Empty by default. Only this address can create the first Faculty account |
| Development values | `app/src/debug/res/values/config.xml` | Used by debug builds only |
| Development accounts | `app/src/debug/java/.../data/DevData.java` | Not compiled into a release build |
| Firebase | `app/google-services.json` | Not in the repository; ignored by git |
| Signing keys | `*.jks`, `*.keystore` | Ignored by git |

There are no real credentials in the repository. The development accounts exist only in
debug builds and use the reserved `.test` domain. If you make the repository public,
change those passwords first: anyone who can read the repository can read them.

## Setup

1. Install Android Studio and open this folder.
2. Let Gradle sync. The project needs JDK 17 or newer (Android Studio's bundled JDK
   works), Android SDK 37 and a device or emulator with Android 7.0 (API 24) or newer.
3. Press Run. A debug build starts with sample sports, fixtures and development
   accounts. On each login form, "Fill in the development account" enters one for you.
4. For a real installation, write the Faculty email into
   `app/src/main/res/values/config.xml`, build a release, and use "Set up the first
   Faculty account" on the login screen. Faculty then creates every other account.

If a development account is missing from a database that was created by an older
version of the app, use "Create or repair this development account" on the login form.

## Testing

Unit tests, on the computer:

```bash
./gradlew testDebugUnitTest
```

Tests on a device or emulator. These commands keep the data that is already on the
device (`connectedDebugAndroidTest` would uninstall the app and erase it):

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.example.flaviumarena.test/androidx.test.runner.AndroidJUnitRunner
```

The device tests use temporary in-memory databases and never touch the app's own data.
Debug builds also have **More > Permission self-test**, which runs the role rules
against a temporary database and lists each check.

| Area | Tests |
|---|---|
| Role rules | `AccessControlTest`, `PermissionRulesInstrumentedTest`, `CoachInstrumentedTest` |
| Login and sessions | `LoginInstrumentedTest`, `SessionInstrumentedTest`, `PasswordUtilTest` |
| Registrations | `BranchRulesTest`, `RegistrationRulesInstrumentedTest` |
| Scoring | `SetScoringTest`, `EventRankingTest`, `MatchSummaryTest`, `EventSummaryTest`, `ScoringInstrumentedTest`, `RoundsInstrumentedTest` |
| Standings and medals | `StandingsCalculatorTest`, `MedalsTest`, `MedalsInstrumentedTest` |
| Sport catalogue | `RulesTest`, `SportCatalogueInstrumentedTest`, `LiveLinkTest` |
| Downloads | `CsvTest`, `ExportInstrumentedTest` |
| Database upgrades | `DatabaseUpgradeInstrumentedTest` |
| Sharing between devices | `SyncInstrumentedTest` (two databases, stand-in server), `PublicViewTest`, `FirestoreEmulatorInstrumentedTest` (Firebase Local Emulator Suite) |

## Sharing data between devices

**Status: built, and tested against the Firebase Local Emulator Suite. Not yet tested
against the real Firebase project or between two real devices.** A device shares
nothing until someone connects it from **More > Online sharing**.

### How it works

- SQLite stays the store every screen reads and writes, so the app works offline.
- Triggers on the shared tables write one line into `sync_outbox` for every row that is
  added, changed or removed. They are idle until a device is connected.
- `SyncEngine` sends the queued rows to the server and writes server changes locally.
- Every row on the server has a revision. A change quotes the revision the device last
  saw; if the server's copy has moved on, the change is refused, the device takes the
  server's copy, and the person is told. An old copy can never overwrite newer data.
- When a change to a match or event is refused, the history lines, sets, events and
  attempts written with it are dropped too, so the history always matches the score.
- `RemoteStore` is the interface a server has to offer. `cloud/FirestoreRemoteStore`
  is the Cloud Firestore implementation; the tests also use an in-memory stand-in.
- `cloud/Cloud` owns the connection: it synchronises when the app comes to the front
  and after every local save, and listens for server changes while the app is open.
  Every screen reloads itself through `refreshScreen()` when data arrives.

### Data on the server

Everything is under one document, `festivals/main`:

| Path | Content | Who may read |
|---|---|---|
| `festivals/main` | owner, `ready`, revision counter, the row it last moved for | everyone |
| `pub/{table:id}` | the public copy of a row | everyone, without signing in |
| `priv/{table:id}` | the complete row where the public copy is reduced or absent | organisers, by role |
| `links/{uid}` | which account a Firebase sign-in belongs to | that person, Faculty |
| `sessions/{uid}` | the device an account is signed in on | that person |

### What is shared and what is not

| Local table | Shared | Notes |
|---|---|---|
| `branches`, `sports`, `categories` | yes | public to read |
| `registrations` | yes | public only when approved, and without the phone number |
| `matches`, `match_periods`, `match_events` | yes | public to read |
| `event_rounds`, `event_entries`, `event_attempts` | yes | public to read |
| `score_audit`, `event_audit` | yes | organisers only |
| `settings`, `admin_audit` | yes | settings public; the log is for Faculty |
| `coach_sports` | yes | organisers only |
| `users` | profile only | publicly name and role; the email is private. Never the password hash, salt or lockout counters |
| `sessions` | no | a login session is decided on the server |
| `sport_templates` | no | built into every installation |
| standings, medals | not stored | each device works them out from the shared results |

A row keeps its id on every device and on the server (the UUID it was created with), so
the links between branches, registrations, fixtures and results are preserved.

### Firebase setup

1. Firebase Console: a project, with an Android app whose package name is
   `com.example.flaviumarena`.
2. Download `google-services.json` into `app/`. The file is ignored by git. Without it
   the app still builds and simply has no online sharing.
3. Authentication: enable the Email/Password provider and leave sign-up allowed (the
   app creates the sign-in accounts).
4. Firestore: create the database in production mode.
5. Publish the rules and indexes in this repository. Nothing here costs money:

```bash
npm install -g firebase-tools
firebase login
firebase deploy --only firestore:rules,firestore:indexes --project <your-project-id>
```

Or paste `firestore.rules` into Firestore > Rules in the console, and create the two
indexes of `firestore.indexes.json` under Firestore > Indexes.

Until the rules are published, a production-mode database refuses everything and the
app reports that the server refused the request.

### Connecting devices for the first time

Two installations that were used separately each have their own festival, with their
own ids. They are not merged row by row, because that would create duplicates.

1. **One device uploads** (More > Online sharing > Upload this device's festival).
   Only a Faculty account can, and only while the server is empty. Faculty chooses the
   password of their online account at this point. The server is reserved for that
   device first, so a second device cannot upload as well. If the upload is interrupted
   it continues where it stopped and sends nothing twice.
2. **Faculty creates the online logins** of the other organisers in Accounts and roles
   ("Create the online login"). New accounts get one automatically while connected.
3. **Every other device joins** (Join the shared festival). After the person confirms,
   a copy of the local database file is kept in the app's `backups` folder and the
   shared tables are replaced with the server's data. Joining is refused until the
   first upload is complete. Local data that was never uploaded is replaced, not
   merged; it remains in the backup copy.
4. Organisers then log in with their email and password. On a connected device the
   login is checked by Firebase, not against a local password.

### Sign-in and sessions

- The role comes from the account row on the server, never from the role chosen on the
  login screen or from anything the device claims.
- An account is signed in on one device. Logging in elsewhere offers "Sign out the
  other device"; the other device is logged out when it next hears from the server.
- A forgotten password is reset by email from Accounts and roles.

### Testing with the Firebase Local Emulator Suite

`FirestoreEmulatorInstrumentedTest` exercises the adapter and the security rules
(allowed and refused access for every role). It is skipped when the emulators are not
running. Java and Node.js are needed on the computer.

```bash
firebase emulators:start --only firestore,auth --project demo-flavium
adb reverse tcp:8080 tcp:8080
adb reverse tcp:9099 tcp:9099
```

Then run the device tests as described under Testing. To point a debug build of the
app itself at the emulators, set `firebase_emulator_host` to `127.0.0.1` in
`app/src/debug/res/values/config.xml`. Release builds ignore the setting.

### Billing and limits

Everything runs on the free Spark plan. No Cloud Functions are used and nothing that
needs billing has been enabled or deployed. That has consequences:

- The one-device session is a document the app writes itself, checked by the rules.
  It stops the normal app; it is not tamper-proof against a modified app, which could
  write its own device number or send another device's. Such an app still needs the
  account's password and can do nothing the account's role may not do.
- Online logins are created from the Faculty device through Firebase sign-up, so
  sign-up has to stay enabled. The rules give a sign-in no access until Faculty has
  linked it to an account.
- Faculty cannot set someone else's password; resets go by email.
- A Coach reads and changes the private rows of the assigned sports only. An Asoo Core
  can read every registration and score history (the app needs them to enter results),
  but can write only the scores of what is assigned to them.
- Everyone can read the name, role, branch and active flag of every organiser account
  (fixtures show who scores them), and the team captain's name and the members of an
  approved team. Emails, phone numbers and waiting or rejected registrations are not
  public; how many registrations are hidden can be counted, their content cannot.
- A Core can remove a fixture whose result is official, as in the app, even when
  correcting official results is switched off for Cores.
- A row is attributed to whoever sent it. If someone works without a connection and
  another account then uses the same device, the first account's unsent history lines
  are refused. Logging out sends what is waiting first, when there is a connection.
- A device that was signed in as an organiser keeps the private rows it received after
  logging out. The app hides them, but they are in its database file.
- The first device to upload claims the empty server. Do the upload yourself before
  handing the app to anyone.
- Synchronisation runs while the app is open. Nothing is sent in the background.
- When a change arrives the open screen reloads, which can clear a form being typed.

## Not built yet

- **A test between real devices on the real Firebase project.** The sharing code has
  only been run against the local emulators.
- Automatic creation of the next knockout round. The knockout view only shows who
  went through.
- Net run rate in cricket tables.
- Rule defaults in the sport templates have not been checked against official rulebooks.
