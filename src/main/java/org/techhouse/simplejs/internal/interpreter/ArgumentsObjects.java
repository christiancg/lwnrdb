package org.techhouse.simplejs.internal.interpreter;

import java.util.List;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsValue;

public record ArgumentsObjects(Interpreter interp) {
    public JsArguments make(List<JsValue> args) {
        return withOwnProperties(new JsArguments(args, null, null));
    }

    public JsArguments withOwnProperties(JsArguments arguments) {
        interp.intrinsics.poison(arguments, "callee");
        final var table = arguments.ownProperties();
        table.setFlags("callee", new JsObject.PropertyFlags(false, false, false));
        table.defineSymbolValue(JsSymbol.ITERATOR, interp.intrinsics.arrayProto.getSymbol(JsSymbol.ITERATOR));
        table.setSymbolFlags(JsSymbol.ITERATOR, JsObject.PropertyFlags.HIDDEN);
        return arguments;
    }
}
