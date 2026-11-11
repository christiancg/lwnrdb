using System.Globalization;
using System.Linq;
using System.Threading.Tasks;

namespace Lwnrdb.Client.IntegrationTests.Support;

public static class Seed
{
    public static async Task QueryDataAsync(LwnrdbClient client, string databaseName)
    {
        var database = client.Database(databaseName);
        await database.CreateAsync();
        var orders = database.Collection("orders");
        var customers = database.Collection("customers");
        await orders.CreateAsync();
        await customers.CreateAsync();
        await orders.SaveAllAsync(Enumerable.Range(0, 20).Select(i => Document.Of(
            "_id", "o-" + i.ToString("00", CultureInfo.InvariantCulture),
            "status", i % 3 == 0 ? "new" : "paid",
            "total", i * 10,
            "qty", i % 4 + 1,
            "price", 2.5,
            "customerId", "c-" + (i % 2).ToString(CultureInfo.InvariantCulture),
            "name", "Item " + i.ToString(CultureInfo.InvariantCulture),
            "tags", new[] { "t" + (i % 2).ToString(CultureInfo.InvariantCulture) },
            "paid", i % 3 != 0,
            "discount", i % 5 == 0 ? null : i,
            "location", new Values.GeoPoint(40 + i * 0.01, -74 + i * 0.01))));
        await customers.SaveAllAsync([Document.Of("_id", "c-0", "city", "Lima"), Document.Of("_id", "c-1", "city", "Quito")]);
    }
}
