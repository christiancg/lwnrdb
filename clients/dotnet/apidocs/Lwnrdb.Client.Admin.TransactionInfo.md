An unresolved distributed transaction.

@param DtxId the transaction's id
@param Coordinator the coordinating node
@param CoordinatorReachable whether that node answers
@param Participants the participating nodes
@param AgeMs the transaction's age in milliseconds
@param Raw the row as the server sent it

## TransactionInfo(string, string, bool, IReadOnlyList<string>, long, Document)
Creates the description.

@param DtxId the transaction's id
@param Coordinator the coordinating node
@param CoordinatorReachable whether that node answers
@param Participants the participating nodes
@param AgeMs the transaction's age in milliseconds
@param Raw the row as the server sent it

## AgeMs
The transaction's age in milliseconds.

## Coordinator
The coordinating node, or null when unknown.

## CoordinatorReachable
Whether the coordinating node answers.

## DtxId
The transaction's id, which `DistributedTransactions.ResolveAsync` takes.

## Participants
The participating nodes.

## Raw
The row as the server sent it.
