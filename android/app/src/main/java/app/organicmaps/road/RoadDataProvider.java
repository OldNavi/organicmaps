package app.organicmaps.road;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Each source owns its protocol and importer; storage and navigation consume normalized events. */
public interface RoadDataProvider
{
  final class SessionExpiredException extends IOException
  {
    public SessionExpiredException()
    {
      super("Source session expired");
    }
  }
  String id();
  RoadEventImporter newImporter();
  void login(String login, String password) throws IOException;
  void logout();
  List<String> countries() throws IOException;
  void download(String country, File destination) throws IOException;
}
