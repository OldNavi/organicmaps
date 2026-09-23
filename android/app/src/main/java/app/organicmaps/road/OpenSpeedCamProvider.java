package app.organicmaps.road;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class OpenSpeedCamProvider implements RoadDataProvider
{
  private static final String BASE_URL = "https://openspeedcam.net";
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private static final long MAX_DOWNLOAD_BYTES = 128L * 1024 * 1024;
  private static final String[] TYPES = {"static_cam",         "control_cam_red",
                                         "speed_cam",          "traffic_control_ot",
                                         "video_control",      "dummy",
                                         "mobile_ambush",      "stationary_porst_dps",
                                         "railroad_crossing",  "begin_village",
                                         "end_village",        "crosswalk",
                                         "cautiouslychildren", "speed_limit",
                                         "rec_policeman",      "bad_road",
                                         "dang_turn",          "dang_intersection",
                                         "dang_other",         "overtaking_proh"};
  // Session cookies stay in memory; neither passwords nor sessions enter backups or diagnostic bundles.
  private final SessionCookies mCookies = new SessionCookies();
  private final OkHttpClient mClient = new OkHttpClient.Builder()
                                           .cookieJar(mCookies)
                                           .connectTimeout(20, TimeUnit.SECONDS)
                                           .readTimeout(120, TimeUnit.SECONDS)
                                           .callTimeout(180, TimeUnit.SECONDS)
                                           .followRedirects(false)
                                           .build();

  @Override
  public String id()
  {
    return "openspeedcam.net";
  }
  @Override
  public RoadEventImporter newImporter()
  {
    return new OpenSpeedCamImporter();
  }

  @Override
  public void login(String login, String password) throws IOException
  {
    logout();
    try
    {
      JSONObject payload = new JSONObject().put("login", login).put("password", password);
      try (Response response = execute(new Request.Builder()
                                           .url(BASE_URL + "/api/auth/login")
                                           .post(RequestBody.create(payload.toString(), JSON))
                                           .build()))
      {
        if (new JSONObject(readJson(response)).optJSONObject("user") == null)
          throw new IOException("Invalid login response");
      }
      countries(); // Verify the authenticated session against the protected endpoint.
    }
    catch (JSONException | IOException e)
    {
      logout();
      throw new IOException("Unable to sign in", e);
    }
  }

  @Override
  public void logout()
  {
    mCookies.clear();
  }

  @Override
  public List<String> countries() throws IOException
  {
    try (Response response = execute(new Request.Builder().url(BASE_URL + "/api/addresses/countries").build()))
    {
      JSONArray data = new JSONArray(readJson(response));
      List<String> result = new ArrayList<>();
      for (int i = 0; i < data.length(); ++i)
      {
        String code = data.getString(i);
        if (code.matches("[A-Z]{2}") && !result.contains(code))
          result.add(code);
      }
      if (result.isEmpty())
        throw new IOException("Empty country list");
      return result;
    }
    catch (JSONException e)
    {
      throw new IOException("Invalid country list", e);
    }
  }

  static JSONObject exportRequest(String country, LocalDate today) throws JSONException
  {
    if (!country.matches("[A-Z]{2}"))
      throw new IllegalArgumentException("Invalid country code");
    return new JSONObject()
        .put("format", "PocketGisPlus")
        .put("rating", 0)
        .put("country", new JSONArray().put(country))
        .put("types", new JSONArray(TYPES))
        .put("selectMiddlePoints", false)
        .put("withIdPoint", true)
        .put("extended", false)
        .put("saveToUserExport", false)
        .put("userExportName", "")
        .put("confirmed", today.minusYears(1).toString())
        .put("selectOnlyConfirmed", true);
  }

  @Override
  public void download(String country, File destination) throws IOException
  {
    try
    {
      JSONObject payload = exportRequest(country, LocalDate.now());
      try (Response response = execute(new Request.Builder()
                                           .url(BASE_URL + "/api/export/")
                                           .post(RequestBody.create(payload.toString(), JSON))
                                           .build()))
      {
        ResponseBody body = response.body();
        if (body == null || body.contentLength() > MAX_DOWNLOAD_BYTES)
          throw new IOException("Invalid export size");
        String type = response.header("Content-Type", "");
        if (type.contains("json") || type.contains("html"))
          throw new IOException("Expected an export file");
        try (InputStream in = body.byteStream(); FileOutputStream out = new FileOutputStream(destination))
        {
          byte[] buffer = new byte[32768];
          long total = 0;
          int read;
          while ((read = in.read(buffer)) != -1)
          {
            total += read;
            if (total > MAX_DOWNLOAD_BYTES)
              throw new IOException("Export is too large");
            out.write(buffer, 0, read);
          }
          if (total == 0)
            throw new IOException("Empty export");
        }
      }
    }
    catch (JSONException e)
    {
      throw new IOException("Cannot create export request", e);
    }
  }

  private Response execute(Request request) throws IOException
  {
    Response response = mClient
                            .newCall(request.newBuilder()
                                         .header("Accept", "application/json, text/plain, */*")
                                         .header("Accept-Language", "ru")
                                         .build())
                            .execute();
    if (!response.isSuccessful())
    {
      int status = response.code();
      response.close();
      if (status == 401 || status == 403)
      {
        logout();
        throw new SessionExpiredException();
      }
      throw new IOException("HTTP " + status);
    }
    return response;
  }

  private static String readJson(Response response) throws IOException
  {
    ResponseBody body = response.body();
    if (body == null)
      throw new IOException("Empty API response");
    return response.peekBody(1024 * 1024).string();
  }

  static final class SessionCookies implements CookieJar
  {
    private final List<Cookie> mValues = new ArrayList<>();
    synchronized void clear()
    {
      mValues.clear();
    }
    @Override
    public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies)
    {
      for (Cookie cookie : cookies)
      {
        mValues.removeIf(old
                         -> old.name().equals(cookie.name()) && old.domain().equals(cookie.domain())
                                && old.path().equals(cookie.path()));
        if (cookie.expiresAt() > System.currentTimeMillis())
          mValues.add(cookie);
      }
    }
    @Override
    public synchronized List<Cookie> loadForRequest(HttpUrl url)
    {
      mValues.removeIf(cookie -> cookie.expiresAt() <= System.currentTimeMillis());
      List<Cookie> result = new ArrayList<>();
      for (Cookie cookie : mValues)
        if (cookie.matches(url))
          result.add(cookie);
      return result;
    }
  }
}
