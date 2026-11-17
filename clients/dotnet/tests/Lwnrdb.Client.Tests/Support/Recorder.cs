using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Lwnrdb.Client.Internal;
using Lwnrdb.Client.Protocol;

namespace Lwnrdb.Client.Tests.Support;

internal sealed class Recorder
{
    public Recorder()
    {
        Context = RecordingChannel.Context(Channel);
        Shop = new Database(Context, "shop");
        Orders = Shop.Collection("orders");
        TypedOrders = Shop.Collection<Order>("orders");
        Customers = Shop.Collection<Customer>("customers");
        Admin = new Admin.Admin(Context);
    }

    public RecordingChannel Channel { get; } = new();

    public ClientContext Context { get; }

    public Database Shop { get; }

    public Collection<Document> Orders { get; }

    public Collection<Order> TypedOrders { get; }

    public Collection<Customer> Customers { get; }

    public Admin.Admin Admin { get; }

    public RequestEncoder Encoder => Context.Encoder;

    public Task Raw(JsonObject request) => Channel.SendAsync(Operation.ServerInfo, request, default);
}
