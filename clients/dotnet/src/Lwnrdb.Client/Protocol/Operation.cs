using System;
using System.Collections.Frozen;
using System.Collections.Generic;
using System.Linq;

namespace Lwnrdb.Client.Protocol;

public sealed class Operation
{
    public static readonly Operation BulkSave = new("BULK_SAVE", OperationScope.Collection, false, true);
    public static readonly Operation Save = new("SAVE", OperationScope.Collection, false, true);
    public static readonly Operation FindById = new("FIND_BY_ID", OperationScope.Collection, false, true);
    public static readonly Operation Aggregate = new("AGGREGATE", OperationScope.Collection, false, true);
    public static readonly Operation Delete = new("DELETE", OperationScope.Collection, false, true);
    public static readonly Operation CreateDatabase = new("CREATE_DATABASE", OperationScope.Database, false, false);
    public static readonly Operation DropDatabase = new("DROP_DATABASE", OperationScope.Database, false, false);
    public static readonly Operation ListDatabases = new("LIST_DATABASES", OperationScope.None, false, false);
    public static readonly Operation CreateCollection = new("CREATE_COLLECTION", OperationScope.Collection, false, false);
    public static readonly Operation DropCollection = new("DROP_COLLECTION", OperationScope.Collection, false, false);
    public static readonly Operation ListCollections = new("LIST_COLLECTIONS", OperationScope.Database, false, false);
    public static readonly Operation CreateIndex = new("CREATE_INDEX", OperationScope.Collection, false, false);
    public static readonly Operation DropIndex = new("DROP_INDEX", OperationScope.Collection, false, false);
    public static readonly Operation Reindex = new("REINDEX", OperationScope.Collection, false, false);
    public static readonly Operation SaveSchema = new("SAVE_SCHEMA", OperationScope.Collection, false, false);
    public static readonly Operation DeleteSchema = new("DELETE_SCHEMA", OperationScope.Collection, false, false);
    public static readonly Operation GetSchema = new("GET_SCHEMA", OperationScope.Collection, false, false);
    public static readonly Operation CloseConnection = new("CLOSE_CONNECTION", OperationScope.None, false, true);
    public static readonly Operation ServerInfo = new("SERVER_INFO", OperationScope.None, false, true);
    public static readonly Operation Authenticate = new("AUTHENTICATE", OperationScope.None, false, false);
    public static readonly Operation CreateUser = new("CREATE_USER", OperationScope.None, true, false);
    public static readonly Operation DeleteUser = new("DELETE_USER", OperationScope.None, true, false);
    public static readonly Operation ChangePermissions = new("CHANGE_PERMISSIONS", OperationScope.None, true, false);
    public static readonly Operation SetDatabaseOwners = new("SET_DATABASE_OWNERS", OperationScope.Database, true, false);
    public static readonly Operation ListUsers = new("LIST_USERS", OperationScope.None, true, false);
    public static readonly Operation SetPassword = new("SET_PASSWORD", OperationScope.None, false, false);
    public static readonly Operation GetDatabaseStats = new("GET_DATABASE_STATS", OperationScope.Database, false, false);
    public static readonly Operation Listen = new("LISTEN", OperationScope.Collection, false, false);
    public static readonly Operation StopListen = new("STOP_LISTEN", OperationScope.None, false, false);
    public static readonly Operation StartTransaction = new("START_TRANSACTION", OperationScope.None, false, true);
    public static readonly Operation CommitTransaction = new("COMMIT_TRANSACTION", OperationScope.None, false, true);
    public static readonly Operation RollbackTransaction = new("ROLLBACK_TRANSACTION", OperationScope.None, false, true);
    public static readonly Operation ResolveTransaction = new("RESOLVE_TRANSACTION", OperationScope.None, true, false);
    public static readonly Operation ListTransactions = new("LIST_TRANSACTIONS", OperationScope.None, true, false);
    public static readonly Operation RunScript = new("RUN_SCRIPT", OperationScope.Database, false, false);
    public static readonly Operation SaveProcedure = new("SAVE_PROCEDURE", OperationScope.Database, false, false);
    public static readonly Operation DeleteProcedure = new("DELETE_PROCEDURE", OperationScope.Database, false, false);
    public static readonly Operation ListProcedures = new("LIST_PROCEDURES", OperationScope.Database, false, false);
    public static readonly Operation CallProcedure = new("CALL_PROCEDURE", OperationScope.Database, false, false);
    public static readonly Operation SaveTrigger = new("SAVE_TRIGGER", OperationScope.Collection, false, false);
    public static readonly Operation DeleteTrigger = new("DELETE_TRIGGER", OperationScope.Collection, false, false);
    public static readonly Operation ListTriggers = new("LIST_TRIGGERS", OperationScope.OptionalCollection, false, false);
    public static readonly Operation SaveSchedule = new("SAVE_SCHEDULE", OperationScope.Database, false, false);
    public static readonly Operation DeleteSchedule = new("DELETE_SCHEDULE", OperationScope.Database, false, false);
    public static readonly Operation ListSchedules = new("LIST_SCHEDULES", OperationScope.Database, false, false);
    public static readonly Operation ListScripts = new("LIST_SCRIPTS", OperationScope.None, true, false);
    public static readonly Operation CancelScript = new("CANCEL_SCRIPT", OperationScope.None, true, false);
    public static readonly Operation TestTrigger = new("TEST_TRIGGER", OperationScope.Collection, false, false);
    public static readonly Operation ListTriggerRuns = new("LIST_TRIGGER_RUNS", OperationScope.None, true, false);
    public static readonly Operation ResolveTriggerRun = new("RESOLVE_TRIGGER_RUN", OperationScope.None, true, false);

    private static readonly FrozenDictionary<string, Operation> ByName = typeof(Operation)
        .GetFields(System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Static)
        .Where(field => field.FieldType == typeof(Operation))
        .Select(field => (Operation)field.GetValue(null)!)
        .ToFrozenDictionary(operation => operation.Name, StringComparer.Ordinal);

    private Operation(string name, OperationScope scope, bool adminOnly, bool allowedInTransaction)
    {
        Name = name;
        Scope = scope;
        AdminOnly = adminOnly;
        AllowedInTransaction = allowedInTransaction;
        IsRead = name is "FIND_BY_ID" or "AGGREGATE" or "SERVER_INFO"
            || name.StartsWith("LIST_", StringComparison.Ordinal)
            || name.StartsWith("GET_", StringComparison.Ordinal);
    }

    public string Name { get; }

    public OperationScope Scope { get; }

    public bool AdminOnly { get; }

    public bool AllowedInTransaction { get; }

    public bool IsRead { get; }

    public static IReadOnlyCollection<Operation> All => ByName.Values;

    public static Operation? Of(string? name) =>
        name is not null && ByName.TryGetValue(name, out var operation) ? operation : null;

    public override string ToString() => Name;
}
