# Room, Compose, AndroidX and kotlinx.serialization ship their own consumer rules.
# kotlinx.serialization is used through JsonElement only (no generated serializers), so no keep rules are needed.

# Why the gate's network_api set used to flap: R8 shrinks OkHttp's code by reachability, and the set of
# java.net / javax.net.ssl types the surviving code references changed with unrelated edits (24 to 44 between M2 and
# M3). Keeping every member of OkHttp fixes that set to what OkHttp 5.1.0 itself references, so it changes only with
# the OkHttp version. allowobfuscation keeps R8 renaming and repackaging the classes as before, so the baseline does
# not list OkHttp's own types (keeping them by name listed 375 of them, websocket and cache code included).
-keep,allowobfuscation class okhttp3.** { *; }
-keep,allowobfuscation class app.discoredact.net.** { *; }

# OkHttp's optional TLS providers are not shipped (the platform's own TLS is used). With every OkHttp member kept, R8
# sees the references to them; without these lines they would be reported as missing classes.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
