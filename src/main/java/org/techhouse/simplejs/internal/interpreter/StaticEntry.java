package org.techhouse.simplejs.internal.interpreter;

import org.techhouse.simplejs.nodes.FieldDefinition;
import org.techhouse.simplejs.nodes.StaticBlock;
import org.techhouse.simplejs.values.JsValue;

public record StaticEntry(FieldDefinition field, StaticBlock block, JsValue key) {
}
