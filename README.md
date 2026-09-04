# HomeNet Agent

HomeNet monitors per-device traffic from a TP-Link TL-WR840N on the local network,
stores readings on the phone, syncs them to the HomeNet dashboard, and executes
safe queued internet access commands.

## Android v0.5.0 background setup

1. Open the app and link the HomeNet dashboard account.
2. Enter the router address, username, and password. The router password is encrypted
   with Android Keystore and remains on the phone.
3. Tap **تشغيل المراقبة بالخلفية** and allow notifications.
4. Tap **إعداد البطارية لهاتف Samsung**, allow the battery exception, and set the app
   battery mode to **Unrestricted / غير مقيّد**. Remove it from Sleeping and Deep sleeping apps.

The foreground-service notification must remain visible. Swiping the app from Recents
does not disable monitoring. Android Force Stop intentionally disables all app work until
the user launches the app again.

Readings remain queued locally when the internet is unavailable. Router commands are
returned to the cloud queue and retried after the router becomes reachable again.
