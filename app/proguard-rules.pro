# Room, Compose, AndroidX and kotlinx.serialization ship their own consumer rules.
# kotlinx.serialization is used through JsonElement only (no generated serializers), so no keep rules are needed.

# Keep OkHttp, Okio and the :net module whole. The release gate's network_api set is what the shipped dex references;
# with these keeps it is the fixed set OkHttp 5.1.0 itself references, and changes only with the OkHttp version, not
# with whatever unrelated code R8 happens to shrink or inline. :net is the only module that names a network API.
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class app.discoredact.net.** { *; }

# OkHttp's optional TLS providers are not shipped (the platform's own TLS is used). Keeping all of OkHttp makes R8 see
# the references to them; without these lines they would be reported as missing classes.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
