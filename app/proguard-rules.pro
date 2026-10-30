# Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable

# WorkManager stores its jobs in a Room database whose generated *_Impl class is created by reflection.
# Without this, R8 strips its constructor and the app crashes before it opens (the 9.0/9.1 bug).
-keep class * extends androidx.room.RoomDatabase { <init>(...); }
-keep class androidx.work.impl.** { *; }
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# Services, receivers and the Application class named in the manifest are kept by the build itself (AAPT2's rules).
