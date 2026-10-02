# kotlinx.serialization keeps generated serializers via its own consumer rules.
# Keep protocol models' names stable for readable crash reports.
-keepnames class io.github.aspershupadhyay.latch.protocol.** { *; }
