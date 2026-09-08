package org.techhouse.simplejs.internal.lexer;

import org.techhouse.simplejs.elements.JsBaseElement;

public record Lexed(JsBaseElement token, int next) {
}
