
# Pleos Playground SDKs bind to system services through AIDL / reflection — keep them whole (unverified on hardware, stay safe).
-keep class ai.pleos.** { *; }
-dontwarn ai.pleos.**
# AAOS car library is optional at runtime (uses-library required=false)
-dontwarn android.car.**
