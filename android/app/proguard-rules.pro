# libsignal uses JNI callbacks into these classes.
-keep class org.signal.libsignal.** { *; }
# SQLCipher JNI.
-keep class net.zetetic.database.** { *; }
# kotlinx.serialization DTOs.
-keepclassmembers @kotlinx.serialization.Serializable class dev.whispr.** { *** Companion; *; }
# firebase-datatransport is excluded on purpose (Google delivery telemetry);
# FirebaseMessagingRegistrar still names its backend class.
-dontwarn com.google.firebase.datatransport.**
