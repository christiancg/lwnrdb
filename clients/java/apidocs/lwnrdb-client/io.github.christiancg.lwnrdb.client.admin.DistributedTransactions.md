The two-phase-commit transactions of a cluster that are still unresolved. Admin only.

## list()

@return the unresolved distributed transactions

## resolve(String, TransactionDecision)

Forces the outcome of a transaction whose coordinator is gone.

@param dtxId the distributed transaction's id
@param decision commit or abort
