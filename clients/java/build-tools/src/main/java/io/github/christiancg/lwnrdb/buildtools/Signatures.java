package io.github.christiancg.lwnrdb.buildtools;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.Type;
import java.util.stream.Collectors;

public final class Signatures {
    private Signatures() {
    }

    public static String normalise(String heading) {
        return heading.strip().replaceAll("\\s+", "").replace(",", ", ");
    }

    public static String of(CallableDeclaration<?> callable) {
        return normalise(callable.getNameAsString() + "("
                + callable.getParameters().stream().map(Signatures::parameterType).collect(Collectors.joining(", "))
                + ")");
    }

    private static String parameterType(Parameter parameter) {
        return erased(parameter.getType()) + (parameter.isVarArgs() ? "..." : "");
    }

    private static String erased(Type type) {
        final var text = type.asString();
        final var generic = text.indexOf('<');
        final var raw = generic < 0 ? text : text.substring(0, generic) + text.substring(text.lastIndexOf('>') + 1);
        return raw.substring(raw.lastIndexOf('.', raw.indexOf('[') < 0 ? raw.length() : raw.indexOf('[')) + 1);
    }
}
