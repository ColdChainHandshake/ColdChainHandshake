# R8 rules for the release build (5.6). Libraries that ship their own rules (kotlinx-serialization 1.11, coroutines,
# Room, Hilt, WorkManager, Compose, Play services) need nothing here.

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class * {
    @androidx.room.* *;
}

# supabase-kt (3.8.0) and Ktor (3.5.1) ship no R8 rules (their jars checked on 27 Nov). Ktor finds its HTTP engine
# through ServiceLoader, and supabase-kt looks its plugins and serializers up at run time, so both are kept whole.
-keep class io.ktor.client.engine.** { *; }
-keep class io.github.jan.supabase.** { *; }

# firebase-components 16.1.0 (pulled in by ML Kit barcode-scanning 17.3.0) keeps each ComponentRegistrar but not its
# no-argument constructor, which R8's full mode drops; component discovery builds registrars with it by reflection.
# Without this the QR scanner's ML Kit can't start in release. `checkReleaseKeepRules` in build.gradle guards it.
-keepclassmembers class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
