package app.organicmaps.road;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONException;
import org.json.JSONObject;

/** Provider credentials are encrypted with a non-exportable application key and excluded from backups. */
final class RoadDataCredentials
{
  record Account(String login, String password)
  {
  }
  private static final String KEY_ALIAS = "road-data-credentials-v1";
  private final File mDirectory;

  RoadDataCredentials(Context context)
  {
    mDirectory = context.getNoBackupFilesDir();
  }

  private AtomicFile file(String provider)
  {
    String name = Base64.encodeToString(provider.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP);
    return new AtomicFile(new File(mDirectory, "road-source-" + name + ".auth"));
  }

  private static synchronized SecretKey key() throws GeneralSecurityException, IOException
  {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
      throw new GeneralSecurityException("Encrypted source credentials require Android 6 or later");
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (store.containsAlias(KEY_ALIAS))
      return (SecretKey) store.getKey(KEY_ALIAS, null);
    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
    return generator.generateKey();
  }

  void save(String provider, Account account) throws IOException
  {
    try
    {
      byte[] plain = new JSONObject()
                         .put("login", account.login())
                         .put("password", account.password())
                         .toString()
                         .getBytes(StandardCharsets.UTF_8);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key());
      cipher.updateAAD(provider.getBytes(StandardCharsets.UTF_8));
      byte[] encrypted;
      try
      {
        encrypted = cipher.doFinal(plain);
      }
      finally
      {
        Arrays.fill(plain, (byte) 0);
      }
      AtomicFile file = file(provider);
      FileOutputStream output = file.startWrite();
      try
      {
        byte[] iv = cipher.getIV();
        output.write(1);
        output.write(iv.length);
        output.write(iv);
        output.write(encrypted);
        file.finishWrite(output);
      }
      catch (IOException e)
      {
        file.failWrite(output);
        throw e;
      }
    }
    catch (GeneralSecurityException | JSONException e)
    {
      throw new IOException("Cannot save source credentials", e);
    }
  }

  Account load(String provider) throws IOException
  {
    AtomicFile file = file(provider);
    try
    {
      byte[] stored = file.readFully();
      if (stored.length < 30 || stored[0] != 1 || stored[1] != 12)
        throw new IOException("Invalid credentials file");
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, stored, 2, 12));
      cipher.updateAAD(provider.getBytes(StandardCharsets.UTF_8));
      byte[] plain = cipher.doFinal(stored, 14, stored.length - 14);
      try
      {
        JSONObject account = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        return new Account(account.getString("login"), account.getString("password"));
      }
      finally
      {
        Arrays.fill(plain, (byte) 0);
      }
    }
    catch (FileNotFoundException e)
    {
      return null;
    }
    catch (GeneralSecurityException | JSONException e)
    {
      throw new IOException("Cannot read source credentials", e);
    }
  }

  void clear(String provider)
  {
    file(provider).delete();
  }
}
