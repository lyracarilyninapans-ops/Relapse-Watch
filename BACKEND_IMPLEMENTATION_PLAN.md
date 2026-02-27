# Relapse Wear OS App — Backend Implementation Plan

> **Current State:** The Wear OS app is a UI-only prototype with 5 Activities and 6 Composable screens. The build system declares Hilt, Room, Firebase, DataStore, Play Services Location, and Wearable APIs — but none are wired up. All state is hardcoded `mutableStateOf` in Activities. There are no ViewModels, repositories, services, database entities, or phone-watch communication.

> **Priority Focus:** Activity data collection and upload is the **first feature to be fully functional**. The watch must collect GPS location, track events, compute daily summaries, and upload to Firestore so the Flutter phone app's Activity Screen can display real data. All phases are ordered to deliver this pipeline first.

---

## Cross-App Conflict Resolution (READ FIRST)

> **This section documents all discovered conflicts between the Watch, Flutter phone app, and Cloud Functions implementations. Every schema, naming convention, and data flow in this plan has been updated to match the existing Flutter app code and deployed Cloud Functions. Treat this section as the authoritative contract.**

### Conflict 1: Event Type Naming Convention — RESOLVED

The Flutter app's `ActivityEventType` enum serializes via Dart `.name` as **camelCase** (`locationUpdate`, `safeZoneExit`, etc.), but both this watch plan and Cloud Functions originally used **snake_case** (`location_update`, `safe_zone_exit`, etc.).

**Resolution:** The **Flutter app model must be updated** to use snake_case strings matching the watch and Cloud Functions. Since Cloud Functions are already deployed and use snake_case, and the watch naturally writes snake_case, the Flutter `ActivityRecord.fromJson` must map snake_case event type strings. The phone-side fix is tracked in the Flutter backend plan.

**Watch writes these exact strings:**
- `"location_update"` — not `"locationUpdate"`
- `"safe_zone_exit"` — not `"safeZoneExit"`
- `"safe_zone_enter"` — not `"safeZoneEnter"`
- `"reminder_triggered"` — not `"reminderTriggered"`
- `"watch_disconnected"` — not `"watchDisconnected"`
- `"watch_reconnected"` — not `"watchReconnected"`

### Conflict 2: `patientId` Field in ActivityRecord Documents — RESOLVED

The Flutter model requires `patientId` as a **field inside the document**, not just in the Firestore path. Cloud Functions also validate `patientId` presence and quarantine records without it.

**Resolution:** The watch **must include `patientId` as a field** in every `ActivityRecord` document written to Firestore. The updated schema in Section 2.2 reflects this.

### Conflict 3: DailySummary `date` Field Type — RESOLVED

The Flutter model parses `date` as a Firestore `Timestamp` via `(json['date'] as Timestamp).toDate()`. The watch plan and Cloud Functions write `date` as a plain string (`"2026-02-25"`).

**Resolution:** The **Flutter model must be updated** to parse `date` as a string. The watch writes `date` as a plain `String` (the date key). The phone-side fix is tracked in the Flutter backend plan. Additionally, the Flutter remote source must inject `patientId` from the Firestore path context since neither the watch nor Cloud Functions write it as a document field.

### Conflict 4: DailySummary Dual-Writer — RESOLVED

Both the watch (SyncService) and Cloud Functions (`onActivityRecordForSummary`) previously wrote to `dailySummaries/{date}`, creating race conditions.

**Resolution:** **Cloud Functions are the sole authoritative writer** of `dailySummaries`. The watch writes only `activityRecords`. Cloud Functions maintain summaries incrementally on each new record, with a 15-minute rollup scheduler for drift correction. The watch's local `DailySummary` in Room is for on-device display only and is never uploaded to Firestore.

### Conflict 5: Timestamp Format — RESOLVED

The watch plan used `Long` (epoch milliseconds) for timestamps. Flutter and Cloud Functions expect Firestore `Timestamp` objects.

**Resolution:** The watch **must use `com.google.firebase.Timestamp.now()`** or `FieldValue.serverTimestamp()` when writing to Firestore. The Room database can continue using `Long` epoch millis internally; conversion happens at the Firestore upload boundary in `FirestoreActivitySource`.

### Conflict 6: SafeZoneEvent `eventType` Casing — RESOLVED

Kotlin enum `.name` produces uppercase (`"EXIT"`, `"ENTER"`). Flutter and Cloud Functions expect lowercase (`"exit"`, `"enter"`).

**Resolution:** The watch serializes safe zone event types as **lowercase strings**: `"exit"` and `"enter"`. Use `.name.lowercase()` or a custom serializer in Kotlin.

### Conflict 7: Pairing Code Format — RESOLVED

Flutter `WatchCommunicationService` generates numeric-only 6-digit codes. The watch `PairingScreen` placeholder shows alphanumeric `"A1B2C3"`.

**Resolution:** Both sides use **6-digit numeric codes** to match the Flutter implementation. The watch's `PairingScreen` already accepts any string — it will display whatever code is generated. The watch's code generation (if it generates codes) must also produce numeric-only codes.

### Conflict 8: Phase Ordering — Pairing Before Upload — RESOLVED

The original plan had Pairing (Phase 6) after Firebase Upload (Phase 4). But `SyncService` requires `caregiverUid` and `patientId` from pairing to construct Firestore paths.

**Resolution:** Phase ordering revised. Pairing is promoted to **Phase 4** (moved before Firebase upload). The new order: Foundation → Activity Data Layer → Location Service → Pairing → Firebase Upload → Foreground Service. This ensures `caregiverUid` and `patientId` are available before any Firestore writes.

### Flutter-Side Fixes Required (Tracked Separately)

The following changes must be made to the Flutter phone app to complete alignment:

1. **`lib/models/activity_record.dart`** — Update `ActivityEventType` enum to serialize as snake_case strings. Add a `fromFirestore` helper or use string mapping instead of `.name`.
2. **`lib/models/daily_summary.dart`** — Change `date` field from `DateTime` (parsed from `Timestamp`) to `String`. Remove required `patientId` from constructor or inject it from path context in the remote source.
3. **`lib/data/remote/activity_remote_source.dart`** — Update `eventType` filter in `watchLatestLocation()` to use `"location_update"` instead of `ActivityEventType.locationUpdate.name`.
4. **`lib/data/remote/daily_summary_remote_source.dart`** — Inject `patientId` into the `DailySummary` from the Firestore path when constructing from JSON.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Activity Data — Collection & Upload Requirements](#2-activity-data--collection--upload-requirements)
3. [Phase 1 — Foundation (Hilt DI, Application Class)](#3-phase-1--foundation)
4. [Phase 2 — Activity Data Layer (Room + DataStore)](#4-phase-2--activity-data-layer)
5. [Phase 3 — Location Service & Activity Tracking](#5-phase-3--location-service--activity-tracking)
6. [Phase 4 — Pairing Flow](#6-phase-4--pairing-flow)
7. [Phase 5 — Firebase Integration & Activity Upload](#7-phase-5--firebase-integration--activity-upload)
8. [Phase 6 — Activity Foreground Service](#8-phase-6--activity-foreground-service)
9. [Phase 7 — Full Data Layer (Remaining Room Entities)](#9-phase-7--full-data-layer)
10. [Phase 8 — Phone Communication (Wearable Data Layer)](#10-phase-8--phone-communication)
11. [Phase 9 — Safe Zone Geofencing](#11-phase-9--safe-zone-geofencing)
12. [Phase 10 — Geo-Triggered Memory Reminders](#12-phase-10--geo-triggered-memory-reminders)
13. [Phase 11 — Navigation (Guide Patient Home)](#13-phase-11--navigation)
14. [Phase 12 — Notifications & Haptics](#14-phase-12--notifications--haptics)
15. [Phase 13 — Offline Resilience & Sync](#15-phase-13--offline-resilience--sync)
16. [Phase 14 — Testing & Hardening](#16-phase-14--testing--hardening)
17. [Dependency Summary](#17-dependency-summary)
18. [Directory Structure](#18-directory-structure)

---

## 1. Architecture Overview

### Chosen Architecture: **MVVM + Clean Architecture + Hilt**

```
┌─────────────────────────────────────────────────┐
│  UI Layer (Activities + Composable Screens)      │
│  ↕  ViewModels (Hilt-injected, StateFlow)        │
├─────────────────────────────────────────────────┤
│  Domain Layer                                    │
│  • Entities (Kotlin data classes)                │
│  • Use Cases (complex business logic)            │
│  • Repository Interfaces                         │
├─────────────────────────────────────────────────┤
│  Data Layer                                      │
│  • Repository Implementations                    │
│  • Local: Room DB + DataStore                    │
│  • Remote: Firebase Firestore                    │
│  • Services: Location, Geofence, Wearable Comms  │
│  • Foreground Service: Background monitoring      │
└─────────────────────────────────────────────────┘
```

### Key Patterns

- **Hilt** for all dependency injection (already declared, needs wiring)
- **Room** for structured local data (already declared, needs entities/DAOs)
- **DataStore** for preferences/pairing state (already declared, needs usage)
- **Kotlin Coroutines + Flow** for all async operations
- **StateFlow** in ViewModels for UI state
- **Foreground Service** for persistent location monitoring

---

## 2. Activity Data — Collection & Upload Requirements

The watch is the **source of truth** for all activity data shown on the phone’s Activity Screen. This section defines exactly what the watch must collect and upload.

### 2.1 What the Phone’s Activity Screen Needs from the Watch

| Phone UI Element | Watch Must Provide | Upload Target |
|---|---|---|
| **Current location card** (map + LIVE badge) | Latest GPS coordinates + timestamp | `activityRecords/{id}` with `eventType: location_update` |
| **Safe Zone status pill** | Whether current location is inside/outside safe zone | Derived from GPS + safe zone config |
| **Daily summary — Distance** | Cumulative distance traveled today (meters) | `dailySummaries/{date}.distanceMeters` |
| **Daily summary — Time Outside** | Minutes spent outside safe zone | `dailySummaries/{date}.activeMinutes` |
| **Daily summary — Places** | Count of distinct significant locations | `dailySummaries/{date}.placesVisited` |
| **Movement pattern chart** (24 bars) | Activity records with timestamps (phone groups by hour) | `activityRecords` with timestamps |
| **Recent activity feed** | Typed events: `location_update`, `safe_zone_exit`, `safe_zone_enter`, `reminder_triggered` | `activityRecords/{id}` with `eventType` |
| **Location history timeline** | Ordered location records with lat/lng/timestamp | `activityRecords` filtered by `location_update` |
| **Home Screen — Activity stat** | Total event count for today | `dailySummaries/{date}.totalEvents` |

### 2.2 ActivityRecord Schema (Watch Writes to Firestore)

> **IMPORTANT:** This schema includes all fixes from the Cross-App Conflict Resolution section. The watch must include `patientId` as a document field, use Firestore `Timestamp` (not epoch millis), and write event types as **snake_case** strings.

```kotlin
// Firestore: users/{uid}/patients/{patientId}/activityRecords/{id}
//
// When uploading to Firestore, convert from the Room entity:
//   - timestamp: convert Long epoch → com.google.firebase.Timestamp
//   - eventType: use snake_case string constants (see EVENT_TYPES below)
//   - patientId: MUST be included as a document field (Flutter + Cloud Functions require it)
//   - metadata: Map<String, Any> (not Map<String, String>) to match Flutter's dynamic map

data class ActivityRecord(
    val id: String,
    val patientId: String,             // REQUIRED — must match path segment
    val timestamp: Long,               // Room stores epoch millis; convert to Firestore Timestamp on upload
    val latitude: Double,
    val longitude: Double,
    val eventType: String,             // SNAKE_CASE: "location_update" | "safe_zone_exit" | "safe_zone_enter" | "reminder_triggered" | "watch_disconnected" | "watch_reconnected"
    val metadata: Map<String, Any>?    // optional: accuracy, speed, reminderId, etc.
)

// Event type constants — use these everywhere, never hardcode strings
object EventTypes {
    const val LOCATION_UPDATE = "location_update"
    const val SAFE_ZONE_EXIT = "safe_zone_exit"
    const val SAFE_ZONE_ENTER = "safe_zone_enter"
    const val REMINDER_TRIGGERED = "reminder_triggered"
    const val WATCH_DISCONNECTED = "watch_disconnected"
    const val WATCH_RECONNECTED = "watch_reconnected"
}
```

**Firestore upload conversion in `FirestoreActivitySource`:**
```kotlin
fun ActivityRecord.toFirestoreMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "patientId" to patientId,              // REQUIRED field
    "timestamp" to Timestamp(timestamp / 1000, ((timestamp % 1000) * 1_000_000).toInt()),
    "latitude" to latitude,
    "longitude" to longitude,
    "eventType" to eventType,              // already snake_case
    "metadata" to metadata,
)
```

### 2.3 DailySummary Schema (Cloud Functions Owns — Watch Does NOT Upload)

> **CHANGED:** The watch no longer uploads `DailySummary` to Firestore. Cloud Functions are the sole writer of `dailySummaries/{date}` documents (via `onActivityRecordForSummary` trigger + 15-minute rollup scheduler). The watch maintains a local Room `DailySummary` for its own on-device display only.

```kotlin
// Firestore: users/{uid}/patients/{patientId}/dailySummaries/{yyyy-MM-dd}
// Written ONLY by Cloud Functions — watch reads this via Firestore listener (optional)
// Watch's local Room DailySummary is for on-device display only
data class DailySummary(
    val date: String,                  // "2026-02-25" (plain string, NOT a Timestamp)
    val distanceMeters: Double,        // cumulative GPS distance
    val activeMinutes: Int,            // minutes outside safe zone
    val placesVisited: Int,            // distinct location clusters
    val safeZoneExits: Int,            // count of safe_zone_exit events
    val remindersTriggered: Int,       // count of reminder_triggered events
    val totalEvents: Int,              // total activity records for the day
    val stepCount: Int                 // from Health Services (if available)
)
```

### 2.4 Collection Frequency

| Data Type | Frequency | Trigger |
|---|---|---|
| GPS location update | Every 30 seconds (outside safe zone) / 5 min (inside) | Foreground Service timer |
| Safe zone event | Immediate | Geofence broadcast |
| Reminder triggered | Immediate | Geofence broadcast |
| Daily summary update | Every 5 minutes + on significant events | Periodic + event-driven |

### 2.5 Upload Strategy

> **CHANGED:** Watch uploads only `activityRecords`. Cloud Functions handle `dailySummaries`.

```
Watch collects ActivityRecords → Room DB
        │
        ├── Every 5 minutes:
        │   └── Batch upload pending activityRecords to Firestore
        │       └── Cloud Functions trigger: update dailySummary automatically
        │
        ├── On significant event (safe zone exit/enter):
        │   └── Immediate upload (single record)
        │       └── Cloud Functions trigger: update dailySummary + send push alert
        │
        └── Phone's Firestore listener receives data in real-time
            └── Activity Screen updates automatically
```

### 2.6 End-to-End Pipeline

```
Watch GPS Sensor
   │
   ▼
LocationService.getLocationUpdates() (Phase 3)
   │
   ▼
ActivityTrackingService.recordLocationUpdate() (Phase 3)
   │  ← includes patientId in every record
   ▼
Room DB: activity_records table (Phase 2)
   │
   ▼
SyncService.uploadPendingRecords() (Phase 5)
   │  ← converts epoch ms → Firestore Timestamp
   │  ← uses caregiverUid + patientId from Pairing (Phase 4)
   ▼
Firestore: activityRecords/{id} (Phase 5)
   │
   ├──▶ Cloud Functions: onActivityRecordForSummary
   │        └── updates dailySummaries/{date} automatically
   │
   ▼
Flutter Phone App StreamProvider (Flutter Phase 3-4)
   │
   ▼
Activity Screen UI (real data!)
```

---

## 3. Phase 1 — Foundation

### 3.1 Create Application Class

```kotlin
// app/src/main/java/com/example/relapse_watch/RelapseWatchApp.kt
@HiltAndroidApp
class RelapseWatchApp : Application()
```

Update `AndroidManifest.xml`:
```xml
<application
    android:name=".RelapseWatchApp"
    ...>
```

### 3.2 Annotate All Activities with `@AndroidEntryPoint`

| Activity | Current | Needed |
|---|---|---|
| `MainActivity` | No annotation | `@AndroidEntryPoint` |
| `PreNavigationActivity` | No annotation | `@AndroidEntryPoint` |
| `NavigationActivity` | No annotation | `@AndroidEntryPoint` |
| `ReminderActivity` | No annotation | `@AndroidEntryPoint` |
| `SettingsActivity` | No annotation | `@AndroidEntryPoint` |

### 3.3 Create Hilt Modules Structure

```
di/
├── AppModule.kt          — Application-scoped singletons (DB, DataStore, Firebase)
├── RepositoryModule.kt   — Binds repository interfaces to implementations
├── ServiceModule.kt      — Location, Geofence, Wearable service providers
└── CoroutineModule.kt    — Dispatcher providers for testing
```

### 3.4 Define Domain Entities

Create `domain/model/` with Kotlin data classes:

| Entity | Fields | Purpose |
|---|---|---|
| `MonitoringState` | *(already exists)* — expand with more fields | Overall watch UI state |
| `PatientInfo` | `id`, `name`, `age`, `notes`, `photoUrl` | Patient data received from phone |
| `SafeZoneConfig` | `id`, `centerLat`, `centerLng`, `radiusMeters`, `isActive`, `alarmEnabled`, `vibrationEnabled` | Geofence configuration from phone |
| `SafeZoneEvent` | `id`, `safeZoneId`, `eventType` (ENTER/EXIT), `timestamp`, `lat`, `lng` | Geofence breach record |
| `GeoReminder` | `id`, `title`, `body`, `lat`, `lng`, `radiusMeters`, `imageUrl`, `videoUrl`, `isActive` | Location-triggered memory cue |
| `LocationPoint` | `lat`, `lng`, `timestamp`, `accuracy` | GPS data point |
| `ActivityRecord` | `id`, `timestamp`, `lat`, `lng`, `eventType`, `metadata` | Activity data point for upload |
| `DailySummary` | `date`, `stepCount`, `distanceMeters`, `activeMinutes`, `safeZoneExits`, `remindersTriggered` | Aggregated daily stats |
| `PairingState` | `isPaired`, `pairingCode`, `caregiverUid`, `watchId`, `pairedAt` | Pairing lifecycle |
| `WatchSyncStatus` | `lastSyncTimestamp`, `pendingUploads`, `isConnected` | Sync health |

---

## 4. Phase 2 — Activity Data Layer (Room + DataStore)

> **This phase creates only the Room entities, DAOs, and DataStore needed for activity tracking.** Remaining entities (SafeZone, GeoReminder, etc.) are deferred to Phase 7.

### 4.1 Room Database — Activity Tables First

```kotlin
// data/local/RelapseWatchDatabase.kt
@Database(
    entities = [
        ActivityRecordEntity::class,
        LocationPointEntity::class,
        DailySummaryEntity::class,
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class RelapseWatchDatabase : RoomDatabase() {
    abstract fun activityRecordDao(): ActivityRecordDao
    abstract fun locationPointDao(): LocationPointDao
    abstract fun dailySummaryDao(): DailySummaryDao
}
```

### 4.2 Activity Room Entities & DAOs

| Entity | Table | Key Operations |
|---|---|---|
| `ActivityRecordEntity` | `activity_records` | Insert, get pending upload, mark uploaded, delete old, get by date, get by type |
| `LocationPointEntity` | `location_points` | Insert, get recent, get pending upload, delete old |
| `DailySummaryEntity` | `daily_summaries` | Upsert, get by date, get pending upload |

Key queries in `ActivityRecordDao`:
```kotlin
@Query("SELECT * FROM activity_records WHERE uploaded = 0 ORDER BY timestamp ASC")
suspend fun getPendingUpload(): List<ActivityRecordEntity>

@Query("SELECT * FROM activity_records WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
fun getRecordsByDateRange(start: Long, end: Long): Flow<List<ActivityRecordEntity>>

@Query("SELECT * FROM activity_records ORDER BY timestamp DESC LIMIT 1")
fun getLatestRecord(): Flow<ActivityRecordEntity?>

@Query("UPDATE activity_records SET uploaded = 1 WHERE id IN (:ids)")
suspend fun markUploaded(ids: List<String>)

@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun insertAll(records: List<ActivityRecordEntity>)
```

### 4.3 DataStore Preferences

```kotlin
// data/preferences/WatchPreferences.kt
class WatchPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val isPaired: Flow<Boolean>
    val pairingCode: Flow<String>
    val caregiverUid: Flow<String>
    val watchId: Flow<String>
    val patientName: Flow<String>
    val patientId: Flow<String>
    val lastSyncTimestamp: Flow<Long>
    val safeZoneRadiusMeters: Flow<Int>

    suspend fun setPaired(isPaired: Boolean, caregiverUid: String, watchId: String)
    suspend fun clearPairing()
    suspend fun updateLastSync(timestamp: Long)
    suspend fun setPatientInfo(name: String, id: String)
}
```

### 4.4 Activity Repository

```kotlin
interface ActivityRepository {
    suspend fun insertRecord(record: ActivityRecord)  // record includes patientId
    suspend fun insertBatch(records: List<ActivityRecord>)
    fun getRecordsByDateRange(start: Long, end: Long): Flow<List<ActivityRecord>>
    fun getLatestRecord(): Flow<ActivityRecord?>
    suspend fun getPendingUpload(): List<ActivityRecord>
    suspend fun markUploaded(ids: List<String>)
    suspend fun deleteOlderThan(days: Int)
}

interface DailySummaryRepository {
    suspend fun upsert(summary: DailySummary)              // Local only — never uploaded
    fun getSummaryForDate(date: String): Flow<DailySummary?>
    // No getPendingUpload() or markUploaded() — Cloud Functions own Firestore summaries
}
```

### 4.5 Hilt AppModule — Database & DataStore

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RelapseWatchDatabase {
        return Room.databaseBuilder(context, RelapseWatchDatabase::class.java, "relapse_watch.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("watch_prefs") }
    }

    @Provides fun provideActivityRecordDao(db: RelapseWatchDatabase) = db.activityRecordDao()
    @Provides fun provideLocationPointDao(db: RelapseWatchDatabase) = db.locationPointDao()
    @Provides fun provideDailySummaryDao(db: RelapseWatchDatabase) = db.dailySummaryDao()
}
```

---

## 5. Phase 3 — Location Service & Activity Tracking

> **This phase gets GPS data flowing into the Room DB.** After this phase, the watch is actively collecting location data and recording activity events locally.

### 5.1 Location Service

```kotlin
// services/LocationService.kt
@Singleton
class LocationService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fusedLocationClient: FusedLocationProviderClient

    fun getLocationUpdates(intervalMs: Long = 30_000): Flow<LocationPoint>
    suspend fun getLastKnownLocation(): LocationPoint?
    suspend fun requestPermissions(activity: Activity): Boolean
    fun isLocationEnabled(): Boolean
    fun calculateDistance(from: LocationPoint, to: LocationPoint): Float
    fun calculateBearing(from: LocationPoint, to: LocationPoint): Float
}
```

### 5.2 Activity Tracking Service

```kotlin
// services/ActivityTrackingService.kt
@Singleton
class ActivityTrackingService @Inject constructor(
    private val locationService: LocationService,
    private val activityRepository: ActivityRepository,
    private val dailySummaryRepository: DailySummaryRepository
) {
    // Periodic location sampling
    fun startTracking(): Flow<LocationPoint>
    fun stopTracking()

    // Record activity events
    suspend fun recordLocationUpdate(point: LocationPoint)
    suspend fun recordSafeZoneEvent(eventType: String, point: LocationPoint)
    suspend fun recordReminderTriggered(reminderId: String, point: LocationPoint)

    // Daily aggregation (called periodically + on events)
    suspend fun updateDailySummary(date: LocalDate)
}
```

### 5.3 Daily Summary Computation (Local Only — Not Uploaded)

> **The watch computes DailySummary locally in Room for its own MonitoringScreen display. This is NEVER uploaded to Firestore.** Cloud Functions maintain the authoritative Firestore `dailySummaries/{date}` documents.

```kotlin
// Inside ActivityTrackingService
suspend fun updateDailySummary(date: LocalDate) {
    val records = activityRepository.getRecordsByDateRange(date.startOfDay, date.endOfDay)
    val summary = DailySummary(
        date = date.toString(),
        distanceMeters = computeTotalDistance(records.filter { it.eventType == "location_update" }),
        activeMinutes = computeTimeOutsideSafeZone(records),
        placesVisited = computeDistinctPlaces(records, clusterRadiusMeters = 100),
        safeZoneExits = records.count { it.eventType == "safe_zone_exit" },
        remindersTriggered = records.count { it.eventType == "reminder_triggered" },
        totalEvents = records.size,
        stepCount = 0 // Health Services integration later
    )
    dailySummaryRepository.upsert(summary)
}
```

### 5.4 Milestone: Watch Collecting Data Locally

After Phase 3, the watch is:
- Receiving GPS updates every 30 seconds
- Writing `ActivityRecord` entries (with `patientId` and snake_case `eventType`) to Room DB
- Computing and updating local `DailySummary` every 5 minutes (Room DB only, not uploaded)
- All data is local only (no upload yet — that's Phase 5, after Pairing in Phase 4)

---

## 6. Phase 4 — Pairing Flow + Firebase Setup

> **MOVED UP from original Phase 6.** Pairing must happen before activity upload because `SyncService` requires `caregiverUid` and `patientId` to construct Firestore paths. This phase also includes Firebase project setup since it's needed for the pairing flow itself.

### 6.1 Firebase Setup (Prerequisite)

- [ ] Add `google-services.json` to `app/` directory (same Firebase project: `relapse-488712`)
- [ ] Initialize Firebase in `RelapseWatchApp.onCreate()`
- [ ] Firebase Auth: anonymous auth for watch (linked to caregiver account via pairing)

### 6.2 Auth Strategy

```
Watch Pairing Flow:
1. Watch starts → anonymous Firebase Auth sign-in
2. Watch generates 6-digit NUMERIC pairing code → writes to Firestore
3. Caregiver enters code on phone app → phone confirms pairing in Firestore
4. Watch listens for confirmation → stores caregiverUid + patientId locally
5. Watch now has Firestore path: users/{caregiverUid}/patients/{patientId}/...
```

Firebase Security Rules:
```
match /users/{userId}/patients/{patientId}/activityRecords/{recordId} {
  allow write: if request.auth != null &&
    get(/databases/$(database)/documents/users/$(userId)/watchPairing).data.watchId == request.auth.uid;
}
```

### 6.3 Pairing ViewModel

```kotlin
// viewmodel/PairingViewModel.kt
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
    private val preferences: WatchPreferences,
) : ViewModel() {

    val pairingState: StateFlow<PairingUiState>

    fun generatePairingCode()          // Generate random 6-DIGIT NUMERIC code (matches Flutter)
    fun submitPairingCode(code: String) // Write to Firestore, listen for confirmation
    fun cancelPairing()
}

sealed class PairingUiState {
    object ShowCode : PairingUiState()              // Display code, waiting
    data class Connecting(val code: String) : PairingUiState()  // Code submitted, verifying
    data class Paired(val patientName: String) : PairingUiState() // Success
    data class Error(val message: String) : PairingUiState()
}
```

### 6.4 Pairing Repository

```kotlin
// repository/PairingRepository.kt
interface PairingRepository {
    suspend fun createPairingEntry(code: String, watchId: String): Result<Unit>
    fun observePairingStatus(code: String): Flow<PairingState>
    suspend fun confirmPairing(caregiverUid: String): Result<Unit>
    suspend fun clearPairing(): Result<Unit>
    fun isPaired(): Flow<Boolean>
}
```

### 6.5 Pairing Sequence

```
Watch                        Firestore                   Phone
  │                             │                          │
  ├─ Generate code "123456"    │                          │
  ├─ Display on screen ────────┤                          │
  │                             │                          │
  │                             │◄── Phone: user enters    │
  │                             │    "123456" in app       │
  │                             │                          │
  │                             ├── Phone writes:          │
  │                             │   watchPairing.status    │
  │                             │   = "paired"             │
  │                             │   + caregiverUid         │
  │                             │   + patientId            │
  │                             │                          │
  │◄── Watch observes ─────────┤                          │
  │    status change            │                          │
  │                             │                          │
  ├─ Store caregiverUid        │                          │
  ├─ Store patientId           │                          │
  ├─ Navigate to Monitoring    │                          │
  ├─ Start foreground service  │                          │
  ├─ Begin data collection     │                          │
```

### 6.6 Milestone: Watch Paired with Phone

After Phase 4:
- Watch has `caregiverUid` and `patientId` stored in DataStore preferences
- Firebase Auth initialized with anonymous sign-in
- Ready for Phase 5 (Firebase upload) — Firestore paths are now available

---

## 7. Phase 5 — Firebase Integration & Activity Upload

> **This phase uploads collected activity data to Firestore.** After this phase, the phone's Activity Screen can display real data from the watch. This requires Phase 4 (Pairing) to be complete so that `caregiverUid` and `patientId` are available.

### 7.1 Activity Firestore Source (Upload)

```kotlin
// data/remote/FirestoreActivitySource.kt
@Singleton
class FirestoreActivitySource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    /// Upload a batch of activity records
    /// IMPORTANT: Converts Room entities to Firestore-compatible format:
    ///   - timestamp: Long epoch ms → Firestore Timestamp
    ///   - patientId: included as document field
    ///   - eventType: snake_case string constants
    suspend fun uploadActivityRecords(
        caregiverUid: String,
        patientId: String,
        records: List<ActivityRecord>
    ): Result<Unit>
}
```

Firestore paths (WATCH WRITES):
```
users/{caregiverUid}/patients/{patientId}/activityRecords/{id}    ← batch write
```

> **Note:** Watch does NOT write to `dailySummaries/{date}`. Cloud Functions own that collection (see Conflict Resolution #4).

### 7.2 Sync Service (Activity-Focused — No DailySummary Upload)

> **CHANGED:** `SyncService` no longer uploads `DailySummary`. Only `activityRecords` are uploaded. Cloud Functions handle summary aggregation.

```kotlin
// services/SyncService.kt
@Singleton
class SyncService @Inject constructor(
    private val activityRepository: ActivityRepository,
    private val firestoreActivitySource: FirestoreActivitySource,
    private val preferences: WatchPreferences
) {
    /// Upload all pending activity records to Firestore
    suspend fun syncActivityData() {
        val uid = preferences.caregiverUid.first()
        val patientId = preferences.patientId.first()
        if (uid.isBlank() || patientId.isBlank()) return  // Not paired yet

        val pendingRecords = activityRepository.getPendingUpload()
        if (pendingRecords.isNotEmpty()) {
            firestoreActivitySource.uploadActivityRecords(uid, patientId, pendingRecords)
            activityRepository.markUploaded(pendingRecords.map { it.id })
        }

        preferences.updateLastSync(System.currentTimeMillis())
    }

    /// Schedule periodic sync (every 5 minutes)
    fun schedulePeriodicSync(intervalMinutes: Int = 5)
}
```

### 7.3 Milestone: Activity Data Flowing to Phone

After Phase 5:
```
Watch GPS → Room DB → Firestore upload (every 5 min)
                                │
                                ├──▶ Cloud Functions update dailySummaries
                                │
                                ▼
                    Phone's Activity Screen shows REAL DATA
```

**This is the critical milestone — the Activity Screen is functional end-to-end.**

---

## 8. Phase 6 — Activity Foreground Service

> **Persistent GPS tracking requires a foreground service.** Without it, Android kills location updates within minutes of the screen turning off. This phase ensures the watch continuously collects activity data.

### 8.1 Monitoring Foreground Service (Activity-Focused)

```kotlin
// services/MonitoringForegroundService.kt
@AndroidEntryPoint
class MonitoringForegroundService : Service() {

    @Inject lateinit var locationService: LocationService
    @Inject lateinit var activityTrackingService: ActivityTrackingService
    @Inject lateinit var syncService: SyncService

    override fun onCreate() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun startMonitoring() {
        // 1. Start periodic location updates → ActivityTrackingService
        // 2. Schedule periodic local DailySummary recomputation (on-device only)
        // 3. Schedule periodic Firestore upload (SyncService — activityRecords only)
    }

    override fun onDestroy() {
        // Stop location updates, cancel scheduled work
    }
}
```

### 8.2 Service Lifecycle

```
App launched (MainActivity)
        │
        ├── If paired:
        │   ├── Start MonitoringForegroundService
        │   ├── Show persistent notification: "Monitoring active"
        │   └── Service runs even when app in background
        │
        ├── If NOT paired:
        │   └── Show PairingScreen (no service)
        │
On boot (BootCompletedReceiver):
        │
        ├── If paired:
        │   └── Auto-start MonitoringForegroundService
        │
On unpair:
        │
        └── Stop MonitoringForegroundService
```

### 8.3 Boot Receiver

```kotlin
// services/BootCompletedReceiver.kt
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {
    @Inject lateinit var preferences: WatchPreferences

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Check if paired, if yes → start foreground service
        }
    }
}
```

Manifest additions:
```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<receiver
    android:name=".services.BootCompletedReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

### 8.4 Milestone: Persistent Data Collection

After Phase 6:
- Watch collects GPS data **24/7** via foreground service
- Data flows: GPS → ActivityTrackingService → Room DB → Firestore → Cloud Functions → dailySummaries
- Phone Activity Screen displays continuously updated real data
- **The core activity pipeline is complete. Remaining phases add safety and communication features.**

---

## 9. Phase 7 — Full Data Layer (Remaining Room Entities)

> **Phase 2 created only activity-related tables.** This phase adds the remaining Room entities for safe zones, geo-reminders, and safe zone events.

### 9.1 Additional Room Entities

```kotlin
// Add to RelapseWatchDatabase.kt entities array:
@Database(
    entities = [
        ActivityRecordEntity::class,    // Phase 2
        LocationPointEntity::class,     // Phase 2
        DailySummaryEntity::class,      // Phase 2
        SafeZoneEntity::class,          // NEW
        SafeZoneEventEntity::class,     // NEW
        GeoReminderEntity::class,       // NEW
    ],
    version = 2,
    exportSchema = true
)
```

### 9.2 New DAOs

| Entity | Table | Key Operations |
|---|---|---|
| `SafeZoneEntity` | `safe_zones` | Insert/update (from phone sync), get active |
| `SafeZoneEventEntity` | `safe_zone_events` | Insert (on geofence trigger), get by zone, get pending upload |
| `GeoReminderEntity` | `geo_reminders` | Insert/update (from phone sync), get active, get by location |

### 9.3 Additional Repositories

```kotlin
interface SafeZoneRepository {
    fun getActiveSafeZone(): Flow<SafeZoneConfig?>
    suspend fun updateFromFirestore(config: SafeZoneConfig)
    suspend fun recordEvent(event: SafeZoneEvent)
    fun getEvents(zoneId: String): Flow<List<SafeZoneEvent>>
    suspend fun getPendingEventUpload(): List<SafeZoneEvent>
}

interface GeoReminderRepository {
    fun getActiveReminders(): Flow<List<GeoReminder>>
    suspend fun syncFromPhone(reminders: List<GeoReminder>)
    suspend fun markAsTriggered(reminderId: String, timestamp: Long)
    suspend fun getReminder(id: String): GeoReminder?
    fun getReminderCount(): Flow<Int>
}

interface SafeZoneEventRepository {
    suspend fun insert(event: SafeZoneEvent)
    suspend fun getPendingUpload(): List<SafeZoneEvent>
    suspend fun markUploaded(ids: List<String>)
}
```

### 9.4 Firestore Remote Sources (Remaining)

```
data/remote/
├── FirestorePairingSource.kt     — Read/write pairing doc
├── FirestorePatientSource.kt     — Read patient info (phone writes, watch reads)
├── FirestoreSafeZoneSource.kt    — Read config (phone writes), write events (watch writes)
├── FirestoreReminderSource.kt    — Read reminders (phone writes)
└── FirestoreActivitySource.kt    — Already created in Phase 4
```

Firestore paths (shared with phone):
```
users/{caregiverUid}/
├── watchPairing: { pairingCode, watchId, status, pairedAt }
├── patients/{patientId}/
│   ├── info: { name, age, notes, photoUrl }
│   ├── memoryReminders/{reminderId}: { title, body, lat, lng, radius, imageUrl, videoUrl }
│   ├── safeZones/{zoneId}: { centerLat, centerLng, radius, alarm, vibrate }
│   ├── safeZoneEvents/{eventId}: { type, timestamp, lat, lng }  ← WATCH WRITES
│   ├── activityRecords/{recordId}: …                            ← Phase 4
│   └── dailySummaries/{date}: …                                 ← Phase 4
```

### 9.5 Hilt Repository Bindings

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun bindActivityRepository(impl: ActivityRepositoryImpl): ActivityRepository
    @Binds abstract fun bindDailySummaryRepository(impl: DailySummaryRepositoryImpl): DailySummaryRepository
    @Binds abstract fun bindSafeZoneRepository(impl: SafeZoneRepositoryImpl): SafeZoneRepository
    @Binds abstract fun bindGeoReminderRepository(impl: GeoReminderRepositoryImpl): GeoReminderRepository
    @Binds abstract fun bindSafeZoneEventRepository(impl: SafeZoneEventRepositoryImpl): SafeZoneEventRepository
    @Binds abstract fun bindPairingRepository(impl: PairingRepositoryImpl): PairingRepository
}
```

---

## 10. Phase 8 — Phone Communication (Wearable Data Layer)

### 10.1 Wearable Message/Data Service

```kotlin
// services/WearableCommunicationService.kt
@Singleton
class WearableCommunicationService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val messageClient: MessageClient
    private val dataClient: DataClient
    private val nodeClient: NodeClient

    // Outbound (Watch → Phone)
    suspend fun sendSafeZoneEvent(event: SafeZoneEvent)
    suspend fun sendActivityBatch(records: List<ActivityRecord>)
    suspend fun sendWatchStatus(status: WatchSyncStatus)
    suspend fun sendPairingRequest(code: String)

    // Inbound (Phone → Watch) — via DataClient listeners
    fun observePatientData(): Flow<PatientInfo>
    fun observeSafeZoneConfig(): Flow<SafeZoneConfig>
    fun observeGeoReminders(): Flow<List<GeoReminder>>
    fun observeUnpairCommand(): Flow<Unit>

    // Connection
    fun getConnectedPhone(): Flow<Node?>
    fun isPhoneReachable(): Flow<Boolean>
}
```

### 10.2 Data Layer Paths (Wearable DataClient)

| Path | Direction | Data |
|---|---|---|
| `/patient/info` | Phone → Watch | Patient name, age, photo URL |
| `/patient/safe-zone` | Phone → Watch | Safe zone center, radius, settings |
| `/patient/geo-reminders` | Phone → Watch | List of reminders (JSON) |
| `/patient/settings` | Phone → Watch | Reminder cooldown, notification prefs |
| `/watch/status` | Watch → Phone | Battery, last sync, connection |
| `/watch/unpair` | Phone → Watch | Unpair command |

### 10.3 Message Paths (Wearable MessageClient)

| Path | Direction | Purpose |
|---|---|---|
| `/pairing/request` | Watch → Phone | Send pairing code |
| `/pairing/confirm` | Phone → Watch | Confirm pairing success |
| `/safe-zone/event` | Watch → Phone | Immediate safe zone exit alert |
| `/reminder/triggered` | Watch → Phone | Notify phone a reminder was shown |
| `/sync/request` | Watch → Phone | Request a full data sync |

### 10.4 WearableListenerService

```kotlin
// services/WearableDataListenerService.kt
@AndroidEntryPoint
class WearableDataListenerService : WearableListenerService() {

    @Inject lateinit var safeZoneRepository: SafeZoneRepository
    @Inject lateinit var reminderRepository: GeoReminderRepository
    @Inject lateinit var preferences: WatchPreferences

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // Handle Phone → Watch data sync:
        // - Patient info updates
        // - Safe zone config changes → re-register geofences
        // - Geo-reminder list changes → update local DB + geofences
        // - Settings changes
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        // Handle:
        // - Unpair command → clear all data, reset to pairing screen
        // - Pairing confirmation
        // - Force sync request
    }
}
```

Register in `AndroidManifest.xml`:
```xml
<service
    android:name=".services.WearableDataListenerService"
    android:exported="true">
    <intent-filter>
        <action android:name="com.google.android.gms.wearable.DATA_CHANGED" />
        <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
        <data android:scheme="wear" android:host="*" />
    </intent-filter>
</service>
```

---

## 11. Phase 9 — Safe Zone Geofencing

### 11.1 Geofence Service

```kotlin
// services/GeofenceService.kt
@Singleton
class GeofenceService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationService: LocationService
) {
    private val geofencingClient: GeofencingClient

    suspend fun registerSafeZone(config: SafeZoneConfig): Result<Unit>
    suspend fun removeSafeZone(zoneId: String): Result<Unit>
    suspend fun registerGeoReminder(reminder: GeoReminder): Result<Unit>
    suspend fun removeGeoReminder(reminderId: String): Result<Unit>
    fun removeAllGeofences(): Result<Unit>

    // Internal: PendingIntent for GeofenceBroadcastReceiver
}
```

### 11.2 Geofence BroadcastReceiver

```kotlin
// services/GeofenceBroadcastReceiver.kt
@AndroidEntryPoint
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    @Inject lateinit var safeZoneRepository: SafeZoneRepository
    @Inject lateinit var reminderRepository: GeoReminderRepository
    @Inject lateinit var notificationService: NotificationService

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return

        when {
            // Safe zone exit → trigger PreNavigationActivity countdown
            isSafeZoneExit(event) -> {
                // IMPORTANT: Use lowercase "exit" string, not SafeZoneEvent.EXIT.name
                recordSafeZoneEvent("exit", event)
                recordActivityEvent(EventTypes.SAFE_ZONE_EXIT, event)
                launchPreNavigationActivity(context)
                vibrateWatch()
                notifyPhone()
            }
            // Safe zone enter → cancel navigation, log event
            isSafeZoneEnter(event) -> {
                // IMPORTANT: Use lowercase "enter" string, not SafeZoneEvent.ENTER.name
                recordSafeZoneEvent("enter", event)
                recordActivityEvent(EventTypes.SAFE_ZONE_ENTER, event)
                cancelActiveNavigation()
            }
            // Geo-reminder zone enter → show reminder
            isGeoReminder(event) -> {
                val reminder = findMatchingReminder(event)
                recordActivityEvent(EventTypes.REMINDER_TRIGGERED, event)
                launchReminderActivity(context, reminder)
                notifyPhone()
            }
        }
    }
}
```

Register in `AndroidManifest.xml`:
```xml
<receiver
    android:name=".services.GeofenceBroadcastReceiver"
    android:exported="false" />
```

### 11.3 Safe Zone ViewModel

```kotlin
@HiltViewModel
class MonitoringViewModel @Inject constructor(
    private val preferences: WatchPreferences,
    private val safeZoneRepository: SafeZoneRepository,
    private val locationService: LocationService
) : ViewModel() {

    val uiState: StateFlow<MonitoringUiState>

    // Combines:
    // - Patient info from preferences/DataStore
    // - Safe zone status from location checks
    // - Last sync timestamp
    // - Geo-reminder count from local DB
    // - Watch connection status
}
```

---

## 12. Phase 10 — Geo-Triggered Memory Reminders

### 12.1 Geo-Reminder Repository

```kotlin
interface GeoReminderRepository {
    fun getActiveReminders(): Flow<List<GeoReminder>>
    suspend fun syncFromPhone(reminders: List<GeoReminder>)
    suspend fun markAsTriggered(reminderId: String, timestamp: Long)
    suspend fun getReminder(id: String): GeoReminder?
    fun getReminderCount(): Flow<Int>
}
```

### 12.2 Reminder ViewModel

```kotlin
@HiltViewModel
class ReminderViewModel @Inject constructor(
    private val reminderRepository: GeoReminderRepository,
    private val wearableService: WearableCommunicationService,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val reminder: StateFlow<GeoReminder?>
    val imageLoadState: StateFlow<ImageLoadState>

    fun dismissReminder()      // Mark shown, notify phone
    fun playVideo(videoUrl: String)  // Launch video intent or in-app player
}
```

### 12.3 Reminder Trigger Flow

```
Geofence triggers entry into reminder zone
        │
        ▼
GeofenceBroadcastReceiver
        │
        ├── Query Room DB for matching GeoReminder
        ├── Check cooldown (last triggered timestamp)
        │
        ├── If cooldown passed:
        │   ├── Launch ReminderActivity with reminder ID
        │   ├── Vibrate watch
        │   ├── Mark triggered in DB
        │   └── Notify phone via MessageClient
        │
        └── If within cooldown:
            └── Skip (log only)
```

### 12.4 Image Loading

- Use **Coil** (already declared) for loading reminder images
- Images served from Firebase Storage URLs
- Cache locally for offline access
- Fallback placeholder if no connectivity

---

## 13. Phase 11 — Navigation (Guide Patient Home)

### 13.1 Navigation ViewModel

```kotlin
@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val locationService: LocationService,
    private val safeZoneRepository: SafeZoneRepository
) : ViewModel() {

    val navigationState: StateFlow<NavigationUiState>

    // Continuously calculates:
    // - Distance from current location to safe zone center
    // - Bearing from current location to safe zone center
    // - Whether patient has re-entered safe zone

    fun startNavigation()    // Begin continuous location updates
    fun stopNavigation()     // Cancel updates
}

data class NavigationUiState(
    val distanceMeters: Float = 0f,
    val bearingDegrees: Float = 0f,
    val isNavigating: Boolean = false,
    val hasReachedSafeZone: Boolean = false
)
```

### 13.2 Navigation Flow

```
PreNavigationActivity (10s countdown)
        │
        ├── User does NOT cancel within 10s
        │   └── Launch NavigationActivity
        │
        └── User cancels
            └── Return to MonitoringScreen

NavigationActivity
        │
        ├── Get safe zone center from Repository
        ├── Start location updates (high frequency: 5s)
        │
        ├── Every update:
        │   ├── Calculate distance to safe zone center
        │   ├── Calculate bearing (compass direction)
        │   ├── Update UI (arrow rotation + distance text)
        │   │
        │   └── If distance < safe zone radius:
        │       ├── Show "You're Home!" message
        │       ├── Record ENTER event
        │       └── Auto-finish after 3s
        │
        └── Back/swipe → stop navigation, return to monitoring
```

### 13.3 Pre-Navigation ViewModel

```kotlin
@HiltViewModel
class PreNavigationViewModel @Inject constructor(
    private val safeZoneRepository: SafeZoneRepository,
    private val notificationService: NotificationService
) : ViewModel() {

    val countdownSeconds: StateFlow<Int>    // 10 → 0
    val shouldNavigate: StateFlow<Boolean>  // true when countdown reaches 0

    fun startCountdown()
    fun cancelCountdown()    // User pressed cancel
}
```

---

## 14. Phase 12 — Notifications & Haptics

> **Note:** The foreground service created in Phase 5 already handles monitoring. This phase adds user-facing notifications and haptic feedback for safety events.

### 14.1 Notification Service

```kotlin
// services/NotificationService.kt
@Singleton
class NotificationService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun createNotificationChannels()   // On app start

    // Channels:
    // - "monitoring"  — persistent foreground service notification
    // - "safe_zone"   — high priority, vibration pattern
    // - "reminders"   — default priority
    // - "system"      — low priority (sync status, etc.)

    fun showSafeZoneExitNotification()
    fun showReminderNotification(reminder: GeoReminder)
    fun showMonitoringNotification(): Notification   // For foreground service
    fun cancelAll()
}
```

### 14.2 Vibration Patterns

| Event | Pattern | Intensity |
|---|---|---|
| Safe zone exit | Long-short-long (500ms-200ms-500ms), repeat 3x | Strong |
| Geo-reminder triggered | Double pulse (200ms-100ms-200ms) | Medium |
| Navigation arrived home | Single long pulse (800ms) | Medium |
| Pairing successful | Triple short (100ms-100ms-100ms) | Light |

```kotlin
// services/HapticService.kt
@Singleton
class HapticService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val vibrator: Vibrator

    fun safeZoneExitVibration()
    fun reminderVibration()
    fun arrivedHomeVibration()
    fun confirmationVibration()
    fun cancelVibration()
}
```

---

## 15. Phase 13 — Offline Resilience & Sync

### 15.1 Full Sync Service

> **Phase 4 created a basic SyncService for activity data only.** This phase expands it to handle all data types.

```kotlin
// services/SyncService.kt (expanded)
@Singleton
class SyncService @Inject constructor(
    private val activityRepository: ActivityRepository,
    private val safeZoneEventRepository: SafeZoneEventRepository,
    private val wearableService: WearableCommunicationService,
    private val firestoreActivitySource: FirestoreActivitySource,
    private val connectivityMonitor: ConnectivityMonitor
) {
    // Upload pending data (activity records, safe zone events, daily summaries)
    suspend fun syncPendingData()

    // Download latest config (safe zones, reminders, patient info)
    suspend fun pullLatestConfig()

    // Full bidirectional sync
    suspend fun fullSync()

    // Schedule periodic sync
    fun schedulePeriodicSync(intervalMinutes: Int = 5)
}
```

### 15.2 Connectivity Monitor

```kotlin
@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val isPhoneConnected: Flow<Boolean>     // Bluetooth to phone
    val isInternetAvailable: Flow<Boolean>  // WiFi or phone tethering
    val bestSyncPath: Flow<SyncPath>        // WEARABLE, FIREBASE, or NONE
}

enum class SyncPath { WEARABLE, FIREBASE, NONE }
```

### 15.3 Offline Behavior

| Scenario | Behavior |
|---|---|
| No phone, no internet | All data stored in Room DB. Geofences + GPS still work. |
| No phone, has internet | Sync via Firestore directly |
| Phone connected, no internet | Sync via Wearable Data Layer (Bluetooth) |
| Phone connected + internet | Both channels available; prefer Wearable for speed |

### 15.4 Data Retention

- Activity records: keep 7 days locally, upload and purge
- Location points: keep 24 hours locally, upload and purge
- Safe zone events: keep 30 days locally
- Geo-reminders: keep indefinitely (synced from phone)
- Daily summaries: keep 90 days locally

---

## 16. Phase 14 — Testing & Hardening

### 16.1 Unit Tests

| Target | Tests |
|---|---|
| ViewModels | State transitions, error handling, with mocked repos |
| Repositories | CRUD, sync logic, with mocked DAOs and remote sources |
| Services | Location calculations, geofence registration, with mocks |
| Mappers | Entity ↔ Domain model conversions |

### 16.2 Integration Tests

| Flow | Scope |
|---|---|
| Pairing | Generate code → submit → observe confirmation → navigate |
| Safe zone | Config received → geofence registered → trigger → event recorded → phone notified |
| Reminder | Reminder synced → geofence registered → enter zone → show reminder → dismiss |
| Navigation | Safe zone exit → countdown → navigation start → arrive → auto-finish |
| Sync | Offline data accumulation → connectivity restored → batch upload |

### 16.3 Error Handling

- Graceful location permission denial → show explanation, fallback
- Geofence limit (max 100 on Android) → prioritize safe zone, then nearest reminders
- Firebase offline → Room DB absorbs all writes, retry on reconnect
- Watch battery optimization → adjust location frequency based on battery level
- Crash recovery → `BootCompletedReceiver` restarts monitoring

### 16.4 Battery Optimization

| Strategy | Implementation |
|---|---|
| Adaptive location frequency | 30s when outside safe zone, 5min when inside |
| Batch uploads | Collect 5min of data, upload in one batch |
| Firestore offline persistence | Reduce network calls |
| Display-off optimization | Reduce location frequency to 2min when screen off |

---

## 17. Dependency Summary

All dependencies are **already declared** in `build.gradle.kts`. None need to be added:

| Category | Library | Status | Usage Needed |
|---|---|---|---|
| **DI** | Hilt 2.54 + KSP | Declared, not wired | `@HiltAndroidApp`, `@AndroidEntryPoint`, Modules |
| **Database** | Room 2.7.1 | Declared, not wired | Entities, DAOs, Database class |
| **Preferences** | DataStore 1.1.4 | Declared, not wired | Pairing state, settings |
| **Firebase** | Firestore KTX, Auth KTX | Declared, not wired | Cloud sync, anonymous auth |
| **Location** | Play Services Location 21.0.1 | Declared, not wired | GPS, geofencing |
| **Watch Comms** | Play Services Wearable 19.0.0 | Declared, not wired | MessageClient, DataClient |
| **Async** | Coroutines 1.10.2 | Declared, minimally used | Full Flow/coroutine adoption |
| **Serialization** | kotlinx-serialization 1.7.3 | Declared, not wired | JSON for Wearable messages |
| **Image Loading** | Coil 3.1.0 | Declared, minimally used | Reminder images |
| **Splash** | Core Splashscreen | Declared | Already may be in use |

**No new dependencies required** — the build.gradle.kts already has everything needed.

---

## 18. Directory Structure

```
app/src/main/java/com/example/relapse_watch/
├── RelapseWatchApp.kt                          — @HiltAndroidApp Application
├── presentation/
│   ├── MainActivity.kt                         — @AndroidEntryPoint (existing, update)
│   ├── PreNavigationActivity.kt                — @AndroidEntryPoint (existing, update)
│   ├── NavigationActivity.kt                   — @AndroidEntryPoint (existing, update)
│   ├── ReminderActivity.kt                     — @AndroidEntryPoint (existing, update)
│   ├── SettingsActivity.kt                     — @AndroidEntryPoint (existing, update)
│   ├── screens/
│   │   ├── PairingScreen.kt                    — (existing, wire to ViewModel)
│   │   ├── MonitoringScreen.kt                 — (existing, wire to ViewModel)
│   │   ├── PreNavigationScreen.kt              — (existing, wire to ViewModel)
│   │   ├── NavigationScreen.kt                 — (existing, wire to ViewModel)
│   │   ├── ReminderScreen.kt                   — (existing, wire to ViewModel)
│   │   └── SettingsScreen.kt                   — (existing, wire to ViewModel)
│   ├── model/
│   │   └── MonitoringState.kt                  — (existing, expand)
│   └── theme/
│       └── Theme.kt                            — (existing, no changes)
├── domain/
│   ├── model/
│   │   ├── PatientInfo.kt
│   │   ├── SafeZoneConfig.kt
│   │   ├── SafeZoneEvent.kt
│   │   ├── GeoReminder.kt
│   │   ├── LocationPoint.kt
│   │   ├── ActivityRecord.kt
│   │   ├── DailySummary.kt
│   │   ├── PairingState.kt
│   │   └── WatchSyncStatus.kt
│   └── repository/
│       ├── PairingRepository.kt                — Interface
│       ├── SafeZoneRepository.kt               — Interface
│       ├── GeoReminderRepository.kt            — Interface
│       ├── ActivityRepository.kt               — Interface
│       └── DailySummaryRepository.kt           — Interface
├── data/
│   ├── local/
│   │   ├── RelapseWatchDatabase.kt             — Room DB
│   │   ├── Converters.kt                       — Type converters
│   │   ├── entity/
│   │   │   ├── SafeZoneEntity.kt
│   │   │   ├── SafeZoneEventEntity.kt
│   │   │   ├── GeoReminderEntity.kt
│   │   │   ├── ActivityRecordEntity.kt
│   │   │   ├── LocationPointEntity.kt
│   │   │   └── DailySummaryEntity.kt
│   │   └── dao/
│   │       ├── SafeZoneDao.kt
│   │       ├── SafeZoneEventDao.kt
│   │       ├── GeoReminderDao.kt
│   │       ├── ActivityRecordDao.kt
│   │       ├── LocationPointDao.kt
│   │       └── DailySummaryDao.kt
│   ├── remote/
│   │   ├── FirestorePairingSource.kt
│   │   ├── FirestorePatientSource.kt
│   │   ├── FirestoreSafeZoneSource.kt
│   │   ├── FirestoreReminderSource.kt
│   │   └── FirestoreActivitySource.kt
│   ├── repository/
│   │   ├── PairingRepositoryImpl.kt
│   │   ├── SafeZoneRepositoryImpl.kt
│   │   ├── GeoReminderRepositoryImpl.kt
│   │   ├── ActivityRepositoryImpl.kt
│   │   └── DailySummaryRepositoryImpl.kt
│   └── preferences/
│       └── WatchPreferences.kt                 — DataStore wrapper
├── services/
│   ├── LocationService.kt
│   ├── GeofenceService.kt
│   ├── GeofenceBroadcastReceiver.kt
│   ├── MonitoringForegroundService.kt
│   ├── WearableCommunicationService.kt
│   ├── WearableDataListenerService.kt
│   ├── ActivityTrackingService.kt
│   ├── HealthTrackingService.kt
│   ├── NotificationService.kt
│   ├── HapticService.kt
│   ├── SyncService.kt
│   ├── ConnectivityMonitor.kt
│   └── BootCompletedReceiver.kt
├── di/
│   ├── AppModule.kt
│   ├── RepositoryModule.kt
│   ├── ServiceModule.kt
│   └── CoroutineModule.kt
└── viewmodel/
    ├── PairingViewModel.kt
    ├── MonitoringViewModel.kt
    ├── PreNavigationViewModel.kt
    ├── NavigationViewModel.kt
    ├── ReminderViewModel.kt
    └── SettingsViewModel.kt
```

---

## Implementation Priority Order

> **REVISED:** Pairing moved to Phase 4 (before Firebase upload). DailySummary upload removed from watch responsibilities. Phase numbers updated.

| Priority | Phase | Blocking | Activity Data? |
|---|---|---|---|
| P0 | Phase 1 — Foundation (Hilt, App class) | Everything | Required |
| P0 | Phase 2 — Activity Data Layer (Room, DataStore) | Activity persistence | Required |
| P0 | Phase 3 — Location Service & Activity Tracking | GPS data collection | Required |
| P0 | **Phase 4 — Pairing + Firebase Setup** | Firestore paths | **Required (moved up)** |
| P0 | Phase 5 — Firebase & Activity Upload | Cloud sync to phone | Required |
| P0 | Phase 6 — Activity Foreground Service | Persistent monitoring | Required |
| P1 | Phase 7 — Full Data Layer (remaining entities) | Safety features | No |
| P1 | Phase 8 — Phone Communication | Phone↔Watch sync | Indirect |
| P1 | Phase 9 — Safe Zone Geofencing | Core safety feature | No |
| P2 | Phase 10 — Geo-Reminders | Memory cues | No |
| P2 | Phase 11 — Navigation | Guide patient home | No |
| P3 | Phase 12 — Notifications & Haptics | Alerts | No |
| P3 | Phase 13 — Offline Resilience (full) | Reliability | Indirect |
| P3 | Phase 14 — Testing | Quality | No |

**Activity Screen functional after Phase 6 (Phases 1-6 complete the core pipeline)**

---

## Cross-App Integration Checkpoints

> **REVISED:** Phase numbers updated to reflect new ordering. Data contract requirements added.

| Milestone | Phone App Phase | Watch App Phase | Verification | Data Contract Notes |
|---|---|---|---|---|
| **Pairing works** | Phase 5 (Watch Comms) | **Phase 4 (Pairing + Firebase)** | Code entered on phone pairs with watch | Numeric 6-digit code; `watchPairing` doc in Firestore |
| **Activity visible** | Phase 3-4 (Activity Data Layer + Providers) | **Phase 2-6 (Data + Location + Pairing + Upload + Service)** | Watch tracks → phone shows real data on Activity Screen | `eventType` must be snake_case; `patientId` in every doc; `timestamp` as Firestore Timestamp |
| Safe zone syncs | Phase 4 (Firestore) | Phase 9 (Geofencing) | Config set on phone → geofence on watch | Safe zone event types: lowercase `"enter"` / `"exit"` |
| Safe zone alert | Phase 9 (Notifications) | Phase 9 + 11 (Geofence + Nav) | Patient exits → phone alert + watch navigation | Cloud Functions send push via FCM; watch writes to `safeZoneEvents/` |
| Reminders sync | Phase 7 (Memory Cues) | Phase 10 (Geo-Reminders) | Reminder created on phone → triggers on watch | `reminder_triggered` event type; `reminderId` in metadata |
| Unpair works | Phase 11 (Settings) | Phase 4 (Pairing) | Phone unpairs → watch resets to pairing screen | `watchPairing.status = "unpaired"` |
