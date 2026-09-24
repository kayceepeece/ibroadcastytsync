# YT Sync for iBroadcast rules — keep all pipeline interfaces for spikes.
-keep class ibytsync.core.** { *; }

# Google ErrorProne compile-time annotations referenced by Tink / EncryptedSharedPreferences
-dontwarn com.google.errorprone.annotations.**
