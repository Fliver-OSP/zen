package net.fliver.fl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PathPatternTest {
  @Test
  void validTemplateAcceptsParams() {
    assertTrue(PathPattern.isValidTemplate("bans/{player}"));
    assertTrue(PathPattern.isValidTemplate("status"));
  }
}
