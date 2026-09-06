package org.techhouse.unit.simplejs.elements;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.elements.JsBaseElement.JsType;
import org.techhouse.simplejs.elements.JsBigInt;
import org.techhouse.simplejs.elements.JsBoolean;
import org.techhouse.simplejs.elements.JsEOF;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsNull;
import org.techhouse.simplejs.elements.JsNumber;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.JsPrivateIdentifier;
import org.techhouse.simplejs.elements.JsRegex;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.JsString;
import org.techhouse.simplejs.elements.JsTemplateString;
import org.techhouse.simplejs.elements.JsUndefined;

public class JsBaseElementTest {
    // Each element subclass reports the matching JsType
    @Test
    public void test_get_type_for_each_subclass() {
        assertEquals(JsType.KEYWORD, new JsKeyword("if").getType());
        assertEquals(JsType.IDENTIFIER, new JsIdentifier("x").getType());
        assertEquals(JsType.PRIVATE_IDENTIFIER, new JsPrivateIdentifier("x").getType());
        assertEquals(JsType.NUMBER, new JsNumber(1.0).getType());
        assertEquals(JsType.BIGINT, new JsBigInt(BigInteger.ONE).getType());
        assertEquals(JsType.STRING, new JsString("s").getType());
        assertEquals(JsType.BOOLEAN, new JsBoolean(true).getType());
        assertEquals(JsType.NULL, JsNull.getInstance().getType());
        assertEquals(JsType.UNDEFINED, JsUndefined.getInstance().getType());
        assertEquals(JsType.OPERATOR, new JsOperator("+").getType());
        assertEquals(JsType.SEPARATOR, new JsSeparator(';').getType());
        assertEquals(JsType.REGEX, new JsRegex("a", "g").getType());
        assertEquals(JsType.TEMPLATE_STRING, new JsTemplateString(List.of(""), List.of(""), List.of()).getType());
        assertEquals(JsType.EOF, JsEOF.getInstance().getType());
    }
}
