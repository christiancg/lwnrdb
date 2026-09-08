package org.techhouse.simplejs.internal.parser;

import java.util.List;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.JsNode;

public record FunctionParts(List<JsNode> params, BlockStatement body) {
}
