package net.fliver.fl.util;

import java.io.File;
import java.lang.management.ManagementFactory;
import org.bukkit.Bukkit;

/** Disk and host RAM metrics for .fl endpoint scripts. */
public final class ServerMetrics {
  private static final double BYTES_PER_GB = 1073741824.0;

  private ServerMetrics() {}

  public static double totalPhysicalMemoryGb() {
    return bytesToGb(totalPhysicalMemoryBytes());
  }

  public static double serverDiskUsageGb() {
    return bytesToGb(serverDiskUsageBytes());
  }

  static long totalPhysicalMemoryBytes() {
    try {
      java.lang.management.OperatingSystemMXBean bean =
          ManagementFactory.getOperatingSystemMXBean();
      if (bean instanceof com.sun.management.OperatingSystemMXBean) {
        long total = ((com.sun.management.OperatingSystemMXBean) bean).getTotalPhysicalMemorySize();
        return total > 0 ? total : 0L;
      }
    } catch (Throwable ignored) {
      // Bean unavailable on this JVM — return 0.
    }
    return 0L;
  }

  static long serverDiskUsageBytes() {
    File root = Bukkit.getWorldContainer();
    if (root == null) return 0L;
    return directorySize(root);
  }

  private static long directorySize(File file) {
    if (file == null || !file.exists()) return 0L;

    if (file.isFile()) {
      long length = file.length();
      return length > 0 ? length : 0L;
    }

    if (!file.isDirectory()) return 0L;

    File[] children = file.listFiles();
    if (children == null) return 0L;

    long total = 0L;
    for (File child : children) {
      total += directorySize(child);
    }
    return total;
  }

  private static double bytesToGb(long bytes) {
    if (bytes <= 0) return 0.0;
    return bytes / BYTES_PER_GB;
  }
}
