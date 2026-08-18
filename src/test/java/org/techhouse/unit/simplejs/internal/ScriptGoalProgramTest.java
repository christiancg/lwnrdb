package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.Program;

// The strict Script goal raises the early errors the host contract deliberately relaxes, so every
// case is asserted twice: rejected under the goal, accepted under the default.
public class ScriptGoalProgramTest {
    private Program parseStrict(String source) {
        return Parser.parse(Lexer.lexWithPositions(source), true);
    }

    private Program parseRelaxed(String source) {
        return Parser.parse(Lexer.lexWithPositions(source), false);
    }

    private void assertRejectedOnlyByGoal(String source) {
        assertThrows(SyntaxErrorException.class, () -> parseStrict(source), source);
        assertNotNull(parseRelaxed(source), source);
    }

    private void assertAcceptedByBoth(String source) {
        assertNotNull(parseStrict(source), source);
        assertNotNull(parseRelaxed(source), source);
    }

    // A return outside function code is an early error, wherever the enclosing statement nests it
    @Test
    public void test_top_level_return_rejected() {
        assertRejectedOnlyByGoal("var x = 1; return;");
        assertRejectedOnlyByGoal("return (0);");
        assertRejectedOnlyByGoal("{ return; }");
        assertRejectedOnlyByGoal("try { throw 1; } catch (e) { return e; }");
        assertRejectedOnlyByGoal("while (true) { return; }");
    }

    // A return inside any function-like body stays legal under the goal
    @Test
    public void test_return_in_function_accepted() {
        assertAcceptedByBoth("function f() { return 1; }");
        assertAcceptedByBoth("var f = () => { return 1; };");
        assertAcceptedByBoth("class C { m() { return 1; } }");
        assertAcceptedByBoth("var o = { m() { return 1; } };");
    }

    // new.target is not reachable from global code, and an arrow does not introduce one
    @Test
    public void test_top_level_new_target_rejected() {
        assertRejectedOnlyByGoal("new.target;");
        assertRejectedOnlyByGoal("var f = () => new.target;");
    }

    // Function and class-member code both provide a new.target
    @Test
    public void test_new_target_in_function_code_accepted() {
        assertAcceptedByBoth("function f() { return new.target; }");
        assertAcceptedByBoth("function f() { return () => new.target; }");
        assertAcceptedByBoth("class C { m() { return new.target; } }");
        assertAcceptedByBoth("class C { static { new.target; } }");
    }

    // A super property belongs to a method, so global code rejects it under either goal - the Script
    // goal only adds the rejection for the arrow-wrapped form the host contract would otherwise allow
    @Test
    public void test_top_level_super_property_rejected() {
        assertRejectedByBoth("super.property;");
        assertRejectedByBoth("var f = () => super.property;");
    }

    private void assertRejectedByBoth(String source) {
        assertThrows(SyntaxErrorException.class, () -> parseStrict(source), source);
        assertThrows(SyntaxErrorException.class, () -> parseRelaxed(source), source);
    }

    // A method, a field initializer and a static block all keep their super binding
    @Test
    public void test_super_property_in_class_accepted() {
        assertAcceptedByBoth("class C extends B { m() { return super.x; } }");
        assertAcceptedByBoth("class C extends B { x = super.y; }");
        assertAcceptedByBoth("class C extends B { m() { return () => super.x; } }");
        assertAcceptedByBoth("var o = { m() { return super.x; } };");
    }

    // import.meta belongs to the Module goal; a dynamic import() is legal in both
    @Test
    public void test_import_meta_rejected() {
        assertRejectedOnlyByGoal("import.meta;");
        assertRejectedOnlyByGoal("var url = import.meta.url;");
        assertAcceptedByBoth("import('args');");
    }

    // Static import and export declarations belong to the Module goal
    @Test
    public void test_import_and_export_declarations_rejected() {
        assertRejectedOnlyByGoal("import v from './import.js';");
        assertRejectedOnlyByGoal("import 'args';");
        assertRejectedOnlyByGoal("export default null;");
        assertRejectedOnlyByGoal("export const a = 1;");
        assertRejectedOnlyByGoal("export * from 'args';");
    }

    // A using declaration is rejected only at the script's own top level
    @Test
    public void test_top_level_using_rejected() {
        assertRejectedOnlyByGoal("using x = null;");
        assertRejectedOnlyByGoal("await using x = null;");
        assertAcceptedByBoth("{ using x = null; }");
        assertAcceptedByBoth("{ await using x = null; }");
        assertAcceptedByBoth("function f() { using x = null; }");
        assertAcceptedByBoth("for (using x of []) { x; }");
        assertAcceptedByBoth("class C { static { using x = null; } }");
    }

    // The default goal is what the database host parses with, so its own contract is unchanged
    @Test
    public void test_relaxed_goal_still_parses_the_host_contract() {
        assertAcceptedByBoth("var x = 1;");
        assertNotNull(parseRelaxed("return 1;"));
        assertNotNull(parseRelaxed("import args from 'args'; export default args;"));
    }
}
