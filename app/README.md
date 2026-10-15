# FlipLauncher app 0.7.0

Target: Galaxy Z Flip5 / One UI 8.5. Application ID `com.fliplauncher`.
Minimum API 33, target/compile API 36. Version code 11.

## Grouped picker and widget recovery in 0.7.0

WidgetPickerModel groups the personal-profile provider catalog by package and
sorts app/widget labels, using component identity for variants. App-name search
returns all variants; widget-name search filters variants. Search results expand
automatically. Manual expansion, query and the optional package filter survive
recreation. WidgetPickerAdapter uses ListAdapter diffs and stable row identities,
with restoration delayed until catalog rows arrive.

WidgetCatalogMonitor observes package/component and availability broadcasts while
the picker is started, coalescing bursts for 250 ms. The picker also reloads on
resume and rejects superseded background loads. Preview cache generations prevent
old package images from being republished after invalidation. No second live widget
host is created. The editor's saved-app menu can open a package-filtered picker;
provider selection returns to the existing bind/configure flow.

RecoveringWidgetHostView contains synchronous apply, measure and layout exceptions
and framework error-view callbacks, including asynchronous inflation errors. The
dashboard page owns a separate fallback with a label and Open app action. Failed
provider children remain attached for framework reapply, but are hidden and skip
measure/layout until a new non-null payload retries them. Notifications are posted
outside layout and guarded by update generation; teardown cancels them. Other
pages, bindings and real-payload capture for the native relay are retained.
Recovery payloads apply synchronously before the failing tree can become visible
again, then restore the bounded executor for subsequent normal updates.

WidgetHelper skips auto-advance for failed views. Fallback taps share the existing
validated provider-app launcher, including saved membership, binding/profile and
cover-origin/display-policy checks. This handles widget rendering failures; it
does not promise containment of arbitrary provider drawing, process or system failures.

## App shortcuts and setup in 0.6.0

AppShortcutStore holds up to four ordered component/label pairs in separate
preferences, shared across native cover instances and the dashboard. It never
allocates or deletes hosted widgets. AppShortcutPickerActivity edits a draft;
Save commits it and Cancel leaves the saved selection intact. Its search uses
the existing app picker adapter. Saved editor rows offer move/remove actions.

AppShortcutViews builds native RemoteViews tiles and dashboard tiles. Native
tiles use immutable explicit activity pending intents with unique data URIs.
CoverLaunchRouterActivity validates the outer widget and current saved selection
before resolving an app. Dashboard taps check the same saved selection. Existing
cover-origin and Samsung display-policy checks apply to both paths.

ShortcutCatalogApplication observes personal-profile LauncherApps callbacks and
coalesces events for 250 ms. It reconciles again on process start. App removal,
disablement or suspension preserves saved order and labels; unavailable slots
remain editable. Catalog callbacks run only while the process exists; a native
widget refresh also resolves the current icon and availability.

CoverSetupState derives readiness from current widget IDs, available saved apps,
available hosted widgets, actual service state and pending widget setup. A widget
ID establishes an instance, not Samsung cover placement. The checklist tells the
user to confirm cover placement in Settings. App-only use needs no relay service.
Service/surface changes and resume refresh the checklist. Last launch failures
are stored separately, can be dismissed, and clear after a successful launch.
Neither a ready app entry nor a widget ID asserts Good Lock allow-list access.

## Update and display handling in 0.5.2

WidgetUpdateQueue keeps the latest pending payload per hosted ID, starts after
100 ms, and processes one item per tick with 16 ms between items. Replacements
keep the initial deadline. Completion can publish only while its ticket is
current; removal, restoration cleanup and provider replacement invalidate it.
The native inflation probe stays on the UI thread. Its work is coalesced before
inflation, separately from the existing 500 ms native publication delay.

WidgetHelper gives dashboard host views an executor with two workers and a
24-task queue. Framework inflation cancellation remains in AppWidgetHostView.
At saturation, work runs on the submitting thread rather than being dropped.
Teardown cancels view work and shuts down the pool. The relay service retains
capture-only views and does not inflate a second live provider widget.

WidgetDisplayMonitor debounces public display/configuration events. The service
compares snapshots of display identity, dimensions, density and Configuration,
then stops its own listening and replaces its display context and host when
needed. The dashboard refreshes its own manager/host and resumes its ownership
on configuration changes. Host 1024 and completed widget bindings are retained.
The native list factory refreshes its context on each dataset update, and size
hints are constrained by current cover bounds while host options catch up.

WidgetSizing handles provider pixel dimensions, valid resize limits, host
padding and surface bounds for both native cards and dashboard pages. Fixed
widgets use their provider height. Native rows may scroll up to the existing
1200 dp cap; a dashboard widget must fit its viewport. Unsupported sizes show
a fallback without publishing undersized hints. Last raw payloads remain in
memory for reclassification when the display changes, including failed probes.

## Native cover collection

FlipLauncherWidgetProvider publishes widget_native_cover, containing a native
vertical ListView. The provider component and Samsung sub-screen metadata
remain stable. NativeWidgetListService supplies one stable-ID row per saved
hosted widget. Its factory verifies that the outer ID belongs to FlipLauncher
and uses a cover display context when that display is accessible.

NativeWidgetRelay captures provider RemoteViews through public host-view update
callbacks. An unattached native inflation probe classifies layouts. AdapterView
collections and layouts that cannot inflate get Open fallbacks. Compatible
payloads are copied into rows, with wrapper actions preceding insertion to
avoid resource-ID collisions between packages. Payloads remain in process
memory; raw third-party widget data is not serialized to disk.

Android propagates collection-child flags to nested RemoteViews, restricting
direct provider PendingIntent actions. An explicit mutable template targets CoverLaunchRouterActivity. Row fill-in
intents carry the hosted ID; the router validates both the outer FlipLauncher
widget and the saved hosted ID, resolves the provider package from the binding,
and launches its main launcher activity on the cover. Cross-profile bindings
are not routed to a personal-profile app. The brief router finishes without
opening the dashboard. All controls remains a separate dashboard action.
Individual provider PendingIntent deep links are not reconstructed.

Our own RemoteViewsService is protected by BIND_REMOTEVIEWS. Using a service
avoids submitting all provider images in one large collection update. Native
updates notify the collection. Stable IDs and an unchanged adapter URI allow
the host to preserve list position; no forced scrolling is applied.

## Live updates and host ownership

Start updates from the native page or phone editor. The native action uses an
explicit foreground-service PendingIntent, so a user tap starts the service
without opening an activity. Background update broadcasts only publish views.

NativeWidgetRelayService uses a specialUse foreground-service declaration and
subtype explanation. It submits a low-priority ongoing notification and exposes
Stop controls in the native page, editor and notification. Android may hide the
drawer notification without notification permission; its active-app UI still
exposes the service. The service remains enabled until stopped or its native
surface/board is removed. START_STICKY handles ordinary process restarts;
force-stop requires reopening and starting updates again.

Android registers callbacks by package and host ID. Both service and dashboard
retain host 1024, with a process-wide ownership coordinator:

1. The service stops listening before the dashboard starts.
2. Dashboard host views also capture provider updates for the native page.
3. When the dashboard stops, the service recreates headless listeners, fetches
   current payloads and resumes listening.
4. A paused service never stops the dashboard's callback.

The phone editor never listens. Teardown never deletes the host or completed
bindings. Removing the native surface keeps the board. Explicit widget removal
deletes that hosted ID. Host-ID restoration invalidates old relay IDs and
refreshes subscriptions.

Native hints use outer dimensions and provider metadata. Fixed-height widgets
use their minimum height. Resizable widgets use the saved size fraction within
provider bounds. Native and dashboard sizing are republished on ownership
changes. Null temporary payloads do not overwrite a last valid relay value.

## Dashboard and setup

The native ComponentActivity/theme from 0.4.1 avoids AppCompat substitutions in
live widget inflation. The full dashboard retains horizontal paging and
foreground-only auto-advance. Good Lock allows the activity on the cover.

The editor persists allocate → bind → configure-ready → configure → commit.
Binding/configuration wait for a resumed, focused editor. Configuration uses
the public system-mediated host API and Android 14+ sender options. Optional
setup is skipped only when both optional and reconfigurable flags are set.
New-setup cancellation deletes that allocation; reconfiguration cancellation
preserves the existing widget.

## Build and diagnostics

Use JDK 17, Gradle 9.6.0, Android Gradle Plugin 9.4.0, platform 36 and build
tools 36.0.0. Kotlin compilation uses Android Gradle Plugin's built-in Kotlin
support. Dependency versions are pinned in the Gradle version catalog.

```sh
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s FlipLauncherNative AppWidgetHostView RemoteViews FlipLauncherWidgets ActivityTaskManager
```

All 58 Robolectric unit tests pass. New coverage exercises grouped picker search,
selection, recreation, provider refresh and per-app discovery, plus real framework
RemoteViews recovery, stale failure notifications and fallback launches. Existing
coverage includes shortcut save/cancel/recreation,
catalog unavailability, native intents and launch validation, setup refresh,
widget-state migrations, pending setup, burst coalescing, stale results, provider
replacement, size constraints, display events, and host ownership during context replacement.
Lint and the debug build pass. The relay still needs device confirmation.
Image URI permissions, provider layouts or Samsung host behavior
may prevent native rendering. Nested collections, widget-specific auto-advance
and complex controls remain in the dashboard. Other app launches still obey
Samsung's policy. A successful build does not establish One UI compatibility.

## Device feedback

The user confirmed 0.5.0 native rendering works. Version 0.5.1 changes card taps
to app launch; that route still needs phone confirmation. Samsung's enabled-app
policy is retained. Missing launcher apps show a message suggesting All controls.
