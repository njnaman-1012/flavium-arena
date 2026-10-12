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
| Sharing between devices | `SyncInstrumentedTest` (two databases, stand-in server) |

## Sharing data between devices

**Status: the logic is built and tested; no server is connected.** Until a Firebase
project is configured, every installation still has its own data.

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
- `RemoteStore` is the interface a server has to offer. The tests use an in-memory
  stand-in; the online app needs a Cloud Firestore implementation of it.

### What is shared and what is not

| Local table | Shared | Notes |
|---|---|---|
| `branches`, `sports`, `categories` | yes | public to read |
| `registrations` | yes | phone numbers and pending or rejected entries are private |
| `matches`, `match_periods`, `match_events` | yes | public to read |
| `event_rounds`, `event_entries`, `event_attempts` | yes | public to read |
| `score_audit`, `event_audit` | yes | organisers only |
| `settings`, `admin_audit` | yes | settings public; the log is for Faculty |
| `coach_sports` | yes | organisers only |
| `users` | profile only | name, email, role, active, branch. Never the password hash, salt or lockout counters |
| `sessions` | no | a login session is decided by the server |
| `sport_templates` | no | built into every installation |
| standings, medals | not stored | each device works them out from the shared results |

A row keeps its id on every device and on the server (the UUID it was created with), so
the links between branches, registrations, fixtures and results are preserved.

### Connecting devices for the first time

Two installations that were used separately each have their own festival, with their
own ids. They are not merged row by row, because that would create duplicates.

1. **One device uploads.** An administrator confirms on the device that holds the real
   data, and `SyncEngine.uploadAsFestival()` sends everything. The server is reserved
   for that device first, so a second device cannot upload as well. If the upload is
   interrupted it continues where it stopped and sends nothing twice.
2. **Every other device joins.** After the person confirms, `LocalBackup.copy()` keeps a
   copy of the local database file in the app's `backups` folder, and
   `SyncEngine.joinFestival()` replaces the shared tables with the server's data.
   Joining is refused until the first upload is complete.

Local data on a joining device that was never uploaded is replaced, not merged. It
remains in the backup copy.

### What still has to be built

1. A Firebase project and `app/google-services.json` (see below).
2. `FirestoreRemoteStore`, the Firestore implementation of `RemoteStore`, with live
   listeners that call `SyncEngine.applyRemote()`.
3. Sign-in through Firebase Authentication, and accounts linked to it.
4. Firestore Security Rules that enforce the permission table above on the server.
5. A screen for the two steps above, a sync status line, and screens that reload when
   `SyncEngine.Listener` reports a change.
6. One session per account across devices.

### Firebase setup (to be done by the project owner)

1. In the Firebase Console create a project.
2. Add an Android app with the package name `com.example.flaviumarena`.
3. Download `google-services.json` into `app/`. The file is ignored by git.
4. Authentication: enable the Email/Password provider.
5. Firestore: create the database in production mode.

### Billing

Everything above works on the free Spark plan except trusted server code. Two
requirements need it: session records that a modified app cannot write itself, and
Faculty creating sign-in accounts for other people. On Firebase that means Cloud
Functions, which require the pay-as-you-go Blaze plan. Without it, sessions can be
enforced with Firestore transactions and rules, which stops honest clients but is not
tamper-proof. Nothing that needs billing has been enabled or deployed.

## Not built yet

- **The connection to a server.** See "Sharing data between devices" above: the
  logic is tested with two databases and a stand-in server, but nothing is online.
- Automatic creation of the next knockout round. The knockout view only shows who
  went through.
- Net run rate in cricket tables.
- Rule defaults in the sport templates have not been checked against official rulebooks.
