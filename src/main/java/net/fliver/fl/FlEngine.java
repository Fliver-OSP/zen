package net.fliver.fl;

import java.io.File;
import net.fliver.fl.builtins.BuiltinSyntax;
import net.fliver.fl.storage.CsvStore;

/** Bootstrap for the open-source .fl scripting engine. */
public final class FlEngine {
  private static volatile FlPlatform platform;
  private static volatile boolean initialized;

  private FlEngine() {}

  public static synchronized void init(FlPlatform hostPlatform, File csvDirectory) {
    if (hostPlatform == null) {
      throw new IllegalArgumentException("FlPlatform is required");
    }
    platform = hostPlatform;
    if (csvDirectory != null) {
      CsvStore.init(csvDirectory);
    }
    BuiltinSyntax.ensureLoaded();
    initialized = true;
  }

  public static FlPlatform platform() {
    if (!initialized || platform == null) {
      throw new IllegalStateException("FlEngine.init() was not called");
    }
    return platform;
  }

  public static boolean isInitialized() {
    return initialized;
  }
}
