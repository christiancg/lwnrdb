The two-phase-commit transactions of a cluster that are still unresolved. Obtained from `Admin.Transactions()`. Admin only.

## ListAsync(CancellationToken)
Lists the unresolved distributed transactions.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the unresolved transactions

## ResolveAsync(string, TransactionDecision, CancellationToken)
Forces the outcome of a transaction whose coordinator is gone.

@param dtxId the distributed transaction's id
@param decision commit or abort
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
