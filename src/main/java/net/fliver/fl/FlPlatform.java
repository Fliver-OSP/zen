package net.fliver.fl;

import org.bukkit.entity.Player;

/** Host scheduler hooks for player mutations (Folia-safe when implemented by the host). */
public interface FlPlatform {
  void runForEntity(Player player, Runnable action);

  void runSync(Runnable action);
}
