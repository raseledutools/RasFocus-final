# ==============================================================================
# RasFocus ProGuard Rules — Crash-safe + Optimized
# ==============================================================================

# -- Readable stack traces -----------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,*Annotation*
-renamesourcefileattribute SourceFile

# -- R8 optimization -----------------------------------------------------------
-optimizationpasses 3
-allowaccessmodification

# -- RasFocus: Keep all class/method (crash-safe) ------------------------------
-keep class com.rasel.RasFocus.** { *; }

# -- Kotlin --------------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# -- Coroutines ----------------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}

# -- Firebase ------------------------------------------------------------------
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# -- Credential Manager / Google Sign-In ---------------------------------------
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn androidx.credentials.**

# -- ML Kit Document Scanner ---------------------------------------------------
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# -- Cloudinary ----------------------------------------------------------------
-keep class com.cloudinary.** { *; }
-dontwarn com.cloudinary.**

# -- PDFBox --------------------------------------------------------------------
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.**

# -- Coil ----------------------------------------------------------------------
-dontwarn coil.**

# -- OkHttp --------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**

# -- Gson ----------------------------------------------------------------------
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.stream.** { *; }
-keep class com.google.gson.examples.android.model.** { *; }
-keep class com.google.gson.** { *; }

# -- Room ----------------------------------------------------------------------
-dontwarn androidx.room.**
-keep class androidx.room.** { *; }

# -- Navigation Component ------------------------------------------------------
-keep class androidx.navigation.** { *; }
-keepnames class androidx.navigation.compose.** { *; }
-keepnames class androidx.navigation.NavBackStackEntry { *; }

# -- Jetpack Compose -----------------------------------------------------------
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# -- AndroidX Lifecycle & ViewModels -------------------------------------------
-keep class androidx.lifecycle.** { *; }
-keep class androidx.lifecycle.ViewModel { *; }

# -- Android essentials --------------------------------------------------------
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# -- Google API Client & Drive SDK ---------------------------------------------
-keep class com.google.api.** { *; }
-keep class com.google.api.services.drive.** { *; }
-dontwarn com.google.api.**

# -- smbj (SMB/CIFS client) ----------------------------------------------------
# smbj uses SPNEGO/Kerberos (org.ietf.jgss) for optional auth — not available
# on Android. We only use NTLM/anonymous auth, so these are safe to ignore.
-keep class com.hierynomus.smbj.** { *; }
-keep class com.hierynomus.msfscc.** { *; }
-keep class com.hierynomus.msdtyp.** { *; }
-keep class com.hierynomus.mssmb2.** { *; }
-keep class com.hierynomus.protocol.** { *; }
-dontwarn com.hierynomus.**
-dontwarn org.ietf.jgss.**

# -- net.engio mbassy (event bus used by smbj) ---------------------------------
# The EL (Expression Language) filter in mbassy references javax.el which is
# not present on Android. We never use annotation-based EL filtering.
-keep class net.engio.mbassy.** { *; }
-dontwarn net.engio.mbassy.**
-dontwarn javax.el.**

# -- Apache FTPServer & MINA (FTP server) -------------------------------------
-keep class org.apache.ftpserver.** { *; }
-keep class org.apache.mina.** { *; }
-dontwarn org.apache.ftpserver.**
-dontwarn org.apache.mina.**
-dontwarn org.apache.commons.net.**

# -- SLF4J (logging facade used by FTPServer and smbj) ------------------------
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# -- BouncyCastle (pulled by smbj / google-api-client) ------------------------
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# -- pdfiumandroid (native PDF renderer) --------------------------------------
-keep class io.legere.pdfiumandroid.** { *; }
-dontwarn io.legere.pdfiumandroid.**

# -- Google Play Services location (internal GMS class warning) ---------------
-dontwarn com.google.android.gms.internal.location.**
