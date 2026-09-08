package org.techhouse.simplejs.internal.regex;

import java.util.ArrayList;
import java.util.List;

final class ClassOperand {
    public CodePointSet points = CodePointSet.EMPTY;
    public final List<String> strings = new ArrayList<>();

    public boolean isEmpty() {
        return points.isEmpty() && strings.isEmpty();
    }

    public void addPoints(CodePointSet set) {
        points = points.union(set);
    }

    public void addString(String text) {
        if (text.codePointCount(0, text.length()) == 1) {
            addPoints(CodePointSet.ofChar(text.codePointAt(0)));
        } else {
            strings.add(text);
        }
    }

    public void merge(ClassOperand other) {
        addPoints(other.points);
        strings.addAll(other.strings);
    }
}
