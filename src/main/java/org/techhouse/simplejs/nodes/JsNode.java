package org.techhouse.simplejs.nodes;

public abstract class JsNode {
    public enum NodeType {
        PROGRAM, VARIABLE_DECLARATION, VARIABLE_DECLARATOR, BLOCK_STATEMENT, IF_STATEMENT, WHILE_STATEMENT, FOR_STATEMENT, FOR_IN_STATEMENT, FOR_OF_STATEMENT, TRY_STATEMENT, CATCH_CLAUSE, THROW_STATEMENT, SWITCH_STATEMENT, SWITCH_CASE, RETURN_STATEMENT, BREAK_STATEMENT, CONTINUE_STATEMENT, EXPRESSION_STATEMENT, FUNCTION_DECLARATION, EMPTY_STATEMENT, NUMBER_LITERAL, STRING_LITERAL, BOOLEAN_LITERAL, NULL_LITERAL, UNDEFINED_LITERAL, REGEX_LITERAL, TEMPLATE_LITERAL, IDENTIFIER, THIS_EXPRESSION, ARRAY_EXPRESSION, OBJECT_EXPRESSION, PROPERTY, UNARY_EXPRESSION, UPDATE_EXPRESSION, BINARY_EXPRESSION, LOGICAL_EXPRESSION, ASSIGNMENT_EXPRESSION, CONDITIONAL_EXPRESSION, CALL_EXPRESSION, MEMBER_EXPRESSION, NEW_EXPRESSION, FUNCTION_EXPRESSION, ARROW_FUNCTION_EXPRESSION
    }

    public NodeType getType() {
        return internalGetType(this);
    }

    private static NodeType internalGetType(Object object) {
        return switch (object) {
            case Program ignored -> NodeType.PROGRAM;
            case VariableDeclaration ignored -> NodeType.VARIABLE_DECLARATION;
            case VariableDeclarator ignored -> NodeType.VARIABLE_DECLARATOR;
            case BlockStatement ignored -> NodeType.BLOCK_STATEMENT;
            case IfStatement ignored -> NodeType.IF_STATEMENT;
            case WhileStatement ignored -> NodeType.WHILE_STATEMENT;
            case ForStatement ignored -> NodeType.FOR_STATEMENT;
            case ForInStatement ignored -> NodeType.FOR_IN_STATEMENT;
            case ForOfStatement ignored -> NodeType.FOR_OF_STATEMENT;
            case TryStatement ignored -> NodeType.TRY_STATEMENT;
            case CatchClause ignored -> NodeType.CATCH_CLAUSE;
            case ThrowStatement ignored -> NodeType.THROW_STATEMENT;
            case SwitchStatement ignored -> NodeType.SWITCH_STATEMENT;
            case SwitchCase ignored -> NodeType.SWITCH_CASE;
            case ReturnStatement ignored -> NodeType.RETURN_STATEMENT;
            case BreakStatement ignored -> NodeType.BREAK_STATEMENT;
            case ContinueStatement ignored -> NodeType.CONTINUE_STATEMENT;
            case ExpressionStatement ignored -> NodeType.EXPRESSION_STATEMENT;
            case FunctionDeclaration ignored -> NodeType.FUNCTION_DECLARATION;
            case EmptyStatement ignored -> NodeType.EMPTY_STATEMENT;
            case NumberLiteral ignored -> NodeType.NUMBER_LITERAL;
            case StringLiteral ignored -> NodeType.STRING_LITERAL;
            case BooleanLiteral ignored -> NodeType.BOOLEAN_LITERAL;
            case NullLiteral ignored -> NodeType.NULL_LITERAL;
            case UndefinedLiteral ignored -> NodeType.UNDEFINED_LITERAL;
            case RegexLiteral ignored -> NodeType.REGEX_LITERAL;
            case TemplateLiteral ignored -> NodeType.TEMPLATE_LITERAL;
            case Identifier ignored -> NodeType.IDENTIFIER;
            case ThisExpression ignored -> NodeType.THIS_EXPRESSION;
            case ArrayExpression ignored -> NodeType.ARRAY_EXPRESSION;
            case ObjectExpression ignored -> NodeType.OBJECT_EXPRESSION;
            case Property ignored -> NodeType.PROPERTY;
            case UnaryExpression ignored -> NodeType.UNARY_EXPRESSION;
            case UpdateExpression ignored -> NodeType.UPDATE_EXPRESSION;
            case BinaryExpression ignored -> NodeType.BINARY_EXPRESSION;
            case LogicalExpression ignored -> NodeType.LOGICAL_EXPRESSION;
            case AssignmentExpression ignored -> NodeType.ASSIGNMENT_EXPRESSION;
            case ConditionalExpression ignored -> NodeType.CONDITIONAL_EXPRESSION;
            case CallExpression ignored -> NodeType.CALL_EXPRESSION;
            case MemberExpression ignored -> NodeType.MEMBER_EXPRESSION;
            case NewExpression ignored -> NodeType.NEW_EXPRESSION;
            case FunctionExpression ignored -> NodeType.FUNCTION_EXPRESSION;
            case ArrowFunctionExpression ignored -> NodeType.ARROW_FUNCTION_EXPRESSION;
            default -> throw new IllegalStateException("Unexpected value: " + object);
        };
    }
}
