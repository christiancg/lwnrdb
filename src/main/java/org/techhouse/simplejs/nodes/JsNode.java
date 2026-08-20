package org.techhouse.simplejs.nodes;

public abstract class JsNode {
    // The construct's verbatim source text, retained by the parser only for the function-like and
    // class nodes whose runtime value has an observable [[SourceText]] (Function.prototype.toString).
    // Null wherever there was no source to slice - the token-list-only parse entry point and a
    // template substitution's nested token stream - which is what makes the callable report the
    // NativeFunction form instead.
    private String sourceText;

    public enum NodeType {
        PROGRAM, VARIABLE_DECLARATION, VARIABLE_DECLARATOR, BLOCK_STATEMENT, IF_STATEMENT, WHILE_STATEMENT, DO_WHILE_STATEMENT, FOR_STATEMENT, FOR_IN_STATEMENT, FOR_OF_STATEMENT, TRY_STATEMENT, CATCH_CLAUSE, THROW_STATEMENT, SWITCH_STATEMENT, SWITCH_CASE, RETURN_STATEMENT, BREAK_STATEMENT, CONTINUE_STATEMENT, LABELED_STATEMENT, EXPRESSION_STATEMENT, FUNCTION_DECLARATION, EMPTY_STATEMENT, NUMBER_LITERAL, BIGINT_LITERAL, STRING_LITERAL, BOOLEAN_LITERAL, NULL_LITERAL, UNDEFINED_LITERAL, REGEX_LITERAL, TEMPLATE_LITERAL, IDENTIFIER, THIS_EXPRESSION, ARRAY_EXPRESSION, OBJECT_EXPRESSION, PROPERTY, UNARY_EXPRESSION, UPDATE_EXPRESSION, BINARY_EXPRESSION, LOGICAL_EXPRESSION, ASSIGNMENT_EXPRESSION, CONDITIONAL_EXPRESSION, CALL_EXPRESSION, MEMBER_EXPRESSION, NEW_EXPRESSION, FUNCTION_EXPRESSION, ARROW_FUNCTION_EXPRESSION, CLASS_DECLARATION, CLASS_EXPRESSION, CLASS_BODY, METHOD_DEFINITION, FIELD_DEFINITION, STATIC_BLOCK, SUPER_EXPRESSION, PRIVATE_IDENTIFIER, AWAIT_EXPRESSION, YIELD_EXPRESSION, SPREAD_ELEMENT, REST_ELEMENT, ARRAY_PATTERN, OBJECT_PATTERN, ASSIGNMENT_PATTERN, IMPORT_DECLARATION, IMPORT_ATTRIBUTE, IMPORT_SPECIFIER, IMPORT_DEFAULT_SPECIFIER, IMPORT_NAMESPACE_SPECIFIER, EXPORT_NAMED_DECLARATION, EXPORT_SPECIFIER, EXPORT_DEFAULT_DECLARATION, EXPORT_ALL_DECLARATION, TAGGED_TEMPLATE_EXPRESSION, IMPORT_EXPRESSION, META_PROPERTY, SEQUENCE_EXPRESSION
    }

    public NodeType getType() {
        return internalGetType(this);
    }

    public String getSourceText() {
        return sourceText;
    }

    public void setSourceText(String sourceText) {
        this.sourceText = sourceText;
    }

    private static NodeType internalGetType(Object object) {
        return switch (object) {
            case Program ignored -> NodeType.PROGRAM;
            case VariableDeclaration ignored -> NodeType.VARIABLE_DECLARATION;
            case VariableDeclarator ignored -> NodeType.VARIABLE_DECLARATOR;
            case BlockStatement ignored -> NodeType.BLOCK_STATEMENT;
            case IfStatement ignored -> NodeType.IF_STATEMENT;
            case WhileStatement ignored -> NodeType.WHILE_STATEMENT;
            case DoWhileStatement ignored -> NodeType.DO_WHILE_STATEMENT;
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
            case LabeledStatement ignored -> NodeType.LABELED_STATEMENT;
            case ExpressionStatement ignored -> NodeType.EXPRESSION_STATEMENT;
            case FunctionDeclaration ignored -> NodeType.FUNCTION_DECLARATION;
            case EmptyStatement ignored -> NodeType.EMPTY_STATEMENT;
            case NumberLiteral ignored -> NodeType.NUMBER_LITERAL;
            case BigIntLiteral ignored -> NodeType.BIGINT_LITERAL;
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
            case SequenceExpression ignored -> NodeType.SEQUENCE_EXPRESSION;
            case CallExpression ignored -> NodeType.CALL_EXPRESSION;
            case MemberExpression ignored -> NodeType.MEMBER_EXPRESSION;
            case NewExpression ignored -> NodeType.NEW_EXPRESSION;
            case FunctionExpression ignored -> NodeType.FUNCTION_EXPRESSION;
            case ArrowFunctionExpression ignored -> NodeType.ARROW_FUNCTION_EXPRESSION;
            case ClassDeclaration ignored -> NodeType.CLASS_DECLARATION;
            case ClassExpression ignored -> NodeType.CLASS_EXPRESSION;
            case ClassBody ignored -> NodeType.CLASS_BODY;
            case MethodDefinition ignored -> NodeType.METHOD_DEFINITION;
            case FieldDefinition ignored -> NodeType.FIELD_DEFINITION;
            case StaticBlock ignored -> NodeType.STATIC_BLOCK;
            case PrivateIdentifier ignored -> NodeType.PRIVATE_IDENTIFIER;
            case SuperExpression ignored -> NodeType.SUPER_EXPRESSION;
            case AwaitExpression ignored -> NodeType.AWAIT_EXPRESSION;
            case YieldExpression ignored -> NodeType.YIELD_EXPRESSION;
            case SpreadElement ignored -> NodeType.SPREAD_ELEMENT;
            case RestElement ignored -> NodeType.REST_ELEMENT;
            case ArrayPattern ignored -> NodeType.ARRAY_PATTERN;
            case ObjectPattern ignored -> NodeType.OBJECT_PATTERN;
            case AssignmentPattern ignored -> NodeType.ASSIGNMENT_PATTERN;
            case ImportDeclaration ignored -> NodeType.IMPORT_DECLARATION;
            case ImportAttribute ignored -> NodeType.IMPORT_ATTRIBUTE;
            case ImportSpecifier ignored -> NodeType.IMPORT_SPECIFIER;
            case ImportDefaultSpecifier ignored -> NodeType.IMPORT_DEFAULT_SPECIFIER;
            case ImportNamespaceSpecifier ignored -> NodeType.IMPORT_NAMESPACE_SPECIFIER;
            case ExportNamedDeclaration ignored -> NodeType.EXPORT_NAMED_DECLARATION;
            case ExportSpecifier ignored -> NodeType.EXPORT_SPECIFIER;
            case ExportDefaultDeclaration ignored -> NodeType.EXPORT_DEFAULT_DECLARATION;
            case ExportAllDeclaration ignored -> NodeType.EXPORT_ALL_DECLARATION;
            case TaggedTemplateExpression ignored -> NodeType.TAGGED_TEMPLATE_EXPRESSION;
            case ImportExpression ignored -> NodeType.IMPORT_EXPRESSION;
            case MetaProperty ignored -> NodeType.META_PROPERTY;
            default -> throw new IllegalStateException("Unexpected value: " + object);
        };
    }
}
