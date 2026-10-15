# FlipLauncher 0.7.0

**Pinned apps and a vertically scrolling widget page on the Galaxy Z Flip5 native cover screen.**
Target: Galaxy Z Flip5 / One UI 8.5.

Choose up to four apps for a shortcut strip above your widgets on both cover
surfaces. Compatible widgets show their content directly in Samsung's FlipLauncher
cover page. A setup checklist in the phone editor shows widget placement,
available content and live-update status, with guidance for failed app launches.

## Install and use

1. Install `FlipLauncher-0.7.0-debug.apk` over the earlier workspace APK.
   Existing hosted widget bindings, order and sizes are retained.
2. Open FlipLauncher on the main screen. Tap **Choose apps**, select up to four,
   and tap **Save shortcuts**. Tap a saved app for widget discovery or order/removal options.
   Tap **Add widget**, then an app heading to see its widget variants. Search opens matching groups.
3. Enable **FlipLauncher** under **Settings → Cover screen → Widgets**.
   If an existing cover instance keeps the old shortcut layout, disable and
   re-enable that cover instance; your separately hosted widgets are retained.
4. Fold the phone and swipe to FlipLauncher. App shortcuts work immediately;
   **Start cover updates** is only needed for widget content.
   You can also start updates from the phone editor.
5. Tap an app shortcut to launch it, or scroll vertically through widgets.
   Tap a widget card or **Open app** to launch its app directly.

## Changes in 0.7.0

- The widget picker groups variants under expandable app headings. Searching an
  app name shows its widgets; searching a widget name shows matching variants.
- The picker refreshes on resume and live package/component changes, retaining
  the query and expanded apps. Stable row identities and list diffs preserve list
  state. **Widgets from this app** opens a filtered picker from a saved app's menu.
- Dashboard inflation, measurement and layout failures show a local fallback
  with the widget name and **Open app**. A new provider payload retries rendering;
  bindings, saved order and the native relay are retained.
- Fallback app launches keep the existing binding, profile and cover-policy checks.
  No new permissions are required.

## Changes in 0.6.0

- Up to four shared, ordered app shortcuts appear above native widgets and in
  the full dashboard. The searchable picker saves only when confirmed.
- Shortcuts use explicit immutable pending intents and the existing validated
  router. Removed selections and unknown widget instances cannot launch apps.
- Missing, disabled or suspended apps retain their saved slot and label and
  show as unavailable. Live catalog events are coalesced while the process is
  running; the catalog is reconciled again on process start.
- The editor scrolls as one page and includes a live setup checklist. Its
  widget-added state detects a FlipLauncher instance; cover placement still
  needs confirmation under Settings → Cover screen → Widgets.
- The checklist distinguishes app-only use from stopped widget updates, keeps
  Stop available during setup, and shows actual launch failures with cover guidance.
- No additional permissions are required. Existing widget bindings, order and
  sizes are retained. Samsung's app-launch policy still applies.

For full controls on the cover, keep FlipLauncher enabled in **Good Lock →
MultiStar → I ♡ Galaxy Foldable → Launcher Widget**. Samsung's app policy still
applies to activity launches. **All controls** opens the existing dashboard.

## Changes in 0.5.2

- Only the latest pending update for each widget is classified; queued work for
  removed or replaced widgets cannot publish stale content.
- Dashboard widget inflation uses a bounded worker pool. Native classification
  stays on the UI thread, one payload per queue tick after burst coalescing.
- The update service watches display changes and recreates its host/context
  when the cover becomes available or its configuration changes. The dashboard
  refreshes its host on configuration changes while preserving callback ownership.
- Both surfaces share sizing rules. Fixed-height widgets keep their provider
  height; resizable widgets honor valid bounds. Widgets that cannot fit show an
  explanation rather than receiving unsupported smaller size hints.

The native page can scroll taller widgets. The dashboard's single-page viewport
shows a fit explanation for widgets that exceed its available height.

## Changes retained from 0.5.1

Native card taps now launch the widget provider's app through a brief transparent
router instead of opening the FlipLauncher dashboard. The provider package is
resolved from the validated widget binding. Samsung's enabled-app policy applies;
apps not enabled for the cover may be blocked or ask to unfold. The **All
controls** button still opens the full interactive widgets.

## Native page behavior

- A native ListView shows cards in the order saved in the phone editor.
- Compatible provider RemoteViews are relayed as content, not screenshots.
- An update service receives provider changes while the dashboard is closed.
  **Stop cover updates** is available on the native page and in the editor.
- Supported text clocks can run in the native host; other content updates when
  the original provider supplies an update.
- Android restricts direct provider button actions inside collection rows.
  Tapping a native card opens its provider app directly on the cover.
  This opens the app's launcher screen rather than reproducing individual widget
  actions such as a microphone or a particular calendar event.
- Widgets with nested scrolling collections or unsupported layouts show an
  explanatory **Open app** card. Native rendering is not guaranteed for every
  third-party provider, including Search, Stocks and Photos.

The update service uses Android's foreground-service mechanism and submits a
low-priority ongoing notification. Without notification permission, Android
may show it only in the system's active-app controls. The native page and phone
editor always provide an explicit stop control. After force-stopping the app,
reopen it and start updates again.

## Phone editor and full dashboard

- Search installed home widgets and finish Android's binding/setup prompts.
- Manage up to 12 widgets, drag to reorder, configure or explicitly remove them.
- Vertically resizable widgets offer Compact, Medium and Full page sizes.
- The full dashboard retains interactive widget pages and horizontal swiping.
- The service and dashboard hand off their shared widget-host callback.
- The native inflater and focused configuration flow from 0.4.1 are retained.

## Build

Use JDK 17, SDK platform 36 and build tools 36.0.0. The wrapper uses Gradle
9.6.0 and Android Gradle Plugin 9.4.0. Android Gradle Plugin's built-in Kotlin
support compiles the app's Kotlin sources.

```sh
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

All 58 Robolectric unit tests pass, including grouped picker search and recreation,
live provider changes, dashboard layout/measure failures, asynchronous inflation
recovery and fallback launch validation, as well as shortcut selection and recreation,
native shortcut intents, stale launch rejection, setup status, update bursts,
provider replacement, size constraints and display/host ownership transitions.
Lint and the debug APK build pass. No Flip5 or emulator is connected here.
Native collection rendering, fold/unfold behavior, service restarts, lock-screen
behavior and provider compatibility still need phone confirmation.

## Documentation

- [App architecture and build details](app/README.md)

## License

FlipLauncher is licensed under the [Apache License 2.0](LICENSE).
Samsung, Galaxy, One UI and Good Lock are trademarks of Samsung Electronics;
this project is not affiliated with or endorsed by Samsung.
