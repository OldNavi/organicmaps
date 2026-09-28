package app.organicmaps.road;

import static org.junit.Assert.*;

import org.junit.Test;

public class SpeedLimitWordsTest
{
  private static SpeedLimitWords russian()
  {
    return new SpeedLimitWords(
        "ноль|один|два|три|четыре|пять|шесть|семь|восемь|девять|десять|одиннадцать|двенадцать|тринадцать|"
            + "четырнадцать|пятнадцать|шестнадцать|семнадцать|восемнадцать|девятнадцать",
        "двадцать|тридцать|сорок|пятьдесят|шестьдесят|семьдесят|восемьдесят|девяносто", "сто|двести|триста|четыреста",
        false);
  }

  private static SpeedLimitWords english()
  {
    return new SpeedLimitWords(
        "zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|"
            + "seventeen|eighteen|nineteen",
        "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety", "one hundred|two hundred|three hundred|four hundred",
        true);
  }

  @Test
  public void russianCardinalsCoverSpecialTensAndHundreds()
  {
    var words = russian();
    assertEquals("один", words.format(1));
    assertEquals("одиннадцать", words.format(11));
    assertEquals("двадцать", words.format(20));
    assertEquals("двадцать один", words.format(21));
    assertEquals("сорок", words.format(40));
    assertEquals("шестьдесят", words.format(60));
    assertEquals("девяносто", words.format(90));
    assertEquals("сто", words.format(100));
    assertEquals("сто десять", words.format(110));
    assertEquals("сто тридцать", words.format(130));
    assertEquals("двести сорок девять", words.format(249));
    assertEquals("триста девяносто девять", words.format(399));
    assertEquals("четыреста", words.format(400));
  }

  @Test
  public void englishHyphenatesOnlyCompoundTens()
  {
    var words = english();
    assertEquals("one", words.format(1));
    assertEquals("nineteen", words.format(19));
    assertEquals("twenty", words.format(20));
    assertEquals("twenty-one", words.format(21));
    assertEquals("thirty-seven", words.format(37));
    assertEquals("sixty", words.format(60));
    assertEquals("one hundred", words.format(100));
    assertEquals("one hundred one", words.format(101));
    assertEquals("one hundred ten", words.format(110));
    assertEquals("one hundred thirty", words.format(130));
    assertEquals("two hundred forty-nine", words.format(249));
    assertEquals("four hundred", words.format(400));
  }

  @Test
  public void everySupportedLimitHasNoDigitsOrExtraSeparators()
  {
    for (var words : new SpeedLimitWords[] {russian(), english()})
      for (int limit = 1; limit <= 400; ++limit)
      {
        String value = words.format(limit);
        assertTrue(value, value.matches("[\\p{L}]+(?:[- ][\\p{L}]+)*"));
      }
  }

  @Test
  public void unknownAndOutOfRangeLimitsAreNotSpelledOut()
  {
    for (var words : new SpeedLimitWords[] {russian(), english()})
      for (int limit : new int[] {Integer.MIN_VALUE, -1, 0, 401, Integer.MAX_VALUE})
        assertNull(words.format(limit));
  }

  @Test(expected = IllegalArgumentException.class)
  public void malformedTranslationTableFailsFast()
  {
    new SpeedLimitWords("one", "twenty", "one hundred", true);
  }
}
