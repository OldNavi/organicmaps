package app.organicmaps.sdk.road;

import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;

/** Provider-neutral details of the selected index snapshot. */
@Keep
public record RoadEventInfo(String identity, int kind, int speedKmh, long importedAt) implements Parcelable
{
  @Override
  public int describeContents()
  {
    return 0;
  }

  @Override
  public void writeToParcel(@NonNull Parcel dest, int flags)
  {
    dest.writeString(identity);
    dest.writeInt(kind);
    dest.writeInt(speedKmh);
    dest.writeLong(importedAt);
  }

  public static final Creator<RoadEventInfo> CREATOR = new Creator<>() {
    @Override
    public RoadEventInfo createFromParcel(Parcel source)
    {
      return new RoadEventInfo(source.readString(), source.readInt(), source.readInt(), source.readLong());
    }

    @Override
    public RoadEventInfo[] newArray(int size)
    {
      return new RoadEventInfo[size];
    }
  };
}
