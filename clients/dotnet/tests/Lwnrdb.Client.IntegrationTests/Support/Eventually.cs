using System;
using System.Threading.Tasks;
using Xunit;

namespace Lwnrdb.Client.IntegrationTests.Support;

public static class Eventually
{
    public static async Task<T> Until<T>(Func<Task<T>> read, Func<T, bool> holds, TimeSpan? within = null)
    {
        var deadline = DateTime.UtcNow + (within ?? TimeSpan.FromSeconds(20));
        while (true)
        {
            var value = await read();
            if (holds(value))
            {
                return value;
            }
            Assert.True(DateTime.UtcNow < deadline, "the condition never held");
            await Task.Delay(100);
        }
    }
}
