package org.techhouse.simplejs.nodes;

public class TryStatement extends Statement {
    private final BlockStatement block;
    private final CatchClause handler;
    private final BlockStatement finalizer;

    public TryStatement(BlockStatement block, CatchClause handler, BlockStatement finalizer) {
        this.block = block;
        this.handler = handler;
        this.finalizer = finalizer;
    }

    public BlockStatement getBlock() {
        return block;
    }

    public CatchClause getHandler() {
        return handler;
    }

    public BlockStatement getFinalizer() {
        return finalizer;
    }
}
