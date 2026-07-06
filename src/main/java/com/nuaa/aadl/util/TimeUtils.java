package com.nuaa.aadl.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class TimeUtils {

  private TimeUtils() {
  }

  public static String utcNow() {
    return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
  }
}
