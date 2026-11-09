# Isolated Navigation overlay diagnostic

This framework-only Android APK tests whether the emulator can render two `TYPE_APPLICATION_OVERLAY` windows above the already-open native Navigation app. It is separate from the Petasos product, is not an implementation of product ON, and does not establish production permission UX or driving-policy approval.

## Scope

- Package `com.petasos.diagnostics.overlay`; sole permission `SYSTEM_ALERT_WINDOW`.
- No location, microphone, network, storage, service, foreground service, SDK dependency, navigation discovery, or product-data access.
- One fixed circular P input button and one fixed public-text card, confined to a caller-observed map rectangle. The underlying map is not embedded or recreated.
- Both windows avoid focus; the card passes touch through with opacity below the documented Android default obscuring limit. The button only changes a fixed public status label; it never starts speech or playback.
- The Activity sends its task behind the existing Navigation app. No process-lifetime guarantee is claimed; if Android destroys the Activity or process, its windows disappear. While alive, the main-loop timer removes both windows after 60 seconds. A repeated launch closes the existing diagnostic, rather than extending that timer.
- Fixed status codes only. `WINDOWS_ATTACHED` and `VIEW_TREES_SHOWN` describe the app's View tree, **not** proof that the system compositor displayed the windows. A screenshot or human observation must verify actual simultaneous display.
- Missing permission or map bounds exits without changing app-ops or opening settings. The APK cannot grant its own special access.

## Build

From the project root:

```sh
bash diagnostics/navigation-overlay/build.sh
```

Uses the existing local JDK17, SDK36 platform, build-tools35, and project debug key. Java compilation, dexing, and signing use maximum 256 MiB heaps. No Gradle or download. Output: `.local/diagnostics/navigation-overlay/navigation-overlay-diagnostic.apk`.

## Controlled emulator procedure

The supervising developer must first identify the emulator and current Android user, inspect and save the diagnostic package's prior special-access state, and observe the actual Navigation map bounds. The operator may separately enable temporary emulator-only special access for this diagnostic and must restore its prior state afterward; **this APK and build script never do that**. Do not change the Petasos product package's privileges.

1. Install this distinct APK on the selected emulator. Keep the native Navigation map open and parked.
2. Launch its explicit Activity, providing all four integer extras in **absolute display pixels**: `map_x`, `map_y`, `map_width`, `map_height`. The caller is responsible for excluding the Pleos Driving view, system controls, and other reserved areas. Values are clipped to display bounds; rectangles narrower than 400dp or shorter than 320dp are rejected.
3. Inspect only the `PetasosOverlayDiag` log tag. In the no-permission case expect `PERMISSION_ABSENT` and immediate exit. In the permitted case expect `WINDOWS_ADDED`, `WINDOWS_ATTACHED`, and `VIEW_TREES_SHOWN`; capture visual evidence of the native map and diagnostic windows together.
4. Optionally tap the P button once to observe `BUTTON_TOUCHED` and its fixed status label. Test a map point outside the button/card to verify the window is not a full-screen input blocker.
5. At 60 seconds expect `TIMEOUT_CLOSED` and both windows gone. Force-stop this diagnostic package if it does not close or when ending early. Restore the saved special-access state; remove the diagnostic package when no longer needed.

Example explicit launch shape (replace every placeholder from observations; do not use invented map bounds):

```text
adb -s SERIAL shell am start --user USER \
  -n com.petasos.diagnostics.overlay/.OverlayDiagnosticActivity \
  --ei map_x X --ei map_y Y --ei map_width WIDTH --ei map_height HEIGHT
```

Official contracts: [Android overlay windows](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY), [special access](https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context)), [touch-through restrictions](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE), [window context](https://developer.android.com/reference/android/content/Context#createWindowContext(int,%20android.os.Bundle)).
