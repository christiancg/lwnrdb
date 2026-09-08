package org.techhouse.simplejs.internal.interpreter;

import java.util.Locale;
import java.util.function.Supplier;
import org.techhouse.simplejs.builtins.GlobalScope;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.ScriptLimitException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.Completion;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.ModuleResult;
import org.techhouse.simplejs.nodes.ExportAllDeclaration;
import org.techhouse.simplejs.nodes.ExportDefaultDeclaration;
import org.techhouse.simplejs.nodes.ExportNamedDeclaration;
import org.techhouse.simplejs.nodes.ImportDeclaration;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public record ModuleLifecycle(Interpreter interp) {
    public Interpreter.ProgramOutcome evaluateTopLevelModule(Program program) {
        final var env = Environment.global();
        final var globalThis = GlobalScope.install(env, interp.eventLoop, interp::callValue, interp::iterableToList,
                interp.host.console(), interp.ops, interp.host.network(), interp.host.limits(), interp.intrinsics);
        env.defineThis(globalThis);
        interp.globalObjectValue = globalThis;
        interp.globalEnv = env;
        final var result = new ModuleResult();
        final var coroutine = new Coroutine();
        interp.coroutines.add(coroutine);
        coroutine.markAsync();
        coroutine.startAsync(() -> {
            interp.currentCoroutine.set(coroutine);
            if (interp.moduleBodyWrapper == null) {
                evaluateModuleBody(program, env, result);
            } else {
                interp.moduleBodyWrapper.around(() -> evaluateModuleBody(program, env, result));
            }
            return JsUndefined.getInstance();
        });
        markContractPromiseHandled(result);
        interp.eventLoop.drain(interp.deadlineNanos);
        return new Interpreter.ProgramOutcome(result.last, result.hasReturn, result.returnValue, result.exportDefault,
                result.namedExports);
    }

    public void markContractPromiseHandled(ModuleResult result) {
        final var contract = result.hasReturn ? result.returnValue : result.exportDefault;
        if (contract instanceof JsPromise promise) {
            promise.markHandled();
        }
    }

    public void reportUnhandledRejections() {
        if (!interp.host.limits().reportUnhandledRejections()) {
            return;
        }
        final var sink = interp.host.console();
        if (sink == null) {
            return;
        }
        for (final var promise : interp.eventLoop.promises()) {
            if (promise.isUnhandledRejection()) {
                sink.accept("UnhandledPromiseRejection: " + JsCoercion.toStr(promise.getResult()));
            }
        }
    }

    public void evaluateModuleBody(Program program, Environment env, ModuleResult result) {
        for (final var statement : program.getBody()) {
            if (statement instanceof ImportDeclaration importDeclaration) {
                interp.modules.bindImport(importDeclaration, env);
            }
        }
        VarHoisting.hoistVars(program.getBody(), env);
        interp.hoist(program.getBody(), env);
        RuntimeException pending = null;
        try {
            runModuleStatements(program, env, result);
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException error) {
            pending = error;
        }
        interp.statements.disposeScope(env, Completion.empty(), pending);
    }

    public JsValue importModule(String moduleId, String displayName, Supplier<Program> parser) {
        final var state = interp.moduleRegistry.stateOf(moduleId);
        if (state == ModuleRegistry.State.EVALUATED) {
            return interp.moduleRegistry.namespaceOf(moduleId);
        }
        if (state == ModuleRegistry.State.FAILED) {
            throw interp.moduleRegistry.failureOf(moduleId);
        }
        if (state == ModuleRegistry.State.EVALUATING) {
            throw new JsThrowException(
                    interp.intrinsics.makeError("Error", "Circular import of module '" + moduleId + "'"));
        }
        if (interp.maxModuleDepth >= 0 && interp.moduleDepth >= interp.maxModuleDepth) {
            throw new ScriptLimitException("Script exceeded its maximum module nesting depth");
        }
        interp.moduleRegistry.beginEvaluation(moduleId);
        interp.moduleDepth++;
        final var previousModule = interp.callStack.enterModule(displayName);
        try {
            final var namespace = evaluateModule(parser.get());
            interp.moduleRegistry.complete(moduleId, namespace);
            return namespace;
        } catch (RuntimeException error) {
            interp.moduleRegistry.fail(moduleId, error);
            throw error;
        } finally {
            interp.callStack.exitModule(previousModule);
            interp.moduleDepth--;
        }
    }

    public JsValue cacheBuiltinModule(String moduleId, Supplier<JsValue> factory) {
        final var state = interp.moduleRegistry.stateOf(moduleId);
        if (state == ModuleRegistry.State.EVALUATED) {
            return interp.moduleRegistry.namespaceOf(moduleId);
        }
        final var module = factory.get();
        interp.moduleRegistry.complete(moduleId, module);
        return module;
    }

    public JsValue evaluateModule(Program program) {
        final var env = interp.globalEnv.functionChild();
        final var result = new ModuleResult();
        evaluateModuleBody(program, env, result);
        final var namespace = new JsObject();
        result.namedExports.forEach(namespace::set);
        namespace.set("default", result.exportDefault == null ? JsUndefined.getInstance() : result.exportDefault);
        return namespace;
    }

    public void runModuleStatements(Program program, Environment env, ModuleResult result) {
        moduleLoop : for (final var statement : program.getBody()) {
            switch (statement) {
                case ImportDeclaration ignored -> {
                }
                case ExportDefaultDeclaration exportDefaultDeclaration ->
                    result.exportDefault = interp.modules.evalExportDefault(exportDefaultDeclaration, env);
                case ExportNamedDeclaration exportNamedDeclaration ->
                    interp.modules.evalExportNamed(exportNamedDeclaration, env, result.namedExports);
                case ExportAllDeclaration exportAllDeclaration ->
                    interp.modules.evalExportAll(exportAllDeclaration, result.namedExports);
                default -> {
                    final var completion = interp.evalStatement(statement, env);
                    if (completion.kind() == Completion.Kind.RETURN) {
                        result.hasReturn = true;
                        result.returnValue = completion.value();
                        break moduleLoop;
                    }
                    if (!completion.isNormal()) {
                        throw new SyntaxErrorException(
                                "Illegal " + completion.kind().name().toLowerCase(Locale.ROOT) + " statement");
                    }
                    result.last = completion.value();
                }
            }
        }
    }

}
