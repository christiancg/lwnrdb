using System;
using System.Globalization;
using System.Text.RegularExpressions;

namespace Lwnrdb.Client.Internal;

internal static partial class TemporalText
{
    private const long NanosPerTick = 100;
    private const long NanosPerMilli = 1_000_000;
    private const long NanosPerMicro = 1_000;
    private const int TickDigits = 7;

    public static string Format(DateTime value) =>
        value.ToString("yyyy'-'MM'-'dd'T'", CultureInfo.InvariantCulture) + FormatTime(value.TimeOfDay.Ticks);

    public static string Format(TimeOnly value) => FormatTime(value.Ticks);

    public static DateTime ParseDateTime(string text)
    {
        var match = DateTimePattern().Match(text);
        if (!match.Success)
        {
            throw new FormatException($"not a date-time: {text}");
        }
        var year = int.Parse(match.Groups["year"].Value, CultureInfo.InvariantCulture);
        var month = int.Parse(match.Groups["month"].Value, CultureInfo.InvariantCulture);
        var day = int.Parse(match.Groups["day"].Value, CultureInfo.InvariantCulture);
        if (year is < 1 or > 9999 || month is < 1 or > 12 || day < 1 || day > DateTime.DaysInMonth(year, month))
        {
            throw new FormatException($"not a valid date-time: {text}");
        }
        return new DateTime(year, month, day, 0, 0, 0, DateTimeKind.Unspecified).AddTicks(TimeTicks(match, text));
    }

    public static TimeOnly ParseTime(string text)
    {
        var match = TimePattern().Match(text);
        if (!match.Success)
        {
            throw new FormatException($"not a time: {text}");
        }
        return new TimeOnly(TimeTicks(match, text));
    }

    private static long TimeTicks(Match match, string text)
    {
        var hour = int.Parse(match.Groups["hour"].Value, CultureInfo.InvariantCulture);
        var minute = int.Parse(match.Groups["minute"].Value, CultureInfo.InvariantCulture);
        var second = match.Groups["second"].Success
            ? int.Parse(match.Groups["second"].Value, CultureInfo.InvariantCulture)
            : 0;
        if (hour > 23 || minute > 59 || second > 59)
        {
            throw new FormatException($"not a valid time: {text}");
        }
        var fraction = match.Groups["fraction"].Value;
        var ticksText = fraction.Length > TickDigits ? fraction[..TickDigits] : fraction.PadRight(TickDigits, '0');
        var fractionTicks = long.Parse(ticksText, CultureInfo.InvariantCulture);
        return new TimeSpan(hour, minute, second).Ticks + fractionTicks;
    }

    private static string FormatTime(long ticksOfDay)
    {
        var time = new TimeSpan(ticksOfDay);
        var hoursAndMinutes = string.Create(CultureInfo.InvariantCulture, $"{time.Hours:00}:{time.Minutes:00}");
        var nanos = ticksOfDay % TimeSpan.TicksPerSecond * NanosPerTick;
        if (time.Seconds == 0 && nanos == 0)
        {
            return hoursAndMinutes;
        }
        return string.Create(CultureInfo.InvariantCulture, $"{hoursAndMinutes}:{time.Seconds:00}") + FormatFraction(nanos);
    }

    private static string FormatFraction(long nanos)
    {
        if (nanos == 0)
        {
            return string.Empty;
        }
        if (nanos % NanosPerMilli == 0)
        {
            return "." + (nanos / NanosPerMilli).ToString("000", CultureInfo.InvariantCulture);
        }
        if (nanos % NanosPerMicro == 0)
        {
            return "." + (nanos / NanosPerMicro).ToString("000000", CultureInfo.InvariantCulture);
        }
        return "." + nanos.ToString("000000000", CultureInfo.InvariantCulture);
    }

    [GeneratedRegex(@"^(?<year>[+-]?\d{4,9})-(?<month>\d{2})-(?<day>\d{2})T(?<hour>\d{2}):(?<minute>\d{2})(?::(?<second>\d{2})(?:\.(?<fraction>\d{1,9}))?)?$", RegexOptions.CultureInvariant)]
    private static partial Regex DateTimePattern();

    [GeneratedRegex(@"^(?<hour>\d{2}):(?<minute>\d{2})(?::(?<second>\d{2})(?:\.(?<fraction>\d{1,9}))?)?$", RegexOptions.CultureInvariant)]
    private static partial Regex TimePattern();
}
