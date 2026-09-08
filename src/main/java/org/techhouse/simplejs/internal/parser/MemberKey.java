package org.techhouse.simplejs.internal.parser;

import org.techhouse.simplejs.nodes.Expression;

record MemberKey(Expression key, boolean computed) {
}
