package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.collectBoundNames;

import java.util.ArrayList;
import java.util.Map;
import org.techhouse.simplejs.builtins.DbModule;
import org.techhouse.simplejs.builtins.ScriptModule;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.UnexpectedCharacterException;
import org.techhouse.simplejs.exceptions.UnexpectedEndOfInputException;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
import org.techhouse.simplejs.exceptions.UnterminatedCommentException;
import org.techhouse.simplejs.exceptions.UnterminatedRegexException;
import org.techhouse.simplejs.exceptions.UnterminatedStringException;
import org.techhouse.simplejs.exceptions.UnterminatedTemplateException;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.ClassDeclaration;
import org.techhouse.simplejs.nodes.ExportAllDeclaration;
import org.techhouse.simplejs.nodes.ExportDefaultDeclaration;
import org.techhouse.simplejs.nodes.ExportNamedDeclaration;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.ImportDeclaration;
import org.techhouse.simplejs.nodes.ImportDefaultSpecifier;
import org.techhouse.simplejs.nodes.ImportExpression;
import org.techhouse.simplejs.nodes.ImportNamespaceSpecifier;
import org.techhouse.simplejs.nodes.ImportSpecifier;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

// The module system: resolving the host-provided built-ins ("args"/"db"/"script") and any specifier
// the host's ModuleResolver claims, binding static import declarations, dynamic import()/import.meta,
// and collecting the named/default/re-exports. Every resolution funnels through the Interpreter's
// module registry, so a module evaluates at most once per run and a cycle is detected rather than
// recursed into. Declaration evaluation and class building route through the Interpreter and
// ClassEvaluator seams.
public final class ModuleEvaluator {
    private final Interpreter interp;
    private final ClassEvaluator classes;
    private final HostBindings host;
    private final EventLoop eventLoop;

    public ModuleEvaluator(Interpreter interp, ClassEvaluator classes, HostBindings host, EventLoop eventLoop) {
        this.interp = interp;
        this.classes = classes;
        this.host = host;
        this.eventLoop = eventLoop;
    }

    private JsValue resolveModule(String source) {
        return switch (source) {
            case "args" -> interp.cacheBuiltinModule("builtin:args",
                    () -> host.args() == null ? new JsObject() : EJsonInterop.fromEjson(host.args()));
            case "db" -> interp.cacheBuiltinModule("builtin:db", this::createDbModule);
            case "script" -> interp.cacheBuiltinModule("builtin:script",
                    () -> ScriptModule.create(this::importText, host.limits(), interp.intrinsics()));
            default -> resolveHostModule(source);
        };
    }

    private JsValue createDbModule() {
        final var database = host.database();
        if (database == null) {
            throw new JsThrowException(interp.intrinsics().makeError("Error", "Database access is not available"));
        }
        database.useErrorPrototype(interp.intrinsics().errorProto("Error"));
        return DbModule.create(database, interp.ops(), interp.intrinsics(), host.limits());
    }

    private JsValue resolveHostModule(String source) {
        final var resolver = host.moduleResolver();
        final var resolved = resolver == null ? null : resolver.resolve(source, "main");
        if (resolved == null) {
            throw new JsThrowException(interp.intrinsics().makeError("Error", "Cannot find module '" + source + "'"));
        }
        return interp.importModule(resolved.moduleId(), () -> parse(resolved.source()));
    }

    private JsValue importText(String moduleId, String source) {
        return interp.importModule(moduleId, () -> parse(source));
    }

    private Program parse(String source) {
        try {
            return Parser.parse(Lexer.lexWithPositions(source), host.strictScriptGoal());
        } catch (SyntaxErrorException | UnexpectedTokenException | UnexpectedEndOfInputException
                | UnexpectedCharacterException | UnterminatedStringException | UnterminatedTemplateException
                | UnterminatedCommentException | UnterminatedRegexException error) {
            throw new JsThrowException(interp.intrinsics().makeError("SyntaxError", error.getMessage()));
        }
    }

    // A module namespace object: the resolved module's own members plus a `default` binding that
    // mirrors what a default import would bind, so both `ns.default` and `ns.member` work.
    private JsValue moduleNamespace(String source) {
        final var module = resolveModule(source);
        final var namespace = new JsObject();
        if (module instanceof JsObject object) {
            for (final var key : object.keys()) {
                namespace.set(key, object.get(key));
            }
        }
        namespace.set("default", module);
        return namespace;
    }

    public JsValue evalImportExpression(ImportExpression expression, Environment env) {
        final var promise = new JsPromise(eventLoop);
        try {
            final var specifier = JsCoercion.toStr(interp.eval(expression.getSource(), env));
            promise.resolve(moduleNamespace(specifier));
        } catch (JsThrowException error) {
            promise.reject(error.getValue());
        }
        return promise;
    }

    public JsValue evalMetaProperty() {
        final var meta = new JsObject();
        meta.set("url", new JsString("simplejs:main"));
        return meta;
    }

    public void bindImport(ImportDeclaration declaration, Environment env) {
        final var namespace = resolveModule(declaration.getSource().getValue());
        for (final var specifier : declaration.getSpecifiers()) {
            switch (specifier) {
                case ImportDefaultSpecifier defaultSpecifier ->
                    defineModuleBinding(env, defaultSpecifier.getLocal().getName(), namespace);
                case ImportNamespaceSpecifier namespaceSpecifier ->
                    defineModuleBinding(env, namespaceSpecifier.getLocal().getName(), namespace);
                case ImportSpecifier importSpecifier -> defineModuleBinding(env, importSpecifier.getLocal().getName(),
                        moduleMember(namespace, moduleName(importSpecifier.getImported())));
                default -> throw new UnsupportedNodeException(specifier.getType().name());
            }
        }
    }

    public JsValue evalExportDefault(ExportDefaultDeclaration declaration, Environment env) {
        final var value = declaration.getDeclaration();
        if (value instanceof FunctionDeclaration functionDeclaration) {
            final var name = functionDeclaration.getName() == null
                    ? "default"
                    : functionDeclaration.getName().getName();
            return interp.makeFunction(name, functionDeclaration.getParams(), functionDeclaration.getBody(), false,
                    false, functionDeclaration.isAsync(), functionDeclaration.isGenerator(), env,
                    functionDeclaration.getSourceText());
        }
        if (value instanceof ClassDeclaration classDeclaration) {
            classes.evalClassDeclaration(classDeclaration, env);
            return env.get(classDeclaration.getId().getName());
        }
        final var evaluated = interp.eval((Expression) value, env);
        InterpreterUtils.applyInferredName(value, evaluated, "default");
        return evaluated;
    }

    public void evalExportNamed(ExportNamedDeclaration declaration, Environment env, Map<String, JsValue> exports) {
        if (declaration.getDeclaration() instanceof Statement inner) {
            interp.evalStatement(inner, env);
            final var names = new ArrayList<String>();
            collectExportedNames(inner, names);
            for (final var name : names) {
                exports.put(name, env.get(name));
            }
            return;
        }
        for (final var specifier : declaration.getSpecifiers()) {
            final var local = moduleName(specifier.getLocal());
            exports.put(moduleName(specifier.getExported()), env.get(local));
        }
    }

    public void evalExportAll(ExportAllDeclaration declaration, Map<String, JsValue> exports) {
        final var namespace = resolveModule(declaration.getSource().getValue());
        if (declaration.getExported() != null) {
            exports.put(declaration.getExported().getName(), namespace);
        } else if (namespace instanceof JsObject object) {
            for (final var key : object.keys()) {
                exports.put(key, object.get(key));
            }
        }
    }

    private void collectExportedNames(Statement declaration, java.util.List<String> names) {
        switch (declaration) {
            case VariableDeclaration variableDeclaration -> {
                for (final var declarator : variableDeclaration.getDeclarations()) {
                    collectBoundNames(declarator.getId(), names);
                }
            }
            case FunctionDeclaration functionDeclaration -> names.add(functionDeclaration.getName().getName());
            case ClassDeclaration classDeclaration -> names.add(classDeclaration.getId().getName());
            default -> {
                // no exported bindings
            }
        }
    }

    private JsValue moduleMember(JsValue namespace, String name) {
        if (namespace instanceof JsObject object) {
            return object.get(name);
        }
        return JsUndefined.getInstance();
    }

    private String moduleName(JsNode node) {
        return switch (node) {
            case Identifier identifier -> identifier.getName();
            case StringLiteral literal -> literal.getValue();
            default -> throw new UnsupportedNodeException(node.getType().name());
        };
    }

    private void defineModuleBinding(Environment env, String name, JsValue value) {
        if (!env.hasLocal(name)) {
            env.declareVar(name);
        }
        env.assign(name, value);
    }
}
