package io.github.christiancg.lwnrdb.buildtools;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PublicApi {
    private static final String INTERNAL_PACKAGE_SEGMENT = ".internal";
    private static final String OVERRIDE = "Override";

    static {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    private PublicApi() {
    }

    public record DocumentedType(String qualifiedName, TypeDeclaration<?> declaration,
            Map<String, NodeWithJavadoc<?>> members, Set<String> overrides) {
    }

    public static CompilationUnit parse(String source) {
        return StaticJavaParser.parse(source);
    }

    public static boolean isInternal(CompilationUnit unit) {
        return unit.getPackageDeclaration().map(PackageDeclaration::getNameAsString)
                .filter(name -> name.contains(INTERNAL_PACKAGE_SEGMENT)).isPresent();
    }

    public static List<DocumentedType> typesOf(CompilationUnit unit) {
        final var packageName = unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString() + ".")
                .orElse("");
        final var types = new ArrayList<DocumentedType>();
        for (final var type : unit.getTypes()) {
            collect(packageName + type.getNameAsString(), type, types);
        }
        return types;
    }

    private static void collect(String qualifiedName, TypeDeclaration<?> type, List<DocumentedType> types) {
        if (!isVisible(type)) {
            return;
        }
        final var overrides = new HashSet<String>();
        types.add(new DocumentedType(qualifiedName, type, membersOf(type, overrides), overrides));
        for (final var member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                collect(qualifiedName + "." + nested.getNameAsString(), nested, types);
            }
        }
    }

    private static Map<String, NodeWithJavadoc<?>> membersOf(TypeDeclaration<?> type, Set<String> overrides) {
        final var members = new LinkedHashMap<String, NodeWithJavadoc<?>>();
        if (type instanceof EnumDeclaration enumeration) {
            for (final EnumConstantDeclaration constant : enumeration.getEntries()) {
                members.put(constant.getNameAsString(), constant);
            }
        }
        for (final BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof CallableDeclaration<?> callable && isVisibleIn(callable, type)) {
                final var signature = Signatures.of(callable);
                members.put(signature, callable);
                if (callable.isAnnotationPresent(OVERRIDE)) {
                    overrides.add(signature);
                }
            } else if (member instanceof AnnotationMemberDeclaration annotationMember) {
                members.put(annotationMember.getNameAsString() + "()", annotationMember);
            } else if (member instanceof FieldDeclaration field && isVisibleIn(field, type)) {
                field.getVariables().forEach(variable -> members.put(variable.getNameAsString(), field));
            }
        }
        return members;
    }

    private static boolean isVisibleIn(NodeWithModifiers<?> declaration, TypeDeclaration<?> owner) {
        if (owner.isClassOrInterfaceDeclaration() && owner.asClassOrInterfaceDeclaration().isInterface()) {
            return !declaration.hasModifier(Modifier.Keyword.PRIVATE);
        }
        return isVisible(declaration);
    }

    private static boolean isVisible(NodeWithModifiers<?> declaration) {
        return declaration.hasModifier(Modifier.Keyword.PUBLIC) || declaration.hasModifier(Modifier.Keyword.PROTECTED);
    }
}
