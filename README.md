# Tesla Garmin companion (Android phone) — PERSONAL BUILD, CONTAINS SECRETS. Never publish this APK.
Build: JAVA_HOME=/home/box/sdk/jdk-17.0.21+9 ./gradlew assembleRelease -PseedFromKeys=true
Seeds from ~/tesla-keys (client-id.txt, vin.txt, private-key.pem, public-key.pem) and the token file:
  ~/tesla-keys/garmin-user-tokens.json  (preferred: SEPARATE login → own refresh-token chain)
  else user-tokens.json (same as Wear app → the two apps rotate the single-use refresh token and
  the loser gets 401/"login_required"). The app shows a red warning when built this way and has a
  "새 refresh token" field to paste a separate token at runtime.
Runs a minimal-priority foreground service (specialUse) started on app open, boot and update.
Signed with the box debug keystore (same as the Wear app).
