# Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable

# WorkManager stores its jobs in a Room database whose generated *_Impl class is created by reflection.
# Without this, R8 strips its constructor and the app crashes before it opens (the 9.0/9.1 bug).
-keep class * extends androidx.room.RoomDatabase { <init>(...); }
-keep class androidx.work.impl.** { *; }
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# Classes the manifest or system creates by name.
-keep class com.example.walletlookup.** extends android.app.Service { *; }
-keep class com.example.walletlookup.** extends android.content.BroadcastReceiver { *; }
-keep class com.example.walletlookup.BatApp { *; }
