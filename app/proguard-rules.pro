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
