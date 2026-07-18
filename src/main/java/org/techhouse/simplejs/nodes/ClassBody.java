package org.techhouse.simplejs.nodes;

import java.util.List;

public class ClassBody extends JsNode {
    private final List<JsNode> members;

    public ClassBody(List<JsNode> members) {
        this.members = members;
    }

    public List<JsNode> getMembers() {
        return members;
    }
}
