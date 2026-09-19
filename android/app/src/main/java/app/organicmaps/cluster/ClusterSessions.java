package app.organicmaps.cluster;

import app.organicmaps.sdk.cluster.ClusterZoom;
import java.util.HashMap;
import java.util.Map;

/** Main-thread connection ownership; request identity also cancels delayed initialization. */
final class ClusterSessions
{
  static final class Request
  {
    final int uid;
    final int displayId;
    final int zoom;
    Request(int owner, int display, int level)
    {
      uid = owner;
      displayId = display;
      zoom = level;
    }
  }

  private final Map<Integer, Request> mRequests = new HashMap<>();

  Request connect(int uid, int displayId, int zoom)
  {
    if (uid < 0 || displayId <= 0 || !ClusterZoom.isValid(zoom))
      throw new IllegalArgumentException("Invalid cluster connection");
    Request previous = mRequests.get(displayId);
    if (previous != null && previous.uid != uid)
      throw new SecurityException("Cluster display belongs to another client");
    Request request = new Request(uid, displayId, zoom);
    mRequests.put(displayId, request);
    return request;
  }

  boolean disconnect(int uid, int displayId)
  {
    Request request = mRequests.get(displayId);
    if (request == null || request.uid != uid)
      return false;
    mRequests.remove(displayId);
    return true;
  }

  boolean isCurrent(Request request)
  {
    return mRequests.get(request.displayId) == request;
  }
  boolean removeDisplay(int displayId)
  {
    return mRequests.remove(displayId) != null;
  }
  boolean isEmpty()
  {
    return mRequests.isEmpty();
  }
  void clear()
  {
    mRequests.clear();
  }
}
