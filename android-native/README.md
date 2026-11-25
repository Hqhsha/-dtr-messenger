# DTR Native Android

Standalone Android client built with Kotlin + Jetpack Compose. It talks to the existing DTR HTTPS API and Socket.IO server; it does not create a new database or duplicate accounts.

## Current native preview
- Login and registration using existing DTR accounts.
- Loads the existing chat list and message history.
- Sends text messages through the existing API.
- Receives realtime messages over the existing Socket.IO event `chat:message`.
- Stores the session token in Android app-private SharedPreferences.

## Build
Run from this directory with Gradle 8.9 and JDK 17:

```bash
gradle assembleDebug
```

The release APK is built by the `DTR Native Android` GitHub Actions workflow.

## Planned native milestones
1. Native media picker, camera capture, voice recording and upload.
2. WebRTC calling with a production TURN service and native call UI.
3. Push notifications, notification channels and background delivery.
4. Port the remaining DTR screens (contacts, groups, profile, feed, settings and support).
