import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaMethod;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class ParserBridge {

    public static File ROOT_PATH;

    /**
     * The QDox signature you need to maintain.
     */
    public static List<CalleeTarget> parseCalleesFromSource(JavaClass declaringClass, JavaMethod method) {
        List<CalleeTarget> targets = new ArrayList<>();

        try {
            // 1. Get the physical file from QDox
            File sourceFile = new File(declaringClass.getSource().getURL().toURI());
            if (!sourceFile.exists()) {
                return targets;
            }

            // 2. Parse the file using JavaParser
            CompilationUnit cu = StaticJavaParser.parse(sourceFile);

            // 3. Find the exact matching Class and Method in the JavaParser AST
            Optional<ClassOrInterfaceDeclaration> jpClassOpt = cu.getClassByName(declaringClass.getName());
            if (jpClassOpt.isEmpty()) {
                return targets;
            }
            ClassOrInterfaceDeclaration jpClass = jpClassOpt.get();

            // Match by name and parameter count to handle basic overloading
            Optional<MethodDeclaration> jpMethodOpt = jpClass.getMethodsByName(method.getName()).stream()
                    .filter(m -> m.getParameters().size() == method.getParameters().size())
                    .findFirst();

            if (jpMethodOpt.isEmpty()) {
                return targets;
            }
            MethodDeclaration jpMethod = jpMethodOpt.get();

            // 4. Call your JavaParser method (Setting depth to 3 as it was in your original snippet)
            Set<String> visited = new HashSet<>();
            Map<String, Object> nodeMap = parseMethodToNode(cu, jpClass, jpMethod, sourceFile, 0, visited);

            // 5. Transform the Map<String, Object> back into List<CalleeTarget>
            List<Map<String, Object>> callees = (List<Map<String, Object>>) nodeMap.getOrDefault("callees", Collections.emptyList());

            for (Map<String, Object> calleeNode : callees) {
                String className = (String) calleeNode.get("className");
                String methodName = (String) calleeNode.get("methodName");

                // Extract parameter types if your JavaParser method populated them on callees
                List<Map<String, Object>> params = (List<Map<String, Object>>) calleeNode.getOrDefault("parameters", Collections.emptyList());
                List<String> paramTypes = params.stream()
                        .map(p -> (String) p.get("type"))
                        .collect(Collectors.toList());

                // Avoid duplicates in the flat list
                CalleeTarget target = new CalleeTarget(className, methodName, paramTypes);
                if (!targets.contains(target)) {
                    targets.add(target);
                }
            }

        } catch (Exception e) {
            // Fallback: If JavaParser fails or file isn't found on disk, return empty or use string-parsing fallback
            System.err.println("Failed to bridge QDox to JavaParser for: " + method.getName());
            e.printStackTrace();

        }

        return targets;
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
            // Collect both normal method calls and method references in order of occurrence
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
                    if (CallGraphGenerator.IGNORED_METHODS.contains(calledMethodName)) {
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
                            System.err.println("SymbolSolver failed to resolve: " + call.getNameAsString() +
                                    " | Reason: " + e.getMessage());
                            // Smarter unwrapping for unresolved scopes
                            if (call.getScope().isPresent()) {
                                Expression scope = call.getScope().get();

                                if (scope.isThisExpr() || scope.isSuperExpr()) {
                                    targetClassName = fullyQualifiedClassName;
                                } else if (scope.isObjectCreationExpr()) {
                                    String rawType = scope.asObjectCreationExpr().getType().asString();
                                    targetClassName = resolveFullyQualifiedType(cu, rawType);
                                } else if (scope.isClassExpr()) {
                                    String rawType = scope.asClassExpr().getType().asString();
                                    targetClassName = resolveFullyQualifiedType(cu, rawType);
                                } else if (scope.isNameExpr()) {
                                    String scopeName = scope.asNameExpr().getNameAsString();
                                    if (Character.isUpperCase(scopeName.charAt(0))) {
                                        targetClassName = resolveFullyQualifiedType(cu, scopeName);
                                    } else {
                                        String varType = resolveVariableType(scopeName, method, clazz);
                                        if (varType != null) {
                                            targetClassName = resolveFullyQualifiedType(cu, varType);
                                        } else {
                                            targetClassName = "UnresolvedClass<" + scopeName + ">";
                                        }
                                    }
                                } else if (scope.isMethodCallExpr()) {
                                    // ---> FIX: Handle chained method calls like var.getSomething().method() <---
                                    MethodCallExpr chainedCall = scope.asMethodCallExpr();
                                    try {
                                        // Try to ask JavaParser what the return type of the intermediate method is
                                        String returnType = chainedCall.resolve().getReturnType().describe();
                                        targetClassName = resolveFullyQualifiedType(cu, returnType);
                                    } catch (Exception ex) {
                                        // If we can't find the external class, at least clean up the JSON
                                        targetClassName = "UnresolvedReturnType<" + chainedCall.getNameAsString() + ">";
                                    }
                                } else if (scope.isEnclosedExpr() || scope.isCastExpr()) {
                                    // Handles casts like: ((String) myVar).length()
                                    targetClassName = "CastOrEnclosedExpression";
                                } else {
                                    targetClassName = "UnresolvedClass<" + scope.getClass().getSimpleName() + ">";
                                }
                            } else {
                                targetClassName = fullyQualifiedClassName;
                            }
                        }
                    }

                } else if (expr instanceof MethodReferenceExpr) {
                    MethodReferenceExpr ref = (MethodReferenceExpr) expr;
                    calledMethodName = ref.getIdentifier();
                    if (CallGraphGenerator.IGNORED_METHODS.contains(calledMethodName)) {
                        continue;
                    }

                    // Resolve target class for method references (e.g., ClassName::methodName or instance::methodName)
                    try {
                        ResolvedMethodDeclaration resolvedRef = ref.resolve();
                        targetClassName = resolvedRef.declaringType().getQualifiedName();
                    } catch (Exception e) {
                        // Fallback to evaluating the scope expression manually
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

    private static String resolveFullyQualifiedType(CompilationUnit cu, String typeStr) {
        if (typeStr == null) return "Unknown";

        // 1. Check if the type is explicitly imported (e.g., import com.myapp.TableUSER;)
        Optional<String> importedType = cu.getImports().stream()
                .map(imp -> imp.getNameAsString())
                .filter(name -> name.endsWith("." + typeStr))
                .findFirst();

        if (importedType.isPresent()) {
            return importedType.get();
        }

        // 2. Check for wildcard imports (e.g., import java.util.*;)
        // (In a perfect parser you'd check the classpath, but as a string fallback we skip this)
        if (typeStr.equals("String") || typeStr.equals("List") || typeStr.equals("Map")) {
            return "java.lang." + typeStr; // Basic assumption for standard Java types
        }

        // 3. If no import matches, assume it belongs to the SAME package as the current file
        Optional<String> packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString());
        if (packageName.isPresent()) {
            return packageName.get() + "." + typeStr;
        }

        // 4. Fallback if there is no package declaration (default package)
        return typeStr;
    }

    private static ResolvedMethodTarget resolveMethodTarget(CompilationUnit currentCu, ClassOrInterfaceDeclaration currentClass, MethodDeclaration currentMethod, MethodCallExpr call) {
        try {
            // 1. Let the SymbolSolver do all the heavy lifting (handles scopes, generics, overloading, etc.)
            ResolvedMethodDeclaration resolvedMethod = call.resolve();

            // 2. Get the fully qualified name of the class declaring this method (e.g., "com.example.MyClass")
            String qualifiedClassName = resolvedMethod.declaringType().getQualifiedName();

            // 3. Find the source file
            // Note: Make sure your findSourceFile method handles fully qualified names!
            File targetFile = findSourceFile(qualifiedClassName);

            if (targetFile != null) {
                // 4. Parse or retrieve the target file from cache
                CompilationUnit targetCu = CallGraphGenerator.fileToCuCache.computeIfAbsent(targetFile, f -> {
                    try {
                        return StaticJavaParser.parse(f);
                    } catch (Exception e) {
                        return null;
                    }
                });

                if (targetCu != null) {
                    // 5. Find the Class/Interface in the target AST
                    String simpleClassName = resolvedMethod.declaringType().getName();
                    Optional<ClassOrInterfaceDeclaration> targetClass = targetCu.getClassByName(simpleClassName);

                    if (targetClass.isPresent()) {
                        // 6. Bridge the resolved symbol back to the AST node (MethodDeclaration)
// Safely extract the Node and cast it to a MethodDeclaration
                        Optional<MethodDeclaration> targetMethodNode = resolvedMethod.toAst()
                                .filter(node -> node instanceof MethodDeclaration)
                                .map(node -> (MethodDeclaration) node);
                        if (targetMethodNode.isPresent()) {
                            return new ResolvedMethodTarget(targetFile, targetCu, targetClass.get(), targetMethodNode.get());
                        } else {
                            // Fallback: If toAst() fails, match manually by name and parameter count
                            Optional<MethodDeclaration> manualMatch = targetClass.get().getMethodsByName(resolvedMethod.getName()).stream()
                                    .filter(m -> m.getParameters().size() == resolvedMethod.getNumberOfParams())
                                    .findFirst();

                            if (manualMatch.isPresent()) {
                                return new ResolvedMethodTarget(targetFile, targetCu, targetClass.get(), manualMatch.get());
                            }
                        }
                    }
                }
            }
        } catch (UnsolvedSymbolException e) {
            // The solver couldn't find the target (e.g., it's an external library not configured in your TypeSolver)
            return null;
        } catch (Exception e) {
            // Catch any other unexpected resolution/parsing errors
            return null;
        }

        return null;
    }

    private static File findSourceFile(String className) {
        try {
            return Files.walk(ROOT_PATH.toPath())
                    .filter(p -> p.toFile().isFile() && p.toFile().getName().equals(className + ".java"))
                    .map(Path::toFile)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String resolveVariableType(String varName, MethodDeclaration method, ClassOrInterfaceDeclaration clazz) {
        // 1. Check Method Parameters
        for (Parameter param : method.getParameters()) {
            if (param.getNameAsString().equals(varName)) {
                return param.getType().asString();
            }
        }

        // 2. Check Local Variables inside the method
        List<VariableDeclarator> localVars = method.findAll(VariableDeclarator.class);
        for (VariableDeclarator var : localVars) {
            if (var.getNameAsString().equals(varName)) {
                return var.getType().asString();
            }
        }

        // 3. Check Class Fields
        for (FieldDeclaration field : clazz.getFields()) {
            for (VariableDeclarator var : field.getVariables()) {
                if (var.getNameAsString().equals(varName)) {
                    return var.getType().asString();
                }
            }
        }

        return null; // Variable declaration not found in this class/method
    }
}