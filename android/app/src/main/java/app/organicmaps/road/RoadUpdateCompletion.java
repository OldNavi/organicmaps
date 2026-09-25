package app.organicmaps.road;

import java.util.concurrent.FutureTask;

/** Callback-completed result with timed waiting, including Android versions before API 24. */
final class RoadUpdateCompletion extends FutureTask<RoadDataManager.UpdateResult>
{
  RoadUpdateCompletion()
  {
    super(() -> RoadDataManager.UpdateResult.SKIPPED);
  }

  void complete(RoadDataManager.UpdateResult result)
  {
    set(result);
  }
}
