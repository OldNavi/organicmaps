package app.organicmaps.road;

/** Number spellout for the bounded camera limits, using translated cardinal number tables. */
final class SpeedLimitWords
{
  static final int MAX_LIMIT = 400;
  private final String[] mSmall;
  private final String[] mTens;
  private final String[] mHundreds;
  private final String mTensSeparator;

  SpeedLimitWords(String small, String tens, String hundreds, boolean hyphenateTens)
  {
    mSmall = small.split("\\|");
    mTens = tens.split("\\|");
    mHundreds = hundreds.split("\\|");
    if (mSmall.length != 20 || mTens.length != 8 || mHundreds.length != 4)
      throw new IllegalArgumentException("Invalid cardinal number tables");
    mTensSeparator = hyphenateTens ? "-" : " ";
  }

  String format(int number)
  {
    if (number <= 0 || number > MAX_LIMIT)
      return null;
    StringBuilder words = new StringBuilder();
    if (number >= 100)
    {
      words.append(mHundreds[number / 100 - 1]);
      number %= 100;
      if (number > 0)
        words.append(' ');
    }
    if (number >= 20)
    {
      words.append(mTens[number / 10 - 2]);
      number %= 10;
      if (number > 0)
        words.append(mTensSeparator);
    }
    if (number > 0)
      words.append(mSmall[number]);
    return words.toString();
  }
}
