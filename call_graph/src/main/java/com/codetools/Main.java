package com.codetools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class Main {

    private static File projectRoot;
    private static final Map<File, CompilationUnit> fileToCuCache = new HashMap<>();

    private static final Set<String> IGNORED_METHODS = Set.of(
            "stream", "map", "filter", "collect", "forEach", "peek", "sorted", "distinct",
            "orElse", "orElseGet", "orElseThrow", "isPresent", "isEmpty", "size",
            "equals", "hashCode", "toString", "getClass", "get", "set", "add", "remove",
            "put", "contains", "iterator", "hasNext", "next", "toSet", "toList", "partition"
    );

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: mvn exec:java -Dexec.args=\"src/main/java/com/example/MyClass.java myMethod\"");
            return;
        }

        File initialFile = new File(args[0]);
        String initialMethodName = args[1];

        projectRoot = findProjectRoot(initialFile);
        System.out.println("Detected Project Root: " + projectRoot.getAbsolutePath());

        try {
            CompilationUnit cu = fileToCuCache.computeIfAbsent(initialFile, f -> {
                try {
                    return StaticJavaParser.parse(f);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to parse: " + f.getName(), e);
                }
            });

            ClassOrInterfaceDeclaration parentClass = cu.findFirst(ClassOrInterfaceDeclaration.class)
                    .orElseThrow(() -> new RuntimeException("No class found in: " + initialFile.getName()));

            MethodDeclaration targetMethod = parentClass.getMethodsByName(initialMethodName).stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Method not found: " + initialMethodName));

            Map<String, Object> rootNode = parseMethodToNode(cu, parentClass, targetMethod, initialFile, 0, new HashSet<>());
            
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("gitCommitId", "34d53510f91ba9fa03115a048a6a6ae578cebab3");
            wrapper.put("generatedAt", Instant.now().toString());
            wrapper.put("callGraph", rootNode);

            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            File outputFile = new File("call_graph.json");
            mapper.writeValue(outputFile, wrapper);
            System.out.println("Successfully generated call trace JSON: " + outputFile.getAbsolutePath());

        } catch (Exception e) {
            System.err.println("Failed to generate call graph JSON: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static Map<String, Object> parseMethodToNode(
            CompilationUnit cu,
            ClassOrInterfaceDeclaration clazz,
            MethodDeclaration method,
            File sourceFile,
            int depth,
            Set<String> visited) {

        Map<String, Object> node = new LinkedHashMap<>();

        String fullyQualifiedClassName = clazz.getFullyQualifiedName().orElseGet(() -> {
            String packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            String className = clazz.getNameAsString();
            return packageName.isEmpty() ? className : packageName + "." + className;
        });

        String methodName = method.getNameAsString();
        String fqSymbol = fullyQualifiedClassName + "." + methodName;

        node.put("fullyQualifiedSymbol", fqSymbol);
        node.put("className", fullyQualifiedClassName);
        node.put("filePath", sourceFile.getAbsolutePath());
        node.put("methodName", methodName);

        List<String> modifiers = method.getModifiers().stream()
                .map(m -> m.getKeyword().asString())
                .collect(Collectors.toList());
        node.put("modifiers", modifiers);

        node.put("classAnnotations", mapAnnotations(clazz.getAnnotations()));
        node.put("methodAnnotations", mapAnnotations(method.getAnnotations()));
        node.put("resolvedImplementation", null);

        // Resolve return type
        String returnTypeStr = method.getType().asString();
        try {
            returnTypeStr = method.getType().resolve().describe();
        } catch (Exception ignored) {
            returnTypeStr = resolveFullyQualifiedType(cu, returnTypeStr);
        }
        node.put("returnType", returnTypeStr);

        // Resolve parameter types
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            Map<String, Object> paramMap = new LinkedHashMap<>();
            paramMap.put("name", p.getNameAsString());

            String paramTypeStr = p.getType().asString();
            try {
                paramTypeStr = p.getType().resolve().describe();
            } catch (Exception ignored) {
                paramTypeStr = resolveFullyQualifiedType(cu, paramTypeStr);
            }

            paramMap.put("type", paramTypeStr);
            parameters.add(paramMap);
        }
        node.put("parameters", parameters);

        node.put("startLine", method.getBegin().map(p -> p.line).orElse(0));
        node.put("endLine", method.getEnd().map(p -> p.line).orElse(0));
        node.put("depth", depth);
        node.put("status", null);

        // Source code extraction
        List<String> sourceCodeLines = new ArrayList<>();
        if (method.getBody().isPresent()) {
            String bodyStr = method.getBody().get().toString();
            if (bodyStr.startsWith("{") && bodyStr.endsWith("}")) {
                bodyStr = bodyStr.substring(1, bodyStr.length() - 1).trim();
            }
            sourceCodeLines = Arrays.asList(bodyStr.split("\\r?\\n"));
        }
        node.put("sourceCode", sourceCodeLines);

        List<Map<String, Object>> callees = new ArrayList<>();

        if (depth < 4) {
            // FIX 1: Walk the AST once to preserve top-down, left-to-right source code order
            List<Expression> invocationNodes = method.findAll(Expression.class).stream()
                    .filter(expr -> expr instanceof MethodCallExpr || expr instanceof MethodReferenceExpr)
                    .collect(Collectors.toList());

            for (Expression expr : invocationNodes) {
                String calledMethodName;
                String targetClassName = null;
                ResolvedMethodTarget target = null;

                if (expr instanceof MethodCallExpr) {
                    MethodCallExpr call = (MethodCallExpr) expr;
                    calledMethodName = call.getNameAsString();
                    if (IGNORED_METHODS.contains(calledMethodName)) {
                        continue;
                    }

                    // Try local AST resolution first
                    target = resolveMethodTarget(cu, clazz, method, call);
                    if (target != null && target.clazz != null) {
                        targetClassName = target.clazz.getFullyQualifiedName().orElse(null);
                    }

                    // Fall back to SymbolSolver for method calls
                    if (targetClassName == null) {
                        try {
                            ResolvedMethodDeclaration resolvedCall = call.resolve();
                            targetClassName = resolvedCall.declaringType().getQualifiedName();
                        } catch (Exception e) {
                            // FIX 2: Safely handle unresolved scopes without treating variables as Classes
                            if (call.getScope().isPresent()) {
                                Expression scope = call.getScope().get();
                                if (scope.isThisExpr() || scope.isSuperExpr()) {
                                    targetClassName = fullyQualifiedClassName;
                                } else {
                                    targetClassName = "UnresolvedClass<" + scope.toString() + ">";
                                }
                            } else {
                                // No scope means local method call
                                targetClassName = fullyQualifiedClassName;
                            }
                        }
                    }

                } else if (expr instanceof MethodReferenceExpr) {
                    MethodReferenceExpr ref = (MethodReferenceExpr) expr;
                    calledMethodName = ref.getIdentifier();
                    if (IGNORED_METHODS.contains(calledMethodName)) {
                        continue;
                    }

                    // Resolve target class for method references
                    try {
                        ResolvedMethodDeclaration resolvedRef = ref.resolve();
                        targetClassName = resolvedRef.declaringType().getQualifiedName();
                    } catch (Exception e) {
                        // FIX 2 (Applied to References): Safely evaluate scope
                        Expression scope = ref.getScope();
                        if (scope.isThisExpr() || scope.isSuperExpr()) {
                            targetClassName = fullyQualifiedClassName;
                        } else {
                            targetClassName = "UnresolvedClass<" + scope.toString() + ">";
                        }
                    }
                } else {
                    continue;
                }

                String targetFqSymbol = targetClassName + "." + calledMethodName;
                String visitKey = fqSymbol + "->" + targetFqSymbol;

                if (!visited.contains(visitKey)) {
                    visited.add(visitKey);

                    if (target != null && target.methodDecl != null) {
                        callees.add(parseMethodToNode(target.cu, target.clazz, target.methodDecl, target.file, depth + 1, visited));
                    } else {
                        Map<String, Object> leaf = new LinkedHashMap<>();
                        leaf.put("fullyQualifiedSymbol", targetFqSymbol);
                        leaf.put("className", targetClassName);
                        leaf.put("filePath", sourceFile.getAbsolutePath());
                        leaf.put("methodName", calledMethodName);
                        leaf.put("modifiers", Collections.emptyList());
                        leaf.put("classAnnotations", Collections.emptyList());
                        leaf.put("methodAnnotations", Collections.emptyList());
                        leaf.put("resolvedImplementation", null);
                        leaf.put("returnType", null);
                        leaf.put("parameters", Collections.emptyList());
                        leaf.put("startLine", 0);
                        leaf.put("endLine", 0);
                        leaf.put("depth", depth + 1);
                        leaf.put("status", "method_not_found");
                        leaf.put("sourceCode", null);
                        leaf.put("callees", Collections.emptyList());
                        callees.add(leaf);
                    }
                }
            }
        }

        node.put("callees", callees);
        return node;
    }
    private static String resolveFullyQualifiedType(CompilationUnit cu, String typeStr) {
        if (typeStr == null || typeStr.isEmpty()) {
            return typeStr;
        }
        if (typeStr.contains(".")) {
            return typeStr;
        }

        Set<String> primitivesOrVoid = Set.of(
                "void", "boolean", "byte", "char", "short", "int", "long", "float", "double"
        );
        if (primitivesOrVoid.contains(typeStr)) {
            return typeStr;
        }

        Set<String> javaLangTypes = Set.of(
                "String", "Long", "Integer", "Double", "Float", "Boolean", "Byte", "Short", "Character",
                "Object", "Class", "Math", "System", "Runtime", "Thread", "Throwable", "Exception", "RuntimeException"
        );
        if (javaLangTypes.contains(typeStr)) {
            return "java.lang." + typeStr;
        }

        for (var importDecl : cu.getImports()) {
            String importName = importDecl.getNameAsString();
            if (importName.endsWith("." + typeStr)) {
                return importName;
            }
        }

        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        if (!pkg.isEmpty()) {
            return pkg + "." + typeStr;
        }

        return typeStr;
    }

    private static List<Map<String, Object>> mapAnnotations(List<AnnotationExpr> annotations) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AnnotationExpr ann : annotations) {
            Map<String, Object> annMap = new LinkedHashMap<>();
            annMap.put("name", ann.getNameAsString());

            Map<String, String> properties = new LinkedHashMap<>();
            if (ann instanceof NormalAnnotationExpr) {
                NormalAnnotationExpr normalAnn = (NormalAnnotationExpr) ann;
                for (MemberValuePair pair : normalAnn.getPairs()) {
                    properties.put(pair.getNameAsString(), pair.getValue().toString());
                }
            } else if (ann instanceof SingleMemberAnnotationExpr) {
                SingleMemberAnnotationExpr singleAnn = (SingleMemberAnnotationExpr) ann;
                properties.put("value", singleAnn.getMemberValue().toString());
            }
            annMap.put("properties", properties);
            result.add(annMap);
        }
        return result;
    }

    private static class ResolvedMethodTarget {
        File file;
        CompilationUnit cu;
        ClassOrInterfaceDeclaration clazz;
        MethodDeclaration methodDecl;
        ResolvedMethodTarget(File file, CompilationUnit cu, ClassOrInterfaceDeclaration clazz, MethodDeclaration methodDecl) {
            this.file = file;
            this.cu = cu;
            this.clazz = clazz;
            this.methodDecl = methodDecl;
        }
    }

    private static ResolvedMethodTarget resolveMethodTarget(CompilationUnit currentCu, ClassOrInterfaceDeclaration currentClass, MethodDeclaration currentMethod, MethodCallExpr call) {
        String methodName = call.getNameAsString();
        int argCount = call.getArguments().size();

        Optional<MethodDeclaration> localMatch = currentClass.getMethodsByName(methodName).stream()
                .filter(m -> m.getParameters().size() == argCount)
                .findFirst();

        if (localMatch.isPresent()) {
            return new ResolvedMethodTarget(findSourceFile(currentClass.getNameAsString()), currentCu, currentClass, localMatch.get());
        }

        if (call.getScope().isPresent()) {
            String scope = call.getScope().get().toString();
            String typeName = null;

            Optional<VariableDeclarationExpr> localDecl = currentMethod.findAll(VariableDeclarationExpr.class).stream()
                    .filter(v -> v.getVariables().stream().anyMatch(var -> var.getNameAsString().equals(scope)))
                    .findFirst();

            if (localDecl.isPresent()) {
                typeName = localDecl.get().getElementType().asString();
            } else {
                Optional<FieldDeclaration> field = currentCu.findAll(FieldDeclaration.class).stream()
                        .filter(f -> f.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(scope)))
                        .findFirst();
                if (field.isPresent()) {
                    List<ClassOrInterfaceType> types = field.get().findAll(ClassOrInterfaceType.class);
                    if (!types.isEmpty()) {
                        typeName = types.get(0).getNameAsString();
                    }
                }
            }

            if (typeName != null) {
                File targetFile = findSourceFile(typeName);
                if (targetFile != null) {
                    try {
                        CompilationUnit targetCu = fileToCuCache.computeIfAbsent(targetFile, f -> {
                            try { return StaticJavaParser.parse(f); } catch (Exception e) { return null; }
                        });
                        if (targetCu != null) {
                            Optional<ClassOrInterfaceDeclaration> targetClass = targetCu.findFirst(ClassOrInterfaceDeclaration.class);
                            if (targetClass.isPresent()) {
                                Optional<MethodDeclaration> targetMethod = targetClass.get().getMethodsByName(methodName).stream()
                                        .filter(m -> m.getParameters().size() == argCount)
                                        .findFirst();
                                if (targetMethod.isPresent()) {
                                    return new ResolvedMethodTarget(targetFile, targetCu, targetClass.get(), targetMethod.get());
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    private static File findProjectRoot(File file) {
        File current = file.getAbsoluteFile();
        File rootCandidate = current.getParentFile();
        while (current != null) {
            if (new File(current, "pom.xml").exists()) {
                rootCandidate = current;
            }
            current = current.getParentFile();
        }
        return rootCandidate;
    }

    private static File findSourceFile(String className) {
        try {
            return Files.walk(projectRoot.toPath())
                    .filter(p -> p.toFile().isFile() && p.toFile().getName().equals(className + ".java"))
                    .map(Path::toFile)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
