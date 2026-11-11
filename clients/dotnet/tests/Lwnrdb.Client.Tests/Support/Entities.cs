using System;
using System.Collections.Generic;
using System.Text.Json.Serialization;
using Lwnrdb.Client.Values;

namespace Lwnrdb.Client.Tests.Support;

public enum OrderStatus
{
    Active,
    Closed,
}

public sealed class Address
{
    public string? City { get; set; }
}

public sealed class Order
{
    public string? Id { get; set; }

    public string? Status { get; set; }

    public double Total { get; set; }

    public double? Discount { get; set; }

    public string? Name { get; set; }

    public bool Paid { get; set; }

    public DateTime CreatedAt { get; set; }

    public DateTime? DeletedAt { get; set; }

    public Address? Address { get; set; }

    public string? CustomerId { get; set; }

    public List<string> Tags { get; set; } = [];

    public DateTime OpenedAt { get; set; }

    public TimeOnly OpensAt { get; set; }

    public GeoPoint Location { get; set; }

    public Vector? Embedding { get; set; }

    public OrderStatus Stage { get; set; }

    [JsonPropertyName("placed_at")]
    public DateTime PlacedAt { get; set; }

    [JsonIgnore]
    public string? Secret { get; set; }

    public bool Admin { get; set; }

    public int Count { get; set; }
}

public sealed class Customer
{
    public string? Id { get; set; }

    public string? Name { get; set; }
}

public sealed class Tagged
{
    public string? Id { get; set; }

    [JsonConverter(typeof(JsonStringEnumConverter))]
    public OrderStatus Stage { get; set; }

    public OrderStatus Plain { get; set; }

    [LwnrdbVector]
    public float[]? Embedding { get; set; }

    [LwnrdbVector]
    public double[]? Weights { get; set; }

    public DateTimeOffset? At { get; set; }
}

public sealed record Point(string Id, string Label, double Weight);

public sealed class NumericId
{
    public int Id { get; set; }
}
