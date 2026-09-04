package com.codetools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

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

    private static Map<String, Object> parseMethodToNode(CompilationUnit cu, ClassOrInterfaceDeclaration clazz, MethodDeclaration method, File sourceFile, int depth, Set<String> visited) {
        Map<String, Object> node = new LinkedHashMap<>();

        String packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        String className = clazz.getNameAsString();
        String fullyQualifiedClassName = packageName.isEmpty() ? className : packageName + "." + className;
        String methodName = method.getNameAsString();
        String fqSymbol = fullyQualifiedClassName + "." + methodName;

        node.put("fullyQualifiedSymbol", fqSymbol);
        node.put("className", fullyQualifiedClassName);
        
        String relativePath = projectRoot.toURI().relativize(sourceFile.toURI()).getPath();
        node.put("filePath", relativePath);
        node.put("methodName", methodName);
        
        List<String> modifiers = method.getModifiers().stream().map(m -> m.getKeyword().asString()).collect(Collectors.toList());
        node.put("modifiers", modifiers);

        node.put("classAnnotations", mapAnnotations(clazz.getAnnotations()));
        node.put("methodAnnotations", mapAnnotations(method.getAnnotations()));
        node.put("resolvedImplementation", null);
        node.put("returnType", method.getType().asString());

        List<Map<String, Object>> parameters = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            Map<String, Object> paramMap = new LinkedHashMap<>();
            paramMap.put("name", p.getNameAsString());
            paramMap.put("type", p.getType().asString());
            parameters.add(paramMap);
        }
        node.put("parameters", parameters);

        int startLine = method.getBegin().map(p -> p.line).orElse(0);
        int endLine = method.getEnd().map(p -> p.line).orElse(0);
        node.put("startLine", startLine);
        node.put("endLine", endLine);
        node.put("depth", depth);
        node.put("status", null);

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
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                String calledMethodName = call.getNameAsString();
                
                if (IGNORED_METHODS.contains(calledMethodName)) {
                    continue;
                }

                if (!visited.contains(fqSymbol + "->" + calledMethodName)) {
                    visited.add(fqSymbol + "->" + calledMethodName);
                    
                    ResolvedMethodTarget target = resolveMethodTarget(cu, clazz, method, call);
                    if (target != null && target.methodDecl != null) {
                        callees.add(parseMethodToNode(target.cu, target.clazz, target.methodDecl, target.file, depth + 1, visited));
                    } else {
                        String targetClassName = fullyQualifiedClassName;
                        if (call.getScope().isPresent()) {
                            String scope = call.getScope().get().toString();
                            
                            // 1. Check method-scoped local variables first
                            Optional<VariableDeclarationExpr> localDecl = method.findAll(VariableDeclarationExpr.class).stream()
                                    .filter(v -> v.getVariables().stream().anyMatch(var -> var.getNameAsString().equals(scope)))
                                    .findFirst();
                            
                            if (localDecl.isPresent()) {
                                targetClassName = localDecl.get().getElementType().asString();
                            } else {
                                // 2. Check class-level fields
                                Optional<FieldDeclaration> field = cu.findAll(FieldDeclaration.class).stream()
                                        .filter(f -> f.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(scope)))
                                        .findFirst();
                                if (field.isPresent()) {
                                    List<ClassOrInterfaceType> types = field.get().findAll(ClassOrInterfaceType.class);
                                    if (!types.isEmpty()) {
                                        targetClassName = types.get(0).getNameAsString();
                                    }
                                } else {
                                    // 3. Fallback to capitalized scope name
                                    targetClassName = scope.substring(0, 1).toUpperCase() + scope.substring(1);
                                }
                            }
                        }

                        Map<String, Object> leaf = new LinkedHashMap<>();
                        leaf.put("fullyQualifiedSymbol", targetClassName + "." + calledMethodName);
                        leaf.put("className", targetClassName);
                        leaf.put("filePath", relativePath);
                        leaf.put("methodName", calledMethodName);
                        leaf.put("modifiers", List.of());
                        leaf.put("classAnnotations", List.of());
                        leaf.put("methodAnnotations", List.of());
                        leaf.put("resolvedImplementation", null);
                        leaf.put("returnType", null);
                        leaf.put("parameters", List.of());
                        leaf.put("startLine", 0);
                        leaf.put("endLine", 0);
                        leaf.put("depth", depth + 1);
                        leaf.put("status", "method_not_found");
                        leaf.put("sourceCode", null);
                        leaf.put("callees", List.of());
                        callees.add(leaf);
                    }
                }
            }
        }
        node.put("callees", callees);
        return node;
    }

    private static List<Map<String, Object>> mapAnnotations(List<AnnotationExpr> annotations) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AnnotationExpr ann : annotations) {
            Map<String, Object> annMap = new LinkedHashMap<>();
            annMap.put("name", ann.getNameAsString());
            annMap.put("properties", Map.of());
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
        Optional<MethodDeclaration> localMatch = currentClass.getMethodsByName(methodName).stream().findFirst();
        if (localMatch.isPresent()) {
            return new ResolvedMethodTarget(findSourceFile(currentClass.getNameAsString()), currentCu, currentClass, localMatch.get());
        }

        if (call.getScope().isPresent()) {
            String scope = call.getScope().get().toString();
            String typeName = null;

            // Check local variables in current method
            Optional<VariableDeclarationExpr> localDecl = currentMethod.findAll(VariableDeclarationExpr.class).stream()
                    .filter(v -> v.getVariables().stream().anyMatch(var -> var.getNameAsString().equals(scope)))
                    .findFirst();

            if (localDecl.isPresent()) {
                typeName = localDecl.get().getElementType().asString();
            } else {
                // Check class fields
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
                                Optional<MethodDeclaration> targetMethod = targetClass.get().getMethodsByName(methodName).stream().findFirst();
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
