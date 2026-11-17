using System;
using Lwnrdb.Client.Errors;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Tests.Support;
using Xunit;

namespace Lwnrdb.Client.Tests.Unit.Values;

public sealed class TemporalTextTests
{
    [Theory]
    [InlineData(0, "2024-01-02T03:04")]
    [InlineData(5 * TimeSpan.TicksPerSecond, "2024-01-02T03:04:05")]
    [InlineData(5 * TimeSpan.TicksPerSecond + 1_230_000, "2024-01-02T03:04:05.123")]
    [InlineData(5 * TimeSpan.TicksPerSecond + 1_234_560, "2024-01-02T03:04:05.123456")]
    [InlineData(5 * TimeSpan.TicksPerSecond + 1_234_567, "2024-01-02T03:04:05.123456700")]
    [InlineData(1, "2024-01-02T03:04:00.000000100")]
    public void DateTimesFormatLikeJava(long extraTicks, string expected)
    {
        var value = new DateTime(2024, 1, 2, 3, 4, 0).AddTicks(extraTicks);
        Assert.Equal(expected, TemporalText.Format(value));
        Assert.Equal(value, TemporalText.ParseDateTime(expected));
    }

    [Fact]
    public void TimesFormatLikeJava()
    {
        Assert.Equal("09:30", TemporalText.Format(new TimeOnly(9, 30)));
        Assert.Equal("09:30:15", TemporalText.Format(new TimeOnly(9, 30, 15)));
        Assert.Equal("23:59:59.999", TemporalText.Format(new TimeOnly(23, 59, 59, 999)));
        Assert.Equal(new TimeOnly(9, 30, 15), TemporalText.ParseTime("09:30:15"));
    }

    [Fact]
    public void DigitsPastTheSeventhAreDroppedOnRead()
    {
        Assert.Equal(1_234_567, TemporalText.ParseTime("00:00:00.123456789").Ticks);
        Assert.Equal(new DateTime(2024, 1, 1).AddTicks(1), TemporalText.ParseDateTime("2024-01-01T00:00:00.000000199"));
    }

    [Theory]
    [InlineData("2024-02-30T00:00")]
    [InlineData("2023-02-29T00:00")]
    [InlineData("2024-13-01T00:00")]
    [InlineData("2024-01-01T24:00")]
    [InlineData("2024-01-01T10:60")]
    [InlineData("+10000-01-01T00:00")]
    [InlineData("2024-01-01")]
    [InlineData("2024-01-01T10")]
    public void InvalidDateTimesAreRejected(string text) =>
        Assert.Throws<FormatException>(() => TemporalText.ParseDateTime(text));

    [Fact]
    public void InvalidTimesAreRejected()
    {
        Assert.Throws<FormatException>(() => TemporalText.ParseTime("25:00"));
        Assert.Throws<FormatException>(() => TemporalText.ParseTime("9:30"));
    }

    [Fact]
    public void AMalformedServerValueIsAMappingException()
    {
        Assert.Throws<MappingException>(() => CustomValues.Parse("#datetime(2024-02-30T00:00)"));
        Assert.Throws<MappingException>(() => CustomValues.Parse("#geo(1)"));
        Assert.Throws<MappingException>(() => CustomValues.Parse("#vector(a,b)"));
        Assert.Equal("#other(1)", CustomValues.Parse("#other(1)"));
        Assert.Throws<MappingException>(() => CustomValues.ParseAs<TimeOnly>("#geo(1,2)", "a time"));
        Assert.Throws<MappingException>(() => CustomValues.ParseAs<TimeOnly>("plain", "a time"));
    }

    [Theory]
    [InlineData("es-AR")]
    [InlineData("tr-TR")]
    [InlineData("ar-SA")]
    public void FormattingIgnoresTheCurrentCulture(string culture)
    {
        var text = Cultures.Under(culture, () =>
            CustomValues.Format(new DateTime(2024, 1, 2, 3, 4, 5, 500)) + CustomValues.Format(Client.Values.Vector.Of(0.5, -1.25)));
        Assert.Equal("#datetime(2024-01-02T03:04:05.500)#vector(0.5,-1.25)", text);
    }
}
