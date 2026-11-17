# DistributedTransactions

The distributed transactions of a cluster that wait for a decision. Admin only.

## list

@returns the transactions

## resolve

Decides a stuck transaction.

@param dtxId the transaction's id
@param decision commit or abort
