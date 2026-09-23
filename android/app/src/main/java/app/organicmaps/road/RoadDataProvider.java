package app.organicmaps.road;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Each source owns its protocol and importer; storage and navigation consume normalized events. */
public interface RoadDataProvider
{
  class AuthenticationException extends IOException
  {
    public AuthenticationException()
    {
      super("Source authentication required");
    }
  }
  final class SessionExpiredException extends AuthenticationException
  {
    public SessionExpiredException()
    {
      super();
    }
  }
  String id();
  RoadEventImporter newImporter();
  void login(String login, String password) throws IOException;
  void logout();
  List<String> countries() throws IOException;
  void download(String country, File destination) throws IOException;
  default void cancel() {}
}
