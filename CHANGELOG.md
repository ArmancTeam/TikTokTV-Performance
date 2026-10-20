# Changelog

## 1.3 (versionCode 4)

### Added
- In-app voice search: menu button (and the remote's SEARCH / mic key when delivered to the app). Live overlay with partial results, level-reactive microphone, OK to finish, BACK to cancel; video is paused while listening. Falls back to the system voice dialog when the device has no recognition service. Adds `RECORD_AUDIO` (optional hardware) and `<queries>` for Android 11+.
- Voice search understands sentences (`VoiceQuery`: drops words like "videos / göster / show me / muéstrame"), searches the Videos tab and opens the first result automatically (setting). The remote's ASSIST key is handled too.
- Virtual mouse: real mouse hover events, so TikTok's hover UI (video controls, progress bar, share menu) works.
- Setting: edge-scrolling for the virtual mouse (off by default).
- Clean screen: green remote key / menu button hides every TikTok overlay and leaves only the video (generic sibling-hiding, no dependency on TikTok class names). BACK or → leaves it.
- Red remote key (`KEYCODE_PROG_RED`): tap toggles the virtual mouse (with on-screen confirmation), hold opens the settings (mouse speed, edge scroll, ...).

### Fixed
- Virtual mouse: edge-scrolling fired as soon as the cursor touched the top/bottom 3% of the screen and switched videos while reaching for the progress bar or top icons. It is now opt-in and requires the cursor to stay pinned on the edge (~0.45 s).
- Virtual mouse: holding OK for 0.8 s while it was on turned it off (broke click-and-hold). Turning it off now takes 1.5 s.

## 1.2 (versionCode 3)

### Fixed
- Two launcher tiles on the TV home screen (the `activity-alias` also declared LAUNCHER). Now a single "TikTok" entry.
- Voice search intents were declared but never handled; they now open TikTok search (`launchMode=singleTask` + `onNewIntent`).
- Audio kept playing after pressing Home: the page is paused in `onPause`, resumed in `onResume`; cookies are flushed.
- WebView renderer crash no longer kills the app (`onRenderProcessGone`, with crash-loop guard).
- Error screen: distinguishes offline from other failures, handles HTTP 5xx, reloads automatically when the network returns.
- Holding BACK no longer exits the app; BACK also works on Android 16 (`OnBackPressedCallback`, `enableOnBackInvokedCallback`).
- Volume keys were swallowed while the virtual mouse was on.
- `versionName` now matches the V1.2 release.

### Added
- Media keys (play/pause, seek ±10 s, previous/next video, CH ±, PgUp/PgDn); hold ←/→ to seek.
- Mouse mode: edge-scrolling and CH/PgUp/PgDn scrolling under the cursor.
- QR-code login, menu tabs (For You / Following / Explore / LIVE), mute toggle, on-screen display.
- Settings: auto-play next video, tracker blocking, H.264/AV1, start page, sign out / clear data.
- Sidebar buttons can be focused one by one with ↑/↓; focus outline for TV.
- Bridge self-test logged to logcat; fallback selectors for the active feed item.
- Strings extracted to `strings.xml` (en, tr, es).
- Unit tests for `UrlPolicy`, GitHub Actions workflow, optional release signing.

### Security
- Main-frame navigation restricted to TikTok / CDN / login-provider hosts; non-http(s) schemes blocked.
- Cleartext traffic off, file/content access off, Safe Browsing left on, mixed content in compatibility mode, `allowBackup=false`.

### Removed
- Dead code (Custom Tabs / external-browser login helpers, second WebView), unused `androidx.browser` and `androidx.webkit` entries, unused `ic_channel*` resources.

### Changed
- `MainActivity` split into `VirtualMouse`, `UrlPolicy`, `AppSettings`.
- Menu is scrollable and uses "TikTok TV" (was "TVTikTok").
