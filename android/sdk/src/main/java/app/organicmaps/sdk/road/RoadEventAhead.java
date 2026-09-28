package app.organicmaps.sdk.road;

import androidx.annotation.Keep;

/** An event on the matched road whose approach sector contains the current location. */
@Keep
public record RoadEventAhead(String identity, int kind, double distanceMeters, double speedMps, double latitude,
                             double longitude)
{}
